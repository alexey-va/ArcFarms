package ru.ruscrafting.farms.paper.lumber

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.ActivityBarKey
import ru.ruscrafting.farms.paper.lumber.scene.LumberPresentation
import ru.ruscrafting.farms.paper.lumber.scene.LumberSceneGeometry
import ru.ruscrafting.farms.paper.worksite.*
import java.util.UUID
import java.util.logging.Level
import kotlin.math.floor

internal enum class LumberIndexStatus { PENDING, NO_LOADED_TREE, NO_SAFE_TREE, READY }

/** One shared closed-beta round. Every accepted transition is durable before its presentation or credit. */
internal class LumberController(
    private val rules: LumbermillSettings,
    private val serverId: String,
    private val locale: ArcFarmsLocale,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val storage: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val serviceItems: WorksiteServiceItems,
    private val presentation: LumberPresentation,
    initial: LumberState = LumberState(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val index: LumberTreeIndex = LumberTreeIndex(tasks),
    private val masks: LumberBlockMask = LumberBlockMask(tasks, storage),
    private val cartClearance: LumberCartClearance = LumberCartClearance(),
) : WorksiteGuidanceSource {
    var state: LumberState = LumberEngine.recover(initial, initial.tree?.cartHome ?: initial.cart ?: rules.cart, clock())
        private set
    private var committed = state
    private var committedPhaseAt = clock()
    private var hasCommitted = false
    private var active = false
    private var pending = false
    private var planning = false
    private var retryIndexAt = 0L
    private var lastPersistWarning = Long.MIN_VALUE
    private var visualTick = 0L
    private val participantIds = linkedSetOf<UUID>()
    private val lastActions = mutableMapOf<UUID, Long>()
    private var cartHome = initial.tree?.cartHome ?: initial.cart ?: rules.cart
    private var cartMotion: WorksiteCartMotion.Frame? = null
    private var ownedBlocks = state.tree?.blocks.orEmpty().mapTo(hashSetOf()) { it.position }
    var indexStatus: LumberIndexStatus = LumberIndexStatus.PENDING
        private set
    fun indexReport() = index.report
    private val guidance = WorksiteGuidancePresenter(audience, access, this)

    fun activate() {
        active = serverId == "spawn"
        if (!active) return
        beginIndex()
        if (state != LumberState()) stage(state)
    }

    fun tick(now: Long) {
        if (!active) return
        synchronizeParticipants()
        if (participantIds.isEmpty() || pending || planning) return
        if (!index.ready && now >= retryIndexAt) beginIndex()
        if (committed.phase == LumberPhase.IDLE && index.ready && now >= retryIndexAt) beginRound()
    }

    fun updateVisuals() {
        if (!active) return
        val now = clock()
        synchronizeParticipants()
        if (!pending && participantIds.isNotEmpty()) {
            updateCargo(now)
            val branches = committed.tree?.let(LumberSceneGeometry::branchTargets).orEmpty().map(::position)
            val next = LumberEngine.tick(committed, effectiveNow(now), rules.fallTicks, branches)
            if (next != committed) stage(next)
        }
        val hidden = if (committed.phase !in setOf(LumberPhase.IDLE, LumberPhase.CHOPPING))
            committed.tree?.maskedBlocks.orEmpty().map { it.position } else emptyList()
        val refreshMask = ++visualTick % 20L == 0L
        participants().forEach { masks.apply(it, hidden, refreshMask) }
        presentation.render(LumberVisualView(committed, ageTicks(now), participantIds.toSet(),
            if (committed.sawRunning) ageTicks(now).coerceAtMost(LumberEngine.SAW_PASS_TICKS) else 0, now))
    }

    fun onChunkChanged() { if (active && indexStatus != LumberIndexStatus.READY) retryIndexAt = 0L }

    fun onBreakLowest(event: BlockBreakEvent): Boolean {
        if (!hasBeta(event.player)) return false
        if (!eligible(event.player)) return false
        if (ownsBlock(event.block)) { event.isCancelled = true; return true }
        val target = presentation.target(event.player) ?: return false
        if (!axeAction(target) || !hasAxe(event.player)) return false
        event.isCancelled = true
        return true
    }

    fun onBreakHigh(event: BlockBreakEvent): Boolean {
        if (!hasBeta(event.player)) return false
        if (!eligible(event.player)) return false
        val protected = ownsBlock(event.block)
        if (!handle(event.player, axeOnly = true) && !protected) return false
        event.isCancelled = true
        return true
    }

    fun onDamage(event: BlockDamageEvent): Boolean {
        if (!hasBeta(event.player)) return false
        if (!eligible(event.player)) return false
        val protected = ownsBlock(event.block)
        if (!handle(event.player, axeOnly = true) && !protected) return false
        event.isCancelled = true
        return true
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        if (!hasBeta(event.player)) return false
        if (event.hand != EquipmentSlot.HAND || event.action == Action.PHYSICAL) return false
        val left = event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK
        if (!handle(event.player, axeOnly = left)) return false
        event.isCancelled = true
        return true
    }

    fun onSwing(event: PlayerAnimationEvent): Boolean {
        if (!hasBeta(event.player)) return false
        return handle(event.player, axeOnly = true)
    }

    fun onMove(player: Player): Boolean {
        if (!hasBeta(player)) {
            if (player.uniqueId in participantIds || state.cartCarrier == player.uniqueId || state.boardsCarrier == player.uniqueId) releasePlayer(player)
            return false
        }
        if (!eligible(player)) { releasePlayer(player); return false }
        admit(player)
        return true
    }

    private fun handle(player: Player, axeOnly: Boolean): Boolean {
        if (!hasBeta(player) || !eligible(player)) return false
        val input = presentation.target(player) ?: return false
        if (axeOnly && !axeAction(input)) return false
        if (axeAction(input) && !hasAxe(player)) return false
        admit(player)
        if (pending) return true
        val now = clock()
        if (now - (lastActions[player.uniqueId] ?: Long.MIN_VALUE / 2) < rules.actionCooldownMillis) return true
        if (!withinActionReach(player, input)) return true
        lastActions[player.uniqueId] = now
        val next = LumberEngine.act(committed, input, player.uniqueId, effectiveNow(now), rules.chopHits)
        if (next != committed) stage(next)
        return true
    }

    private fun withinActionReach(player: Player, input: LumberInput): Boolean {
        if (input.action == LumberAction.LOAD_SAWMILL) return docked(committed.cart)
        val target = when (input.action) {
            LumberAction.DELIVER_BOARDS -> rules.warehouse
            else -> return true // Presentation already checked the exact current ray and planes.
        }
        return near(player.location, target, rules.deliveryRadius + 1.0)
    }

    private fun updateCargo(now: Long) {
        committed.cartCarrier?.let { id ->
            val player = Bukkit.getPlayer(id) ?: return@let
            val pose = committed.cart ?: cartHome
            val at = player.location
            if (at.world.name != pose.world || squared(at.x - pose.x, at.z - pose.z) > 100.0 || kotlin.math.abs(at.y - pose.y) > 3) {
                releasePlayer(player)
                return@let
            }
            val radians = Math.toRadians(at.yaw.toDouble())
            val frame = cartMotion ?: WorksiteCartMotion.Frame(pose.x, pose.y, pose.z, pose.yaw - 180f, 0f,
                at.x, at.z, -kotlin.math.sin(radians), kotlin.math.cos(radians))
            val moved = WorksiteCartMotion.advance(frame, at.x, at.y, at.z, at.yaw, LumberSceneGeometry.CART_FOLLOW_DISTANCE)
            val nextPose = LumberPose(rules.world, moved.x, moved.y, moved.z, moved.yaw + 180f)
            val accepted = cartClearance.resolve(pose, nextPose)
            val actual = accepted ?: pose
            // Consume this carrier input even when blocked; only accepted movement reaches the cart.
            cartMotion = moved.copy(x = actual.x, y = actual.y, z = actual.z, yaw = actual.yaw - 180f,
                wheelPhase = if (actual.x == pose.x && actual.z == pose.z) frame.wheelPhase else moved.wheelPhase)
            if (accepted == null) {
                if (access.allowInteraction("lumber-cart-blocked:$id", 1_500L)) audience.sendActionBar(player, MessageKey.LUMBER_CART_BLOCKED)
                return@let
            }
            committed = committed.copy(cart = accepted)
            state = committed
            if (docked(committed.cart))
                stage(LumberEngine.act(committed, LumberInput(LumberAction.LOAD_SAWMILL), id, effectiveNow(now), rules.chopHits))
        }
        committed.boardsCarrier?.let { id ->
            val player = Bukkit.getPlayer(id) ?: return@let
            if (!pending && now - committedPhaseAt >= LumberTiming.BOARD_PICKUP_TICKS * 50L && near(player.location, rules.warehouse, rules.deliveryRadius))
                stage(LumberEngine.act(committed, LumberInput(LumberAction.DELIVER_BOARDS), id, effectiveNow(now), rules.chopHits))
        }
    }

    private fun beginIndex() {
        val world = Bukkit.getWorld(rules.world) ?: return
        if (planning) return
        planning = true
        indexStatus = LumberIndexStatus.PENDING
        index.rebuild(world, rules) { result ->
            planning = false
            indexStatus = if (result.getOrDefault(0) > 0) LumberIndexStatus.READY
                else if (index.report.roots > 0) LumberIndexStatus.NO_SAFE_TREE else LumberIndexStatus.NO_LOADED_TREE
            retryIndexAt = if (indexStatus == LumberIndexStatus.READY) clock() else clock() + 10_000L
            if (indexStatus != LumberIndexStatus.READY && access.allowInteraction("lumber-index-empty", 60_000L))
                storage.log(Level.WARNING, "Lumber forest has no usable tree: world=${rules.world} status=$indexStatus report=${index.report}; retry=10s")
            result.exceptionOrNull()?.let { storage.log(Level.WARNING, "Lumber forest index failed: world=${rules.world}; retry=10s", it) }
        }
    }

    private fun beginRound() {
        val world = Bukkit.getWorld(rules.world) ?: return
        planning = true
        index.select(world, committed.roundId + 1) { result ->
            planning = false
            val tree = result.getOrNull()
            if (tree == null) {
                indexStatus = if (index.report.roots > 0) LumberIndexStatus.NO_SAFE_TREE else LumberIndexStatus.NO_LOADED_TREE
                if (access.allowInteraction("lumber-tree-unavailable", 60_000L)) storage.log(Level.WARNING,
                    "Lumber indexed trees failed current-world validation: world=${rules.world} report=${index.report}; retry=10s")
                index.clear()
                retryIndexAt = clock() + 10_000L
                result.exceptionOrNull()?.let { storage.log(Level.WARNING, "Lumber tree validation failed: world=${rules.world}; retry=10s", it) }
                participants().forEach { audience.sendActionBar(it, MessageKey.LUMBER_NO_TREE) }
                return@select
            }
            // Empty cart is local to the selected tree; it never relocates authored terrain.
            cartHome = requireNotNull(tree.cartHome) { "Indexed lumber tree has no validated cart footprint" }
            stage(LumberEngine.start(committed, tree, cartHome, clock()))
        }
    }

    private fun stage(next: LumberState) {
        if (next == state && pending) return
        if (next.tree !== state.tree) ownedBlocks = next.tree?.blocks.orEmpty().mapTo(hashSetOf()) { it.position }
        state = next
        if (pending) return
        pending = true
        persistStaged()
    }

    private fun persistStaged() {
        val expected = state
        val token = tasks.lifecycleToken()
        storage.persistAsync().whenComplete { _, failure -> tasks.runSync(token) {
            if (!active) return@runSync
            if (failure != null) {
                if (clock() - lastPersistWarning > 10_000 || lastPersistWarning == Long.MIN_VALUE) {
                    lastPersistWarning = clock()
                    storage.log(Level.WARNING, "Lumber progress awaits durable state: round=${state.roundId} phase=${state.phase}; retry=1s", failure)
                }
                tasks.runLater(token, 20L, ::persistStaged)
            } else if (state != expected) persistStaged()
            else {
                val completed = committed.completedRounds != state.completedRounds
                if (!hasCommitted || committed.phase != state.phase || committed.sawRunning != state.sawRunning || committed.sawPasses != state.sawPasses)
                    committedPhaseAt = clock()
                committed = state
                hasCommitted = true
                pending = false
                cartMotion = null
                participants().forEach { player ->
                    ensureTool(player)
                    if (completed) audience.showScreenTitle(player, MessageKey.LUMBER_COMPLETE)
                }
            }
        } }
    }

    private fun synchronizeParticipants() {
        participantIds.toList().forEach { id ->
            val player = Bukkit.getPlayer(id)
            if (player == null) participantIds.remove(id) else if (!eligible(player)) releasePlayer(player)
        }
        Bukkit.getWorld(rules.world)?.players?.filter(::eligible)?.forEach(::admit)
    }

    private fun admit(player: Player) {
        if (!eligible(player)) return
        if (participantIds.add(player.uniqueId)) ensureTool(player)
    }

    private fun ensureTool(player: Player) {
        if (!eligible(player)) return
        val expected = axeIdentity()
        serviceItems.identities(player).filter { it.activity == ActivityKind.LUMBER && it != expected }
            .forEach { while (serviceItems.consume(player, it)) Unit }
        if (committed.phase != LumberPhase.IDLE && !serviceItems.has(player, expected))
            serviceItems.issueTool(player, expected, Material.IRON_AXE, locale.render(MessageKey.LUMBER_AXE_NAME, player))
    }

    fun releasePlayer(player: Player) {
        val owned = participantIds.remove(player.uniqueId)
        if (!owned && state.cartCarrier != player.uniqueId && state.boardsCarrier != player.uniqueId) return
        val next = LumberEngine.release(state, player.uniqueId, cartHome)
        if (next != state) stage(next)
        serviceItems.identities(player).filter { it.activity == ActivityKind.LUMBER }
            .forEach { while (serviceItems.consume(player, it)) Unit }
        lastActions.remove(player.uniqueId)
        masks.release(player)
        if (owned) {
            presentation.release(player)
            guidance.releasePlayer(player)
            audience.removeSidebar(player, "lumber")
        }
    }

    fun releaseIdentity(playerId: UUID) {
        val next = LumberEngine.release(state, playerId, cartHome)
        if (next != state) stage(next)
    }

    fun isActive(identity: ServiceItemIdentity): Boolean = active && committed.phase != LumberPhase.IDLE && identity == axeIdentity()
    private fun axeIdentity() = ServiceItemIdentity(ActivityKind.LUMBER, LumberEngine.ZONE_ID,
        committed.roundId, committed.roundId, ObjectiveTargetRole("axe"), "lumber_axe")
    private fun hasAxe(player: Player) = serviceItems.identity(player.inventory.itemInMainHand) == axeIdentity()
    private fun axeAction(input: LumberInput) = input.action in setOf(LumberAction.CHOP_TREE, LumberAction.CUT_BRANCH)
    private fun hasBeta(player: Player) = player.hasPermission(LumbermillSettings.BETA_PERMISSION)
    private fun ownsBlock(block: org.bukkit.block.Block) = block.world.name == rules.world &&
        WorksitePosition(block.world.name, block.x, block.y, block.z) in ownedBlocks
    private fun eligible(player: Player): Boolean = active && player.isOnline && !player.isDead && hasBeta(player) &&
        player.world.name == rules.world && player.location.let { rules.worksite.contains(it.blockX, it.blockY, it.blockZ) }

    override fun participants(): Collection<Player> = participantIds.mapNotNull(Bukkit::getPlayer).filter(::eligible)

    override fun view(playerId: UUID): WorksiteGuidanceView? {
        val player = Bukkit.getPlayer(playerId)?.takeIf { it.uniqueId in participantIds && eligible(it) } ?: return null
        val (done, total) = when (committed.phase) {
            LumberPhase.CHOPPING -> committed.chopHits to rules.chopHits
            LumberPhase.DELIMBING -> (committed.branches?.completed ?: 0) to 3
            LumberPhase.SAWING -> committed.sawPasses to 2
            else -> 0 to 1
        }
        val phase = locale.render(PHASE_KEYS.getValue(committed.phase), player)
        val progress = locale.render(MessageKey.LUMBER_PROGRESS, player,
            mapOf("done" to locale.text(done), "total" to locale.text(total)))
        val cartHeld = committed.phase == LumberPhase.HAULING && committed.cartCarrier != null
        val hint = when {
            cartHeld && committed.cartCarrier == playerId -> MessageKey.LUMBER_HAULING_HELD_HINT
            cartHeld -> MessageKey.LUMBER_CART_BUSY
            else -> HINT_KEYS.getValue(committed.phase)
        }
        val target = when (committed.phase) {
            LumberPhase.CHOPPING -> committed.tree?.root?.let { LumberPose(it.world, it.x + .5, it.y + 1.0, it.z + .5) }
            LumberPhase.DELIMBING -> committed.tree?.let(LumberSceneGeometry::branchTargets)?.getOrNull(
                committed.branches?.targets?.indexOfFirst { it.status != ObjectiveTargetStatus.COMPLETED } ?: 0)
            LumberPhase.LOADING_CART -> committed.tree?.let(LumberSceneGeometry::felledStock)
            LumberPhase.HAULING -> if (committed.cartCarrier == playerId) LumberSceneGeometry.sawCartDock() else committed.cart
            LumberPhase.COLLECTING_BOARDS -> LumberSceneGeometry.outputPickup()
            LumberPhase.DELIVERING -> if (committed.boardsCarrier == playerId) rules.warehouse else null
            else -> null
        }
        val targets = target?.let { pose -> Bukkit.getWorld(pose.world)?.let { world ->
            listOf(WorksiteGuidanceTarget("next", ObjectiveTargetRole("next"),
                Location(world, pose.x, pose.y, pose.z), Color.fromRGB(255, 200, 87), bright = true))
        } }.orEmpty()
        return WorksiteGuidanceView("lumber:${LumberEngine.ZONE_ID}", committed.progressVersion,
            locale.render(MessageKey.LUMBER_TITLE, player), locale.render(hint, player),
            phase.append(net.kyori.adventure.text.Component.space()).append(progress), done.toFloat() / total,
            BossBar.Color.YELLOW, targets, listOf(phase, progress,
                locale.render(MessageKey.LUMBER_COMPLETED, player, mapOf("count" to locale.text(committed.completedRounds)))),
            quietProgress = cartHeld)
    }

    fun updateGuidance(expected: MutableSet<ActivityBarKey>) = guidance.updateHud(clock(), expected)
    fun emitGuidance() = guidance.emitParticles()

    fun cleanup(reason: String) {
        participantIds.mapNotNull(Bukkit::getPlayer).forEach(::releasePlayer)
        active = false
        pending = false
        planning = false
        index.clear()
        masks.cleanup()
        presentation.cleanup(reason)
        participantIds.clear()
        lastActions.clear()
        committed = state
    }

    private fun effectiveNow(now: Long) = committed.phaseStartedAt + (now - committedPhaseAt).coerceAtLeast(0)
    private fun ageTicks(now: Long) = ((now - committedPhaseAt).coerceAtLeast(0) / 50).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private fun position(pose: LumberPose) = WorksitePosition(pose.world, floor(pose.x).toInt(), floor(pose.y).toInt(), floor(pose.z).toInt())
    private fun near(at: Location, target: LumberPose, radius: Double) = at.world.name == target.world &&
        kotlin.math.abs(at.y - target.y) <= radius && squared(at.x - target.x, at.z - target.z) <= radius * radius
    private fun docked(cart: LumberPose?): Boolean {
        if (cart == null) return false
        val dock = LumberSceneGeometry.sawCartDock()
        var yaw = (cart.yaw - dock.yaw) % 360f
        if (yaw <= -180f) yaw += 360f
        if (yaw > 180f) yaw -= 360f
        return cart.world == dock.world && squared(cart.x - dock.x, cart.z - dock.z) <= .75 * .75 &&
            kotlin.math.abs(cart.y - dock.y) <= .25 && kotlin.math.abs(yaw) <= 12f
    }
    private fun squared(x: Double, z: Double) = x * x + z * z

    private companion object {
        val PHASE_KEYS = LumberPhase.entries.associateWith { MessageKey.valueOf("LUMBER_${it.name}") }
        val HINT_KEYS = LumberPhase.entries.associateWith { MessageKey.valueOf("LUMBER_${it.name}_HINT") }
    }
}

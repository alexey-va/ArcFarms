package ru.ruscrafting.farms.paper

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineRules
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.domain.MineShiftEvent
import ru.ruscrafting.farms.network.NetworkSignal
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteNetworkPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.random.RandomGenerator
import java.util.logging.Level
import kotlin.math.ceil

/** Owns the complete mine lifecycle, including crash-safe block replacement. */
internal class MineController(
    private val regionGateway: RegionGateway,
    private val locale: ArcFarmsLocale,
    private val journal: MineRecoveryJournal,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    private val stats: WorksiteStatsPort,
    private val network: WorksiteNetworkPort,
    private val clock: () -> Long,
    private val random: RandomGenerator,
    private val blockEffects: MineBlockEffects = PaperMineBlockEffects,
) : WorksiteModule<MineShiftState>, WorksiteBlockBreakHandler, WorksiteBlockDamageHandler, WorksiteBlockInteractHandler, WorksiteMoveHandler,
    WorksiteGuidanceHandler {
    override val kind: ActivityKind = ActivityKind.MINE
    private var runtimes: List<Runtime> = emptyList()
    private val reservations = ConcurrentHashMap.newKeySet<String>()
    private val pendingPositions = ConcurrentHashMap<String, String>()
    private val retiringRecords = ConcurrentHashMap.newKeySet<String>()

    override val zoneCount: Int get() = runtimes.size
    val pendingBlockCount: Int get() = pendingPositions.size

    fun rebuild(
        configured: List<MineZoneSettings>,
        persisted: Map<String, MineShiftState>,
        cooldownMillis: Long,
    ) {
        reservations.clear()
        pendingPositions.clear()
        retiringRecords.clear()
        journal.records().forEach { pendingPositions[it.positionKey] = it.id }
        runtimes = configured.map { settings -> runtime(settings, persisted[settings.id], cooldownMillis) }
    }

    fun reconfigure(
        configured: List<MineZoneSettings>,
        persisted: Map<String, MineShiftState>,
        cooldownMillis: Long,
    ) {
        val current = runtimes.associateBy { it.settings.id }
        require(current.keys == configured.mapTo(linkedSetOf(), MineZoneSettings::id)) {
            "Changing mine zone topology requires a full plugin restart"
        }
        runtimes = configured.map { settings ->
            val candidate = runtime(settings, persisted[settings.id], cooldownMillis)
            requireNotNull(current[settings.id]).apply {
                this.settings = candidate.settings
                region = candidate.region
                rules = candidate.rules
                temporaryMaterial = candidate.temporaryMaterial
                baseMaterial = candidate.baseMaterial
                materialWeights = candidate.materialWeights
                state = candidate.state
            }
        }
    }

    override fun beforeReload(reason: String) {
        reservations.clear()
        pendingPositions.clear()
        journal.records().forEach { pendingPositions[it.positionKey] = it.id }
    }

    override fun states(): Map<String, MineShiftState> = runtimes.associate { it.settings.id to it.state }

    override fun statuses(): List<ActivityStatus> = runtimes.map { runtime ->
        ActivityStatus(
            kind,
            runtime.settings.id,
            "phase.mine.${runtime.state.phase.name.lowercase()}",
            "${runtime.state.cart}/${runtime.rules.cartQuota}",
        )
    }

    override fun canAccess(player: Player): Boolean = runtimes.any { access.hasAccess(player, it.settings.permission) }

    override fun onBreakHigh(event: BlockBreakEvent): Boolean {
        val runtime = runtimeAt(event.block.location) ?: return false
        state.traceBlockBreak(event, kind, runtime.settings.id)
        breakBlock(event, runtime)
        return true
    }

    override fun onBlockDamage(event: BlockDamageEvent): Boolean {
        runtimeAt(event.block.location) ?: return false
        if (access.isAdminEditing(event.player)) return false
        event.instaBreak = true
        return true
    }

    override fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = runtimeAt(clicked.location) ?: return false
        if (
            runtime.state.phase != MinePhase.HAZARD || !player.isSneaking ||
            !MaterialRules.isPickaxe(player.inventory.itemInMainHand) || !clicked.type.isSolid
        ) return false
        event.isCancelled = true
        state.tracePlayerAction(player, kind, runtime.settings.id, "install_support", clicked.type)
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!access.allowInteraction("mine:${runtime.settings.id}:${player.uniqueId}", 750)) return true
        apply(runtime, MineShiftEngine.stabilize(runtime.state, runtime.rules, player.uniqueId, clock()), player)
        return true
    }

    override fun onMove(from: Location, to: Location, player: Player): Boolean {
        val runtime = runtimes.firstOrNull { candidate ->
            candidate.state.phase == MinePhase.EXTRACTION &&
                candidate.region.contains(from) && !candidate.region.contains(to)
        } ?: return false
        state.tracePlayerAction(player, kind, runtime.settings.id, "leave_for_extraction")
        apply(runtime, MineShiftEngine.extract(runtime.state, runtime.rules, player.uniqueId, clock()), player)
        return true
    }

    override fun tick(now: Long) {
        runtimes.forEach { runtime ->
            tasks.guarded("mine:${runtime.settings.id}") {
                val result = MineShiftEngine.tick(runtime.state, runtime.rules, now)
                if (result.events.isNotEmpty()) apply(runtime, result, null)
            }
        }
        tasks.guarded("mine_recovery") { restoreBlocks(now) }
    }

    override fun updateGuidance(expectedBars: MutableSet<ActivityBarKey>) {
        runtimes.forEach { runtime ->
            if (runtime.state.phase !in setOf(MinePhase.MINING, MinePhase.HAZARD, MinePhase.EXTRACTION)) return@forEach
            audience.players(runtime.region).filter { runtimeAt(it.location) === runtime }.forEach { player ->
                val values = mapOf(
                    "route" to locale.renderPath("route.mine.${runtime.settings.id}", player),
                    "done" to locale.text(runtime.state.cart),
                    "total" to locale.text(runtime.rules.cartQuota),
                    "phase" to locale.renderPath("phase.mine.${runtime.state.phase.name.lowercase()}", player),
                )
                val component = locale.render(MessageKey.MINE_ACTIONBAR, player, values)
                audience.sendActionBar(player, MessageKey.MINE_ACTIONBAR, values)
                val color = when (runtime.state.phase) {
                    MinePhase.HAZARD -> BossBar.Color.RED
                    MinePhase.EXTRACTION -> BossBar.Color.YELLOW
                    else -> BossBar.Color.BLUE
                }
                audience.updateSidebar(
                    player, "mine:${runtime.settings.id}", locale.renderPath("mine.guidance.title", player),
                    listOf(component),
                )
                audience.updateBar(
                    player,
                    "mine:${runtime.settings.id}",
                    component,
                    runtime.state.cart.toFloat() / runtime.rules.cartQuota,
                    color,
                    expectedBars,
                )
            }
        }
    }

    override fun emitGuidance() {
        runtimes.filter { it.state.phase == MinePhase.HAZARD }.forEach { runtime ->
            audience.players(runtime.region).filter { runtimeAt(it.location) === runtime }.forEach { player ->
                audience.spawnGuidanceDust(
                    player,
                    player.location.clone().add(0.0, 1.2, 0.0),
                    HAZARD_COLOR,
                )
            }
        }
    }

    private fun breakBlock(event: BlockBreakEvent, runtime: Runtime) {
        event.isCancelled = true
        event.isDropItems = false
        event.expToDrop = 0
        val player = event.player
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return
        }
        val toolSlot = player.inventory.heldItemSlot
        val toolSnapshot = player.inventory.getItem(toolSlot)?.clone()
        if (toolSnapshot == null || !MaterialRules.isPickaxe(toolSnapshot)) {
            audience.sendActionBar(player, MessageKey.MINE_PICKAXE_REQUIRED)
            return
        }
        val block = event.block
        if (block.type !in runtime.materialWeights) return
        when (runtime.state.phase) {
            MinePhase.HAZARD -> {
                audience.sendActionBar(player, MessageKey.MINE_HAZARD_HELP)
                return
            }
            MinePhase.EXTRACTION -> {
                audience.sendActionBar(player, MessageKey.MINE_EXTRACTION_REQUIRED)
                return
            }
            MinePhase.COOLDOWN -> {
                audience.sendActionBar(
                    player,
                    MessageKey.COOLDOWN,
                    mapOf("seconds" to locale.text(remainingSeconds(runtime.state.cooldownEndsAt, clock()))),
                )
                return
            }
            else -> Unit
        }
        val positionKey = positionKey(block.location)
        if (pendingPositions.containsKey(positionKey) || !reservations.add(positionKey)) {
            audience.sendActionBar(player, MessageKey.MINE_REGENERATING)
            return
        }
        val nextMaterial = MaterialRules.weightedMaterial(runtime.materialWeights, random)
        val record = PendingMineBlock(
            id = "${runtime.settings.id}:${UUID.randomUUID()}",
            zoneId = runtime.settings.id,
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            originalMaterial = block.type.name,
            temporaryMaterial = runtime.temporaryMaterial.name,
            nextMaterial = nextMaterial.name,
            restoreAt = clock() + runtime.settings.restoreSeconds * 1000L,
        )
        val originalMaterial = block.type
        val predictedBreakTicks = mineClientBreakTicks(block, player)
        val lifecycle = tasks.lifecycleToken()
        journal.prepare(record).whenComplete { _, failure ->
            if (!access.isOperational()) {
                reservations.remove(positionKey)
                if (failure == null) retireRecord(record, "stale")
                return@whenComplete
            }
            val accepted = tasks.runSync(lifecycle) {
                if (failure != null) {
                    reservations.remove(positionKey)
                    if (player.isOnline) audience.sendChat(player, MessageKey.MINE_JOURNAL_FAILED)
                    state.log(Level.SEVERE, "Could not journal mine block ${record.id}", failure)
                    return@runSync
                }
                if (!access.isOperational() || runtimes.none { it === runtime }) {
                    reservations.remove(positionKey)
                    retireRecord(record, "stale")
                    return@runSync
                }
                if (block.type != originalMaterial) {
                    reservations.remove(positionKey)
                    retireRecord(record, "stale")
                    return@runSync
                }
                block.setType(runtime.temporaryMaterial, false)
                pendingPositions[positionKey] = record.id
                reservations.remove(positionKey)
                val now = clock()
                tasks.guarded("mine_progress:${record.id}") {
                    if (runtime.state.phase == MinePhase.IDLE) {
                        apply(runtime, MineShiftEngine.start(runtime.state, runtime.rules, now), player)
                    }
                    val points = if (originalMaterial == runtime.baseMaterial) 1 else 2
                    apply(runtime, MineShiftEngine.mine(runtime.state, runtime.rules, points, player.uniqueId, now), player)
                }
                tasks.guarded("mine_tool_wear:${record.id}") {
                    blockEffects.completeExtraction(player, block, originalMaterial, toolSlot, toolSnapshot)
                    scheduleMineClientResync(tasks, state, player, block, record.id, predictedBreakTicks)
                }
            }
            if (!accepted) {
                reservations.remove(positionKey)
                if (failure == null) retireRecord(record, "stale")
            }
        }
    }

    private fun retireRecord(record: PendingMineBlock, reason: String) {
        if (!retiringRecords.add(record.id)) return
        journal.remove(record.id).whenComplete { _, failure ->
            retiringRecords.remove(record.id)
            if (failure == null) {
                pendingPositions.remove(record.positionKey, record.id)
            } else {
                state.log(Level.SEVERE, "Could not retire $reason mine journal record ${record.id}", failure)
            }
        }
    }

    private fun restoreBlocks(now: Long) {
        journal.records().filter { it.restoreAt <= now }.forEach { record ->
            val world = Bukkit.getWorld(record.world) ?: return@forEach
            if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return@forEach
            val block = world.getBlockAt(record.x, record.y, record.z)
            val temporary = runCatching { MaterialRules.material(record.temporaryMaterial) }.getOrNull()
            val next = runCatching { MaterialRules.material(record.nextMaterial) }.getOrNull()
            if (temporary == null || next == null) {
                if (access.allowInteraction("mine-journal-material:${record.id}", TimeUnit.MINUTES.toMillis(5))) {
                    state.log(Level.SEVERE, "Mine journal record ${record.id} contains an unknown material and was retained for recovery")
                }
                return@forEach
            }
            if (block.type == temporary) block.setType(next, false)
            retireRecord(record, "restored")
        }
    }

    private fun apply(runtime: Runtime, result: EngineResult<MineShiftState, MineShiftEvent>, actor: Player?) {
        runtime.state = result.state
        state.traceResult(
            kind,
            runtime.settings.id,
            actor,
            runtime.state.phase,
            "${runtime.state.cart}/${runtime.rules.cartQuota}",
            result,
        )
        if (actor != null && result.contribution > 0) {
            stats.recordContribution(actor.uniqueId, kind, result.contribution)
        }
        result.events.forEach { event ->
            when (event) {
                MineShiftEvent.STARTED -> audience.broadcast(
                    listOf(runtime.region),
                    MessageKey.MINE_STARTED,
                    sound = Sound.BLOCK_IRON_DOOR_OPEN,
                    valuesForPlayer = { player ->
                        mapOf("route" to locale.renderPath("route.mine.${runtime.settings.id}", player))
                    },
                )
                MineShiftEvent.HAZARD_STARTED -> {
                    audience.broadcast(
                        listOf(runtime.region),
                        MessageKey.MINE_HAZARD_STARTED,
                        sound = Sound.ENTITY_GENERIC_EXPLODE,
                        title = true,
                    )
                    audience.broadcast(listOf(runtime.region), MessageKey.MINE_HAZARD_HELP)
                    audience.warningBurst(runtime.region)
                    network.signal(NetworkSignal.MINE_HAZARD, kind, actor?.name, recipients(runtime))
                    state.persistAsync()
                }
                MineShiftEvent.HAZARD_RESOLVED -> {
                    audience.broadcast(
                        listOf(runtime.region),
                        MessageKey.MINE_HAZARD_RESOLVED,
                        sound = Sound.BLOCK_ANVIL_USE,
                        title = true,
                    )
                    audience.successBurst(runtime.region)
                    network.signal(NetworkSignal.MINE_STABLE, kind, actor?.name, recipients(runtime))
                }
                MineShiftEvent.EXTRACTION_STARTED -> {
                    audience.broadcast(
                        listOf(runtime.region),
                        MessageKey.MINE_EXTRACTION_STARTED,
                        sound = Sound.BLOCK_BELL_RESONATE,
                        title = true,
                    )
                    network.signal(NetworkSignal.MINE_EXTRACTION, kind, actor?.name, recipients(runtime))
                }
                MineShiftEvent.COMPLETED -> {
                    stats.recordCompletion(kind, runtime.state.contributors)
                    audience.broadcast(
                        listOf(runtime.region),
                        MessageKey.MINE_COMPLETED,
                        sound = Sound.UI_TOAST_CHALLENGE_COMPLETE,
                        title = true,
                    )
                    audience.announceWinner(listOf(runtime.region), runtime.state.contributors)
                    audience.celebration(listOf(runtime.region))
                    network.complete(kind, actor?.name, recipients(runtime))
                    state.persistAsync()
                }
                MineShiftEvent.PROGRESS -> if (runtime.state.phase == MinePhase.HAZARD && actor != null) {
                    audience.sendActionBar(
                        actor,
                        MessageKey.MINE_HAZARD_PROGRESS,
                        mapOf(
                            "done" to locale.text(runtime.state.supports),
                            "total" to locale.text(runtime.rules.supportsRequired),
                        ),
                    )
                }
                else -> Unit
            }
        }
    }

    private fun recipients(runtime: Runtime): Set<UUID> =
        audience.players(runtime.region).mapTo(mutableSetOf(), Player::getUniqueId)

    private fun runtimeAt(location: Location): Runtime? = runtimes.firstOrNull { it.region.contains(location) }

    private fun remainingSeconds(deadline: Long, now: Long): Long =
        ceil((deadline - now).coerceAtLeast(0) / 1000.0).toLong()

    private fun positionKey(location: Location): String =
        "${location.world.name}:${location.blockX}:${location.blockY}:${location.blockZ}"

    private fun runtime(settings: MineZoneSettings, state: MineShiftState?, cooldownMillis: Long): Runtime {
        val region = requireNotNull(regionGateway.resolve(settings.reference)) {
            "Mine zone ${settings.id} cannot resolve ${settings.reference}"
        }
        return Runtime(
            settings,
            region,
            MineRules(settings.cartQuota, settings.hazardTrigger, settings.supportsRequired, cooldownMillis),
            MaterialRules.material(settings.temporaryMaterial),
            MaterialRules.material(settings.baseMaterial),
            LinkedHashMap(settings.materialWeights.mapKeys { MaterialRules.material(it.key) }),
            state ?: MineShiftState(),
        )
    }

    private data class Runtime(
        var settings: MineZoneSettings,
        var region: ActivityRegion,
        var rules: MineRules,
        var temporaryMaterial: Material,
        var baseMaterial: Material,
        var materialWeights: LinkedHashMap<Material, Int>,
        var state: MineShiftState,
    )

    companion object {
        private val HAZARD_COLOR = Color.fromRGB(255, 95, 109)

        fun validateRuntime(configured: List<MineZoneSettings>, regionGateway: RegionGateway) {
            configured.forEach { zone ->
                requireNotNull(regionGateway.resolve(zone.reference)) { "Mine zone ${zone.id} cannot resolve ${zone.reference}" }
                MaterialRules.material(zone.temporaryMaterial)
                MaterialRules.material(zone.baseMaterial)
                zone.materialWeights.keys.forEach(MaterialRules::material)
            }
        }

        fun validateJournalMaterials(journal: MineRecoveryJournal) {
            journal.records().forEach { record ->
                MaterialRules.material(record.originalMaterial)
                MaterialRules.material(record.temporaryMaterial)
                MaterialRules.material(record.nextMaterial)
            }
        }

        fun validatePersisted(configured: List<MineZoneSettings>, persisted: Map<String, MineShiftState>) {
            val zones = configured.associateBy(MineZoneSettings::id)
            persisted.filterValues { it.phase !in setOf(MinePhase.IDLE, MinePhase.COOLDOWN) }.forEach { (id, state) ->
                val zone = requireNotNull(zones[id]) { "Persisted active mine zone $id is missing from config" }
                validateActiveOrder(zone, state, reload = false)
            }
        }

        fun validateReload(
            configured: List<MineZoneSettings>,
            persisted: Map<String, MineShiftState>,
            journal: MineRecoveryJournal,
        ) {
            val zones = configured.associateBy(MineZoneSettings::id)
            persisted.filterValues { it.phase !in setOf(MinePhase.IDLE, MinePhase.COOLDOWN) }.forEach { (id, state) ->
                val zone = requireNotNull(zones[id]) { "Cannot remove active mine $id during reload" }
                validateActiveOrder(zone, state, reload = true)
            }
            journal.records().forEach { record ->
                require(record.zoneId in zones) { "Cannot remove mine ${record.zoneId} with pending block recovery" }
            }
        }

        private fun validateActiveOrder(zone: MineZoneSettings, state: MineShiftState, reload: Boolean) {
            if (zone.engineVersion != 2 || state.engineVersion < 2) return
            val migrated = ru.ruscrafting.farms.paper.mine.MineRuntimeFactory.migrate(zone, state)
            if (migrated.phase in setOf(MinePhase.IDLE, MinePhase.COOLDOWN)) return
            require(zone.orders.any { it.id == migrated.orderId }) {
                if (reload) "Cannot remove active mine order ${zone.id}/${state.orderId} during reload"
                else "Persisted active mine order ${zone.id}/${state.orderId} is missing from config"
            }
        }
    }
}

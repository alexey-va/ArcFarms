package ru.ruscrafting.farms.paper.mine.mining

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockBreakEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineResource
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.MineBlockEffects
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineTransitionCoordinator
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.presentation.MineResourceText
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockMutation
import java.util.concurrent.CompletableFuture
import java.util.UUID
import java.util.random.RandomGenerator
import java.util.logging.Level

internal class MineMiningController(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val recovery: MineBlockRecoveryController,
    private val transitions: MineTransitionCoordinator,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val clock: () -> Long,
    private val random: RandomGenerator,
    private val effects: MineBlockEffects,
    private val startLoading: (ru.ruscrafting.farms.paper.mine.MineRuntime, ru.ruscrafting.farms.domain.MineShiftState) ->
        ru.ruscrafting.farms.domain.MineShiftState,
    private val locale: ArcFarmsLocale? = null,
) {
    private val lastDenialLog = mutableMapOf<String, Long>()
    private val suppressedDenials = mutableMapOf<String, Int>()

    private class Preparation(
        val runtime: MineRuntime,
        val record: PendingMineBlock,
        val original: Material,
        val sequence: Long,
        val orderId: String?,
        val targetId: String?,
        var touchedAt: Long,
        var ready: Boolean = false,
        var completion: CompletableFuture<Boolean>? = null,
        var acceptedEvent: BlockBreakEvent? = null,
        var invalidated: Boolean = false,
    )

    private data class Denial(val reason: String, val message: MessageKey)
    private val prepared = linkedMapOf<WorksitePosition, Preparation>()
    private val nearbyCursors = mutableMapOf<UUID, Int>()
    private var nextMiner = 0

    /** Prepare during the native damage animation, without changing its speed or the world. */
    fun onBlockDamage(event: BlockDamageEvent): Boolean {
        if (!prepareHit(event.player, event.block)) return false
        event.isCancelled = false
        return true
    }

    /** LEFT_CLICK also covers creative, where Paper skips BlockDamageEvent. */
    fun prepareHit(player: Player, block: Block): Boolean {
        val runtime = registry.at(block.location) ?: return false
        if (access.isAdminEditing(player) || rejection(runtime, player, block) != null) return false
        newPreparation(runtime, block)?.let { submit(listOf(it), listOf(block)) }
        return true
    }

    /** Uses the existing visual tick and loaded index: eight candidate reads total, one journal batch. */
    fun prewarm() {
        prune()
        val miners = registry.snapshot().filter { it.state.phase == MinePhase.MINING }.flatMap { runtime ->
            runtime.region.world.players.filter { player ->
                registry.forAudience(player.location) === runtime && access.hasAccess(player, runtime.settings.permission) &&
                    !access.isAdminEditing(player) && MaterialRules.isPickaxe(player.inventory.itemInMainHand)
            }.map { runtime to it }
        }
        if (miners.isEmpty()) return
        val (runtime, player) = miners[Math.floorMod(nextMiner++, miners.size)]
        val at = player.location
        val nearby = index.nearbyTargets(runtime.settings.id, MineAnchorRole.MINEABLE, at, PREWARM_RADIUS)
            .sortedBy { point ->
                (point.x + 0.5 - at.x) * (point.x + 0.5 - at.x) +
                    (point.y + 0.5 - at.y) * (point.y + 0.5 - at.y) +
                    (point.z + 0.5 - at.z) * (point.z + 0.5 - at.z)
            }
        if (nearby.isEmpty()) return
        val offset = Math.floorMod(nearbyCursors.getOrDefault(player.uniqueId, 0), nearby.size)
        val count = minOf(PREWARM_BLOCKS_PER_TICK, nearby.size)
        val additions = mutableListOf<Preparation>()
        val blocks = mutableListOf<Block>()
        repeat(count) { step ->
            val point = nearby[(offset + step) % nearby.size]
            val block = player.world.getBlockAt(point.x, point.y, point.z)
            if (rejection(runtime, player, block) == null) newPreparation(runtime, block)?.let {
                additions += it
                blocks += block
            }
        }
        nearbyCursors[player.uniqueId] = (offset + count) % nearby.size
        submit(additions, blocks)
    }

    /** Admit only an initially permitted event. Later handlers may cancel, never be overridden here. */
    fun onBreakLowest(event: BlockBreakEvent): Boolean {
        val runtime = registry.at(event.block.location) ?: return false
        if (access.isAdminEditing(event.player)) return false
        event.isDropItems = false
        event.expToDrop = 0
        if (event.isCancelled) return true
        if (rejection(runtime, event.player, event.block) != null) {
            event.isCancelled = true
            return true
        }
        val point = event.block.position()
        newPreparation(runtime, event.block)?.let { submit(listOf(it), listOf(event.block)) }
        val preparation = prepared[point]
        if (preparation == null || !preparation.ready || !current(preparation) ||
            preparation.original != event.block.type || preparation.acceptedEvent != null ||
            !recovery.admitPreparedMiningBreak(preparation.record)
        ) {
            event.isCancelled = true
            return true
        }
        preparation.acceptedEvent = event
        // Paper owns the actual AIR update, durability, mining statistics and native fracture effect.
        return true
    }

    fun onBreakHigh(event: BlockBreakEvent): Boolean {
        val runtime = registry.at(event.block.location) ?: return false
        if (access.isAdminEditing(event.player)) return false
        state.traceBlockBreak(event, ru.ruscrafting.farms.domain.ActivityKind.MINE, runtime.settings.id)
        event.isDropItems = false
        event.expToDrop = 0
        rejection(runtime, event.player, event.block)?.let { denied ->
            event.isCancelled = true
            return deny(event, runtime, denied.reason, denied.message)
        }
        val preparation = prepared[event.block.position()]
        if (preparation?.acceptedEvent !== event || !current(preparation, requireUnexpired = false) ||
            preparation.original != event.block.type || !recovery.isAdmittedMiningBreak(preparation.record)
        ) {
            event.isCancelled = true
            return deny(event, runtime, "journal_preparing", MessageKey.MINE_BREAK_PREPARING)
        }
        // In particular, preserve WorldGuard and every other handler's cancellation.
        return true
    }

    /** Observe only accepted native events; a later HIGHEST cancellation never receives credit. */
    fun onBreakMonitor(event: BlockBreakEvent): Boolean {
        if (event.isCancelled) return false
        val point = event.block.position()
        val preparation = prepared[point] ?: return false
        if (preparation.acceptedEvent !== event) return false
        if (!recovery.deferPreparedMiningRestore(preparation.record,
                clock() + preparation.runtime.settings.restoreSeconds * 1_000L)) return false
        prepared.remove(point)
        // Keep recovery even if another handler changed the order after our last validation.
        // The non-cancelled event will still reach Paper's removal, but must not over-credit a quota.
        if (!current(preparation, requireUnexpired = false) || preparation.original != event.block.type ||
            rejection(preparation.runtime, event.player, event.block) != null) return false
        val runtime = preparation.runtime
        val original = preparation.original
        val resource = MineResource.fromMaterial(original.name)
        if (runtime.settings.miningOnly) {
            val order = requireNotNull(runtime.currentOrder())
            val before = runtime.state.minedByMaterial[resource] ?: 0
            val requirement = order.normalizedRequirements[resource]
            transitions.apply(runtime, MineShiftEngine.mineTarget(
                runtime.state, runtime.rules(), event.player.uniqueId, original.name, order.normalizedRequirements,
            ), event.player)
            if (requirement != null && before < requirement &&
                (runtime.state.minedByMaterial[resource] ?: 0) >= requirement) {
                audience.showScreenTitle(event.player, MessageKey.MINE_RESOURCE_COMPLETED,
                    values = mapOf("resource" to MineResourceText.name(locale, resource, event.player)),
                    scope = "mine:resource-complete:$resource")
            }
        } else {
            val completed = ObjectiveTargetPool.complete(requireNotNull(runtime.state.objective),
                requireNotNull(preparation.targetId), event.player.uniqueId)
            if (!completed.accepted) return false
            val advanced = MineShiftEngine.mineTarget(
                runtime.state.copy(objective = completed.state), runtime.rules(), event.player.uniqueId,
            )
            val finalState = if (advanced.state.phase == MinePhase.LOADING) startLoading(runtime, advanced.state)
                else advanced.state.copy(objective = completed.state)
            transitions.apply(runtime, advanced.copy(state = finalState), event.player)
        }
        effects.completeNativeExtraction(event.player, event.block, original)
        state.log(Level.INFO, "Mine native break accepted player=${event.player.name} uuid=${event.player.uniqueId} " +
            "zone=${runtime.settings.id} sequence=${preparation.sequence} order=${preparation.orderId} " +
            "position=$point ore=$original resourceDisposition=order_accounting experienceDisposition=suppressed " +
            "progress=${runtime.state.mined}/${runtime.rules().miningQuota} record=${preparation.record.id}")
        return true
    }

    fun releasePlayer(player: Player) { nearbyCursors.remove(player.uniqueId) }

    fun clear() {
        val entries = prepared.values.iterator()
        while (entries.hasNext()) {
            val preparation = entries.next()
            preparation.invalidated = true
            // Pending storage continues to consume the shared cap across lifecycle/phase changes.
            if (pending(preparation)) continue
            resolveUnobservedAdmission(preparation)
            recovery.retireUnusedMiningPreparation(preparation.record)
            entries.remove()
        }
        nearbyCursors.clear()
        nextMiner = 0
    }

    private fun rejection(runtime: MineRuntime, player: Player, block: Block): Denial? = when {
        !access.hasAccess(player, runtime.settings.permission) -> Denial("missing_permission", MessageKey.ZONE_LOCKED)
        !access.isOperational() -> Denial("runtime_not_operational", MessageKey.MINE_ORDER_PAUSED)
        runtime.state.phase != MinePhase.MINING -> Denial("phase_not_mining",
            if (runtime.settings.miningOnly) MessageKey.MINE_ORDER_PAUSED else MessageKey.MINE_PROSPECT_REQUIRED)
        !MaterialRules.isPickaxe(player.inventory.itemInMainHand) -> Denial("pickaxe_required", MessageKey.MINE_PICKAXE_REQUIRED)
        !index.contains(runtime.settings.id, block, MineAnchorRole.MINEABLE) -> Denial("block_not_indexed",
            if (runtime.settings.miningOnly) MessageKey.MINE_MANAGED_REQUIRED else MessageKey.MINE_TARGET_REQUIRED)
        block.type !in runtime.mineableMaterials -> Denial("material_not_configured", MessageKey.MINE_MANAGED_REQUIRED)
        else -> {
            val resource = MineResource.fromMaterial(block.type.name)
            val order = runtime.currentOrder()
            when {
                runtime.settings.miningOnly && resource !in requireNotNull(order).requestedResources ->
                    Denial("wrong_order_material", MessageKey.MINE_MANAGED_REQUIRED)
                runtime.settings.miningOnly && order?.normalizedRequirements?.get(resource)?.let {
                    (runtime.state.minedByMaterial[resource] ?: 0) >= it
                } == true -> Denial("material_quota_complete", MessageKey.MINE_RESOURCE_COMPLETE)
                !runtime.settings.miningOnly && runtime.state.objective?.targets?.none {
                    it.position == block.position() && it.status == ObjectiveTargetStatus.AVAILABLE
                } != false -> Denial("target_unavailable", MessageKey.MINE_TARGET_REQUIRED)
                else -> null
            }
        }
    }

    private fun newPreparation(runtime: MineRuntime, block: Block): Preparation? {
        val point = block.position()
        val previous = prepared[point]
        if (previous != null) {
            if (pending(previous)) return null
            previous.acceptedEvent?.let { event ->
                if (!event.isCancelled) return null // Another synchronous event still owns this block.
                recovery.cancelPreparedMiningBreak(previous.record)
                previous.acceptedEvent = null
            }
            if (current(previous) && previous.original == block.type &&
                previous.ready && recovery.isPreparedMiningBreak(previous.record)) {
                previous.touchedAt = clock()
                return null
            }
            prepared.remove(point)
            recovery.retireUnusedMiningPreparation(previous.record)
        }
        if (prepared.size >= MAX_PREPARED_BLOCKS || recovery.containsPosition("${point.world}:${point.x}:${point.y}:${point.z}")) return null
        val original = block.type
        val next = if (runtime.currentOrder()?.normalizedRequirements?.isNotEmpty() == true) original
            else MaterialRules.weightedMaterial(LinkedHashMap(runtime.settings.materialWeights.mapKeys {
                MaterialRules.material(it.key)
            }), random)
        val record = PendingMineBlock(
            id = "mine:${runtime.settings.id}:${runtime.state.sequence}:${UUID.randomUUID()}",
            zoneId = runtime.settings.id, world = point.world, x = point.x, y = point.y, z = point.z,
            originalMaterial = original.name, temporaryMaterial = Material.AIR.name, nextMaterial = next.name,
            restoreAt = clock() + runtime.settings.restoreSeconds * 1_000L,
        )
        return Preparation(runtime, record, original, runtime.state.sequence, runtime.state.orderId,
            runtime.state.objective?.targets?.firstOrNull { it.position == point }?.id, clock())
            .also { prepared[point] = it }
    }

    private fun submit(additions: List<Preparation>, blocks: List<Block>) {
        if (additions.isEmpty()) return
        val completion = recovery.prepareAll(additions.mapIndexed { i, preparation ->
            MineBlockMutation(preparation.record, blocks[i], preparation.original,
                stillValid = { prepared[blocks[i].position()] === preparation && current(preparation) },
                mutation = { preparation.ready = true })
        })
        additions.forEach { it.completion = completion }
        completion.whenComplete { _, failure ->
            if (failure != null) state.log(Level.WARNING,
                "Mine native break preparation failed records=${additions.joinToString { it.record.id }}", failure)
        }
    }

    private fun current(preparation: Preparation, requireUnexpired: Boolean = true): Boolean {
        val runtime = preparation.runtime
        if (preparation.invalidated || registry.byId(runtime.settings.id) !== runtime || runtime.state.sequence != preparation.sequence ||
            runtime.state.orderId != preparation.orderId || runtime.state.phase != MinePhase.MINING ||
            requireUnexpired && clock() >= preparation.record.restoreAt) return false
        return runtime.settings.miningOnly ||
            runtime.state.objective?.target(requireNotNull(preparation.targetId))?.status == ObjectiveTargetStatus.AVAILABLE
    }

    private fun prune() {
        repeat(minOf(PRUNE_BLOCKS_PER_TICK, prepared.size)) {
            val entry = prepared.entries.first()
            val point = entry.key
            val preparation = entry.value
            prepared.remove(point)
            if (pending(preparation)) {
                prepared[point] = preparation
                return@repeat
            }
            // MONITOR skips cancelled events; the existing tick releases those reservations.
            // An unobserved non-cancelled event conservatively retains recovery without credit.
            if (resolveUnobservedAdmission(preparation)) return@repeat
            if (current(preparation) && clock() - preparation.touchedAt < UNUSED_PREPARATION_MILLIS &&
                preparation.ready && recovery.isPreparedMiningBreak(preparation.record)) prepared[point] = preparation
            else recovery.retireUnusedMiningPreparation(preparation.record)
        }
    }

    private fun pending(preparation: Preparation): Boolean = recovery.isWritingMiningPreparation(preparation.record) ||
        !preparation.ready && preparation.completion?.isDone != true

    private fun resolveUnobservedAdmission(preparation: Preparation): Boolean {
        val event = preparation.acceptedEvent ?: return false
        preparation.acceptedEvent = null
        if (event.isCancelled) {
            recovery.cancelPreparedMiningBreak(preparation.record)
            return false
        }
        recovery.deferPreparedMiningRestore(preparation.record,
            clock() + preparation.runtime.settings.restoreSeconds * 1_000L)
        return true
    }

    fun logRuntimeActivation(runtime: ru.ruscrafting.farms.paper.mine.MineRuntime) {
        val mineables = index.loadedTargets(runtime.settings.id, MineAnchorRole.MINEABLE).size
        val supports = index.loadedTargets(runtime.settings.id, MineAnchorRole.SUPPORT).size
        state.log(Level.INFO, "Mine runtime activated zone=${runtime.settings.id} world=${runtime.region.world.name} " +
            "region=${runtime.region.label} bounds=${runtime.region.bounds} miningOnly=${runtime.settings.miningOnly} " +
            "phase=${runtime.state.phase} sequence=${runtime.state.sequence} order=${runtime.state.orderId} " +
            "progress=${runtime.state.mined}/${runtime.rules().miningQuota} loadedMineables=$mineables loadedSupports=$supports " +
            "pendingRecovery=${recovery.records(runtime.settings.id).size}")
    }

    fun logPlacement(event: org.bukkit.event.block.BlockPlaceEvent, runtime: ru.ruscrafting.farms.paper.mine.MineRuntime, allowed: Boolean) {
        state.log(Level.INFO, "Mine block place ${if (allowed) "allowed" else "denied"} " +
            "reason=${if (allowed) "admin_edit" else "managed_zone"} player=${event.player.name} uuid=${event.player.uniqueId} " +
            "zone=${runtime.settings.id} phase=${runtime.state.phase} sequence=${runtime.state.sequence} " +
            "order=${runtime.state.orderId} block=${event.blockPlaced.type} position=${event.blockPlaced.position()}")
    }

    private fun deny(event: BlockBreakEvent, runtime: ru.ruscrafting.farms.paper.mine.MineRuntime, reason: String, key: MessageKey): Boolean {
        logDenied(event, runtime, reason)
        if (key == MessageKey.ZONE_LOCKED) audience.sendChat(event.player, key) else remind(event, key)
        return true
    }

    private fun logDenied(event: BlockBreakEvent, runtime: ru.ruscrafting.farms.paper.mine.MineRuntime, reason: String) {
        val logKey = "${event.player.uniqueId}:${runtime.settings.id}:$reason"
        val now = clock()
        val previous = lastDenialLog[logKey]
        if (previous != null && now - previous < DENIAL_LOG_INTERVAL_MILLIS) {
            suppressedDenials[logKey] = suppressedDenials.getOrDefault(logKey, 0) + 1
            return
        }
        lastDenialLog[logKey] = now
        val suppressed = suppressedDenials.remove(logKey) ?: 0
        val tool = event.player.inventory.itemInMainHand.type
        state.log(Level.INFO, "Mine break denied reason=$reason player=${event.player.name} uuid=${event.player.uniqueId} " +
            "zone=${runtime.settings.id} phase=${runtime.state.phase} sequence=${runtime.state.sequence} " +
            "order=${runtime.state.orderId} block=${event.block.type} position=${event.block.position()} tool=$tool " +
            "indexed=${index.contains(runtime.settings.id, event.block, MineAnchorRole.MINEABLE)} " +
            "progress=${runtime.state.mined}/${runtime.rules().miningQuota} suppressedSinceLast=$suppressed")
    }

    private fun remind(event: BlockBreakEvent, key: MessageKey) {
        audience.sendActionBar(event.player, key)
    }

    private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)

    private companion object {
        const val PREWARM_RADIUS = 6
        const val PREWARM_BLOCKS_PER_TICK = 8
        const val PRUNE_BLOCKS_PER_TICK = 16
        const val MAX_PREPARED_BLOCKS = 256
        const val UNUSED_PREPARATION_MILLIS = 5_000L
        const val DENIAL_LOG_INTERVAL_MILLIS = 2_000L
    }
}

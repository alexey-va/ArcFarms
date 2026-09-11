package ru.ruscrafting.farms.paper.mine.mining

import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.MinePhase
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
import ru.ruscrafting.farms.paper.mine.MineTransitionCoordinator
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
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
) {
    private val lastDenialLog = mutableMapOf<String, Long>()
    private val suppressedDenials = mutableMapOf<String, Int>()

    fun onBreakHigh(event: BlockBreakEvent): Boolean {
        val runtime = registry.at(event.block.location) ?: return false
        event.isCancelled = true
        state.traceBlockBreak(event, ru.ruscrafting.farms.domain.ActivityKind.MINE, runtime.settings.id)
        if (!access.hasAccess(event.player, runtime.settings.permission)) {
            logDenied(event, runtime, "missing_permission")
            audience.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (access.isAdminEditing(event.player)) {
            state.log(Level.INFO, "Mine break bypassed reason=admin_edit player=${event.player.name} " +
                "uuid=${event.player.uniqueId} zone=${runtime.settings.id} phase=${runtime.state.phase} " +
                "sequence=${runtime.state.sequence} block=${event.block.type} position=${event.block.position()}")
            event.isCancelled = false
            return false
        }
        if (runtime.state.phase != MinePhase.MINING) {
            return deny(event, runtime, "phase_not_mining",
                if (runtime.settings.miningOnly) MessageKey.MINE_ORDER_PAUSED else MessageKey.MINE_PROSPECT_REQUIRED)
        }
        val toolSlot = event.player.inventory.heldItemSlot
        val tool = event.player.inventory.getItem(toolSlot)?.clone()
        if (tool == null || !MaterialRules.isPickaxe(tool)) {
            return deny(event, runtime, "pickaxe_required", MessageKey.MINE_PICKAXE_REQUIRED)
        }
        if (!index.contains(runtime.settings.id, event.block, MineAnchorRole.MINEABLE)) {
            return deny(event, runtime, "block_not_indexed",
                if (runtime.settings.miningOnly) MessageKey.MINE_MANAGED_REQUIRED else MessageKey.MINE_TARGET_REQUIRED)
        }
        if (runtime.settings.miningOnly && event.block.type.name !in requireNotNull(runtime.currentOrder()).miningMaterials) {
            return deny(event, runtime, "wrong_order_material", MessageKey.MINE_MANAGED_REQUIRED)
        }
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == event.block.position() }
        if (!runtime.settings.miningOnly && (target == null || target.status != ObjectiveTargetStatus.AVAILABLE)) {
            return deny(event, runtime, "target_unavailable", MessageKey.MINE_TARGET_REQUIRED)
        }
        val original = event.block.type
        if (original.name !in runtime.settings.materialWeights) {
            return deny(event, runtime, "material_not_configured", MessageKey.MINE_MANAGED_REQUIRED)
        }
        val drops = runCatching { effects.captureDrops(event.block, tool, event.player) }.getOrElse { failure ->
            state.log(Level.WARNING, "Could not calculate mine drops for ${target?.id ?: event.block.position()}", failure)
            audience.sendChat(event.player, MessageKey.GENERIC_ERROR)
            return true
        }
        val next = MaterialRules.weightedMaterial(
            LinkedHashMap(runtime.settings.materialWeights.mapKeys { MaterialRules.material(it.key) }), random,
        )
        val record = PendingMineBlock(
            id = "${runtime.settings.id}:${runtime.state.sequence}:${UUID.randomUUID()}",
            zoneId = runtime.settings.id,
            world = event.block.world.name,
            x = event.block.x,
            y = event.block.y,
            z = event.block.z,
            originalMaterial = original.name,
            temporaryMaterial = runtime.settings.temporaryMaterial,
            nextMaterial = next.name,
            restoreAt = clock() + runtime.settings.restoreSeconds * 1_000L,
        )
        val sequence = runtime.state.sequence
        val orderId = runtime.state.orderId
        val minedBefore = runtime.state.mined
        val quota = runtime.rules().miningQuota
        val experience = event.expToDrop
        state.log(Level.INFO, "Mine break journal scheduled player=${event.player.name} uuid=${event.player.uniqueId} " +
            "zone=${runtime.settings.id} sequence=$sequence order=$orderId phase=${runtime.state.phase} " +
            "position=${event.block.position()} ore=$original temporary=${runtime.settings.temporaryMaterial} next=$next " +
            "progress=$minedBefore/$quota restoreAt=${record.restoreAt} record=${record.id}")
        recovery.prepare(
            record,
            event.block,
            original,
            stillValid = {
                runtime.state.sequence == sequence && runtime.state.phase == MinePhase.MINING &&
                    (runtime.settings.miningOnly ||
                        runtime.state.objective?.target(requireNotNull(target).id)?.status == ObjectiveTargetStatus.AVAILABLE)
            },
        ) {
            event.block.setType(MaterialRules.material(runtime.settings.temporaryMaterial), false)
            if (runtime.settings.miningOnly) {
                if (original.name in requireNotNull(runtime.currentOrder()).miningMaterials) {
                    transitions.apply(runtime, MineShiftEngine.mineTarget(runtime.state, runtime.rules(), event.player.uniqueId), event.player)
                }
                effects.deliverRewards(event.player, event.block, drops, experience, toolSlot, tool)
                return@prepare
            }
            val current = requireNotNull(runtime.state.objective)
            val completed = ObjectiveTargetPool.complete(current, requireNotNull(target).id, event.player.uniqueId)
            if (!completed.accepted) return@prepare
            val advanced = MineShiftEngine.mineTarget(
                runtime.state.copy(objective = completed.state), runtime.rules(), event.player.uniqueId,
            )
            val finalState = if (advanced.state.phase == MinePhase.LOADING) {
                startLoading(runtime, advanced.state)
            } else {
                advanced.state.copy(objective = completed.state)
            }
            transitions.apply(runtime, advanced.copy(state = finalState), event.player)
            effects.deliverRewards(event.player, event.block, drops, experience, toolSlot, tool)
        }.whenComplete { accepted, failure ->
            if (failure != null) {
                state.log(Level.WARNING, "Mine break journal failed player=${event.player.name} uuid=${event.player.uniqueId} " +
                    "zone=${runtime.settings.id} sequence=$sequence order=$orderId position=${event.block.position()} " +
                    "ore=$original record=${record.id}", failure)
                remind(event, MessageKey.MINE_JOURNAL_FAILED)
            } else if (accepted == false) {
                state.log(Level.INFO, "Mine break rejected reason=recovery_conflict player=${event.player.name} " +
                    "uuid=${event.player.uniqueId} zone=${runtime.settings.id} sequence=$sequence order=$orderId " +
                    "position=${event.block.position()} ore=$original record=${record.id}")
                remind(event, MessageKey.MINE_REGENERATING)
            } else {
                val dropSummary = drops.joinToString(",") { "${it.type}:${it.amount}" }.ifEmpty { "none" }
                state.log(Level.INFO, "Mine break completed player=${event.player.name} uuid=${event.player.uniqueId} " +
                    "zone=${runtime.settings.id} sequence=$sequence order=$orderId position=${event.block.position()} " +
                    "ore=$original temporary=${runtime.settings.temporaryMaterial} next=$next drops=$dropSummary xp=$experience " +
                    "progressBefore=$minedBefore/$quota progressAfter=${runtime.state.mined}/${runtime.rules().miningQuota} " +
                    "phaseAfter=${runtime.state.phase} record=${record.id}")
            }
        }
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
        remind(event, key)
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
        const val DENIAL_LOG_INTERVAL_MILLIS = 2_000L
    }
}

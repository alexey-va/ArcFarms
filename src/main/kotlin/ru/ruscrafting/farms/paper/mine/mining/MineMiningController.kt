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
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
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
    private val port: WorksiteRuntimePort,
    private val clock: () -> Long,
    private val random: RandomGenerator,
    private val effects: MineBlockEffects,
    private val startLoading: (ru.ruscrafting.farms.paper.mine.MineRuntime, ru.ruscrafting.farms.domain.MineShiftState) ->
        ru.ruscrafting.farms.domain.MineShiftState,
) {
    fun onBreakHigh(event: BlockBreakEvent): Boolean {
        val runtime = registry.at(event.block.location) ?: return false
        event.isCancelled = true
        port.traceBlockBreak(event, ru.ruscrafting.farms.domain.ActivityKind.MINE, runtime.settings.id)
        if (!port.hasAccess(event.player, runtime.settings.permission)) {
            port.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (port.isAdminEditing(event.player)) return false.also { event.isCancelled = false }
        if (runtime.state.phase != MinePhase.MINING) {
            remind(event, MessageKey.MINE_PROSPECT_REQUIRED)
            return true
        }
        val toolSlot = event.player.inventory.heldItemSlot
        val tool = event.player.inventory.getItem(toolSlot)?.clone()
        if (tool == null || !MaterialRules.isPickaxe(tool)) {
            remind(event, MessageKey.MINE_PICKAXE_REQUIRED)
            return true
        }
        if (!index.contains(runtime.settings.id, event.block, MineAnchorRole.MINEABLE)) {
            remind(event, MessageKey.MINE_TARGET_REQUIRED)
            return true
        }
        val objective = requireNotNull(runtime.state.objective)
        val target = objective.targets.firstOrNull { it.position == event.block.position() }
        if (target == null || target.status != ObjectiveTargetStatus.AVAILABLE) {
            remind(event, MessageKey.MINE_TARGET_REQUIRED)
            return true
        }
        val original = event.block.type
        if (original.name !in runtime.settings.materialWeights) return true
        val drops = runCatching { effects.captureDrops(event.block, tool, event.player) }.getOrElse { failure ->
            port.log(Level.WARNING, "Could not calculate mine drops for ${target.id}", failure)
            port.sendChat(event.player, MessageKey.GENERIC_ERROR)
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
        val experience = event.expToDrop
        recovery.prepare(
            record,
            event.block,
            original,
            stillValid = {
                runtime.state.sequence == sequence && runtime.state.phase == MinePhase.MINING &&
                    runtime.state.objective?.target(target.id)?.status == ObjectiveTargetStatus.AVAILABLE
            },
        ) {
            event.block.setType(MaterialRules.material(runtime.settings.temporaryMaterial), false)
            val current = requireNotNull(runtime.state.objective)
            val completed = ObjectiveTargetPool.complete(current, target.id, event.player.uniqueId)
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
                port.log(Level.WARNING, "Could not journal mine target ${target.id}", failure)
                remind(event, MessageKey.MINE_JOURNAL_FAILED)
            } else if (accepted == false) {
                remind(event, MessageKey.MINE_REGENERATING)
            }
        }
        return true
    }

    private fun remind(event: BlockBreakEvent, key: MessageKey) {
        port.sendActionBar(event.player, key)
        port.showScreenTitle(event.player, key, scope = "mine:${key.path}")
    }

    private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)
}

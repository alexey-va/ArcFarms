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
    fun onBreakHigh(event: BlockBreakEvent): Boolean {
        val runtime = registry.at(event.block.location) ?: return false
        event.isCancelled = true
        state.traceBlockBreak(event, ru.ruscrafting.farms.domain.ActivityKind.MINE, runtime.settings.id)
        if (!access.hasAccess(event.player, runtime.settings.permission)) {
            audience.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (access.isAdminEditing(event.player)) return false.also { event.isCancelled = false }
        if (runtime.state.phase != MinePhase.MINING) {
            remind(event, if (runtime.settings.miningOnly) MessageKey.MINE_ORDER_PAUSED else MessageKey.MINE_PROSPECT_REQUIRED)
            return true
        }
        val toolSlot = event.player.inventory.heldItemSlot
        val tool = event.player.inventory.getItem(toolSlot)?.clone()
        if (tool == null || !MaterialRules.isPickaxe(tool)) {
            remind(event, MessageKey.MINE_PICKAXE_REQUIRED)
            return true
        }
        if (!index.contains(runtime.settings.id, event.block, MineAnchorRole.MINEABLE)) {
            remind(event, if (runtime.settings.miningOnly) MessageKey.MINE_MANAGED_REQUIRED else MessageKey.MINE_TARGET_REQUIRED)
            return true
        }
        if (runtime.settings.miningOnly && event.block.type.name !in requireNotNull(runtime.currentOrder()).miningMaterials) {
            remind(event, MessageKey.MINE_MANAGED_REQUIRED)
            return true
        }
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == event.block.position() }
        if (!runtime.settings.miningOnly && (target == null || target.status != ObjectiveTargetStatus.AVAILABLE)) {
            remind(event, if (runtime.settings.miningOnly) MessageKey.MINE_MANAGED_REQUIRED else MessageKey.MINE_TARGET_REQUIRED)
            return true
        }
        val original = event.block.type
        if (original.name !in runtime.settings.materialWeights) return true
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
        val experience = event.expToDrop
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
                state.log(Level.WARNING, "Could not journal mine target ${target?.id ?: event.block.position()}", failure)
                remind(event, MessageKey.MINE_JOURNAL_FAILED)
            } else if (accepted == false) {
                remind(event, MessageKey.MINE_REGENERATING)
            }
        }
        return true
    }

    private fun remind(event: BlockBreakEvent, key: MessageKey) {
        audience.sendActionBar(event.player, key)
    }

    private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)
}

package ru.ruscrafting.farms.paper.mine.prospecting

import net.kyori.adventure.text.Component
import org.bukkit.Sound
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftEvent
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.MineTransitionCoordinator
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import kotlin.math.absoluteValue

internal class MineProspectingController(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val recovery: MineBlockRecoveryController,
    private val transitions: MineTransitionCoordinator,
    private val port: WorksiteRuntimePort,
    private val clock: () -> Long,
) {
    fun onInteract(event: PlayerInteractEvent): Boolean {
        val clicked = event.clickedBlock ?: return false
        val runtime = registry.at(clicked.location) ?: return false
        if (event.action != Action.RIGHT_CLICK_BLOCK) return false
        event.isCancelled = true
        if (!port.hasAccess(event.player, runtime.settings.permission)) {
            port.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!index.contains(runtime.settings.id, clicked, MineAnchorRole.PROSPECT)) {
            remind(event.player, MessageKey.MINE_PROSPECT_REQUIRED)
            return true
        }
        if (!ensureStarted(runtime, event.player)) return true
        if (runtime.state.phase != MinePhase.PROSPECTING) {
            remind(event.player, MessageKey.MINE_TARGET_REQUIRED)
            return true
        }
        val objective = requireNotNull(runtime.state.objective)
        val target = objective.targets.firstOrNull { it.position == clicked.position() }
        if (target == null || target.status != ObjectiveTargetStatus.AVAILABLE) {
            remind(event.player, MessageKey.MINE_PROSPECT_REQUIRED)
            return true
        }
        val completed = ObjectiveTargetPool.complete(objective, target.id, event.player.uniqueId)
        if (!completed.accepted) return true
        val advanced = MineShiftEngine.prospect(
            runtime.state.copy(objective = completed.state), runtime.rules(), event.player.uniqueId,
        )
        val finalState = if (advanced.state.phase == MinePhase.MINING) {
            advanced.state.copy(objective = plan(runtime, MineAnchorRole.MINEABLE, "mining", runtime.rules().miningQuota))
        } else {
            advanced.state.copy(objective = completed.state)
        }
        transitions.apply(runtime, advanced.copy(state = finalState), event.player)
        return true
    }

    private fun ensureStarted(runtime: MineRuntime, player: org.bukkit.entity.Player): Boolean {
        if (runtime.state.phase == MinePhase.COOLDOWN) return false.also { remind(player, MessageKey.COOLDOWN) }
        if (runtime.state.phase != MinePhase.IDLE) return true
        if (!recovery.canStart(runtime.settings.id)) return false.also { remind(player, MessageKey.MINE_RECOVERY_PENDING) }
        val order = runtime.nextOrder()
        val rules = runtime.rules(order)
        if (!hasCapacity(runtime, MineAnchorRole.PROSPECT, rules.prospectingQuota) ||
            !hasCapacity(runtime, MineAnchorRole.MINEABLE, rules.miningQuota)) {
            remind(player, MessageKey.MINE_INDEX_SHORTAGE)
            return false
        }
        val started = MineShiftEngine.start(runtime.state, order.domain(), rules, clock())
        val objective = plan(runtime, MineAnchorRole.PROSPECT, "prospecting", rules.prospectingQuota, started.state.sequence)
        transitions.apply(runtime, started.copy(state = started.state.copy(objective = objective)), player)
        port.broadcast(listOf(runtime.region), MessageKey.MINE_STARTED, sound = Sound.BLOCK_IRON_DOOR_OPEN, title = true)
        return true
    }

    private fun hasCapacity(runtime: MineRuntime, role: MineAnchorRole, quota: Int): Boolean =
        index.loadedTargets(runtime.settings.id, role).size >= quota * runtime.rules(runtime.nextOrder()).targetMultiplier

    private fun plan(
        runtime: MineRuntime,
        role: MineAnchorRole,
        objectiveId: String,
        required: Int,
        sequence: Long = runtime.state.sequence,
    ) = ObjectiveTargetPool.plan(
        WorksiteObjectiveKey(runtime.settings.id, objectiveId, sequence),
        required,
        candidates(runtime, role),
    )

    private fun candidates(runtime: MineRuntime, role: MineAnchorRole): List<ObjectiveTargetCandidate> =
        index.loadedTargets(runtime.settings.id, role).map { position ->
            ObjectiveTargetCandidate(
                "${role.name.lowercase()}_${token(position.x)}_${token(position.y)}_${token(position.z)}",
                position,
                ObjectiveTargetRole(role.name.lowercase()),
                score(runtime, position),
            )
        }

    private fun score(runtime: MineRuntime, position: WorksitePosition): Long {
        val center = runtime.region.bounds
        val dx2 = position.x.toLong() * 2L - center.minX - center.maxX
        val dy2 = position.y.toLong() * 2L - center.minY - center.maxY
        val dz2 = position.z.toLong() * 2L - center.minZ - center.maxZ
        return dx2 * dx2 + dy2 * dy2 + dz2 * dz2
    }

    private fun token(value: Int): String = if (value < 0) "m${value.toLong().absoluteValue}" else value.toString()

    private fun remind(player: org.bukkit.entity.Player, key: MessageKey, values: Map<String, Component> = emptyMap()) {
        port.sendActionBar(player, key, values)
        port.showScreenTitle(player, key, values, scope = "mine:${key.path}")
    }

    private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)
}

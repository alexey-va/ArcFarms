package ru.ruscrafting.farms.paper.mine.incident.sequence

import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import kotlin.math.absoluteValue

/** Ordered, non-resetting sequence. A wrong input never destroys accepted progress. */
internal abstract class MineSequenceIncident(
    private val type: MineIncidentType,
    private val anchorRole: MineAnchorRole,
    private val targetRole: String,
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
) {
    fun start(runtime: MineRuntime, required: Int, now: Long): Boolean {
        val candidates = candidates(runtime)
        if (candidates.size < required * runtime.rules().targetMultiplier) return false
        return incidents.start(runtime, type, required, now, candidates)
    }

    fun use(runtime: MineRuntime, targetId: String, player: org.bukkit.entity.Player, timingAccepted: Boolean = true): Boolean {
        val incident = runtime.state.incident ?: return false
        if (!active(runtime) || !timingAccepted) return false
        val objective = runtime.state.objective ?: return false
        val expected = objective.targets.getOrNull(incident.progress) ?: return false
        if (expected.id != targetId) return false
        return incidents.completeTarget(runtime, targetId, player).accepted
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return false
        val clicked = event.clickedBlock ?: return false
        val runtime = registry.at(clicked.location) ?: return false
        if (!active(runtime)) return false
        val position = WorksitePosition(clicked.world.name, clicked.x, clicked.y, clicked.z)
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == position } ?: return false
        event.isCancelled = true
        use(runtime, target.id, event.player)
        return true
    }

    fun nextTarget(runtime: MineRuntime): WorksitePosition? {
        val progress = runtime.state.incident?.takeIf { active(runtime) }?.progress ?: return null
        return runtime.state.objective?.targets?.getOrNull(progress)?.position
    }

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == type

    private fun candidates(runtime: MineRuntime): List<ObjectiveTargetCandidate> =
        index.loadedTargets(runtime.settings.id, anchorRole)
            .filter { index.isLiveTarget(runtime.settings.id, it, anchorRole, runtime.railMaterials) }
            .mapIndexed { order, position ->
                ObjectiveTargetCandidate(
                    "${targetRole}_${order + 1}_${token(position.x)}_${token(position.y)}_${token(position.z)}".take(48),
                    position,
                    ObjectiveTargetRole(targetRole),
                    order.toLong(),
                )
            }

    private fun token(value: Int): String = if (value < 0) "m${value.toLong().absoluteValue}" else value.toString()
}

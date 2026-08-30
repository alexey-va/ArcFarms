package ru.ruscrafting.farms.paper.lumber.incident.warped

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberBatchRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentCoordinator

internal class LumberWarpedBatchIncident(
    private val incidents: LumberIncidentCoordinator,
) {
    fun start(runtime: LumberRuntime, required: Int, now: Long): Boolean = incidents.start(
        runtime,
        LumberIncidentType.WARPED_BATCH,
        required,
        now,
        candidates(runtime, required * 4),
    )

    fun deliver(
        runtime: LumberRuntime,
        targetId: String,
        destination: LumberBatchRole,
        player: Player,
    ): Boolean {
        if (runtime.state.phase != LumberPhase.INCIDENT || runtime.state.incident?.type != LumberIncidentType.WARPED_BATCH) {
            return false
        }
        val target = runtime.state.objective?.target(targetId) ?: return false
        if (target.role.value != destination.objectiveRole) return false
        return incidents.completeTarget(runtime, targetId, player).accepted
    }

    private fun candidates(runtime: LumberRuntime, limit: Int): List<ObjectiveTargetCandidate> {
        val bounds = runtime.station.bounds
        val positions = mutableListOf<WorksitePosition>()
        scan@ for (y in bounds.minY..bounds.maxY) for (x in bounds.minX..bounds.maxX) for (z in bounds.minZ..bounds.maxZ) {
            val block = runtime.station.world.getBlockAt(x, y, z)
            if (block.isPassable && block.getRelative(0, 1, 0).isPassable && block.getRelative(0, -1, 0).type.isOccluding) {
                positions += WorksitePosition(runtime.station.world.name, x, y, z)
                if (positions.size >= limit) break@scan
            }
        }
        return positions.mapIndexed { index, position ->
            val role = if (index % 2 == 0) LumberBatchRole.ACCEPT else LumberBatchRole.REJECT
            ObjectiveTargetCandidate(
                "warped_${index + 1}",
                position,
                ObjectiveTargetRole(role.objectiveRole),
                index.toLong(),
            )
        }
    }
}

package ru.ruscrafting.farms.paper.lumber.incident.warped

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberBatchRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentCoordinator
import ru.ruscrafting.farms.paper.WorksiteRuntimePort

internal class LumberWarpedBatchIncident(
    private val registry: LumberRuntimeRegistry,
    private val incidents: LumberIncidentCoordinator,
    private val port: WorksiteRuntimePort,
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

    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = registry.snapshot().firstOrNull { it.station.contains(clicked.location) } ?: return false
        if (runtime.state.phase != LumberPhase.INCIDENT || runtime.state.incident?.type != LumberIncidentType.WARPED_BATCH) {
            return false
        }
        val position = WorksitePosition(clicked.world.name, clicked.x, clicked.y + 1, clicked.z)
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == position } ?: return false
        event.isCancelled = true
        val expected = if ((runtime.state.incident?.progress ?: 0) % 2 == 0) LumberBatchRole.ACCEPT else LumberBatchRole.REJECT
        if (!deliver(runtime, target.id, expected, player)) port.sendActionBar(player, MessageKey.LUMBER_TARGET_REQUIRED)
        return true
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

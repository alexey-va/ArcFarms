package ru.ruscrafting.farms.paper.lumber.incident.windthrow

import org.bukkit.event.block.BlockBreakEvent
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentCoordinator
import ru.ruscrafting.farms.paper.lumber.index.LumberBlockIndex
import ru.ruscrafting.farms.paper.lumber.recovery.LumberBlockRecoveryController
import java.util.logging.Level

internal class LumberWindthrowIncident(
    private val registry: LumberRuntimeRegistry,
    private val index: LumberBlockIndex,
    private val recovery: LumberBlockRecoveryController,
    private val incidents: LumberIncidentCoordinator,
    private val state: WorksiteStatePort,
) {
    fun start(runtime: LumberRuntime, required: Int, now: Long): Boolean = incidents.start(
        runtime,
        LumberIncidentType.WINDTHROW,
        required,
        now,
        candidates(runtime),
    )

    fun invalidate(runtime: LumberRuntime, targetId: String): Boolean = incidents.invalidate(runtime, targetId)

    fun complete(runtime: LumberRuntime, targetId: String, player: org.bukkit.entity.Player): Boolean =
        incidents.completeTarget(runtime, targetId, player).accepted

    fun onBreak(event: BlockBreakEvent): Boolean {
        val runtime = registry.at(event.block.location) ?: return false
        if (runtime.state.phase != LumberPhase.INCIDENT || runtime.state.incident?.type != LumberIncidentType.WINDTHROW) return false
        val target = runtime.state.objective?.targets?.firstOrNull { target ->
            target.position == WorksitePosition(event.block.world.name, event.block.x, event.block.y, event.block.z)
        } ?: return false
        event.isCancelled = true
        if (!event.player.inventory.itemInMainHand.type.name.endsWith("_AXE")) return true
        if (target.status != ObjectiveTargetStatus.AVAILABLE) return true
        recovery.prepare(
            runtime,
            event.player,
            event.block,
            event.player.inventory.itemInMainHand,
            stillValid = {
                runtime.state.phase == LumberPhase.INCIDENT && runtime.state.incident?.type == LumberIncidentType.WINDTHROW &&
                    runtime.state.objective?.target(target.id)?.status == ObjectiveTargetStatus.AVAILABLE
            },
            afterMutation = { complete(runtime, target.id, event.player) },
        ).whenComplete { _, failure ->
            if (failure != null) state.log(Level.WARNING, "Could not clear windthrow target ${target.id}", failure)
        }
        return true
    }

    private fun candidates(runtime: LumberRuntime): List<ObjectiveTargetCandidate> {
        val species = requireNotNull(runtime.state.species)
        return index.loadedLogs(runtime.settings.id, species).filter { position ->
            val world = org.bukkit.Bukkit.getWorld(position.world) ?: return@filter false
            world.isChunkLoaded(position.x shr 4, position.z shr 4) &&
                index.contains(runtime.settings.id, world.getBlockAt(position.x, position.y, position.z), species)
        }.mapIndexed { index, position ->
            ObjectiveTargetCandidate(
                "windthrow_${index + 1}",
                position,
                ObjectiveTargetRole("windthrow"),
                index.toLong(),
            )
        }
    }
}

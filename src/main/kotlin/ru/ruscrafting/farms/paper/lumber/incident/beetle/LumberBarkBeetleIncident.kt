package ru.ruscrafting.farms.paper.lumber.incident.beetle

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentCoordinator
import ru.ruscrafting.farms.paper.lumber.index.LumberBlockIndex

internal class LumberBarkBeetleIncident(
    private val registry: LumberRuntimeRegistry,
    private val index: LumberBlockIndex,
    private val incidents: LumberIncidentCoordinator,
) {
    fun start(runtime: LumberRuntime, required: Int, now: Long): Boolean = incidents.start(
        runtime,
        LumberIncidentType.BARK_BEETLES,
        required,
        now,
        candidates(runtime),
    )

    fun complete(runtime: LumberRuntime, targetId: String, player: Player): Boolean =
        incidents.completeTarget(runtime, targetId, player).accepted

    fun reconcile(runtime: LumberRuntime): Boolean {
        if (runtime.state.phase != LumberPhase.INCIDENT || runtime.state.incident?.type != LumberIncidentType.BARK_BEETLES) return false
        var changed = false
        runtime.state.objective?.targets.orEmpty().toList().forEach { target ->
            if (target.status == ObjectiveTargetStatus.COMPLETED || valid(runtime, target.position)) return@forEach
            val objective = runtime.state.objective ?: return@forEach
            val replacement = objective.reserve.firstOrNull { candidate ->
                valid(runtime, candidate.position) && objective.targets.none { it.position == candidate.position }
            }
            changed = incidents.invalidate(runtime, target.id, replacement) || changed
        }
        return changed
    }

    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = registry.at(clicked.location) ?: return false
        if (runtime.state.phase != LumberPhase.INCIDENT || runtime.state.incident?.type != LumberIncidentType.BARK_BEETLES) return false
        val position = WorksitePosition(clicked.world.name, clicked.x, clicked.y, clicked.z)
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == position } ?: return false
        event.isCancelled = true
        if (!player.inventory.itemInMainHand.type.name.endsWith("_AXE") || !valid(runtime, position)) return true
        complete(runtime, target.id, player)
        return true
    }

    private fun candidates(runtime: LumberRuntime): List<ObjectiveTargetCandidate> {
        val species = requireNotNull(runtime.state.species)
        return index.loadedLogs(runtime.settings.id, species).mapIndexed { index, position ->
            ObjectiveTargetCandidate(
                "beetle_${index + 1}",
                position,
                ObjectiveTargetRole("infected_log"),
                index.toLong(),
            )
        }
    }

    private fun valid(runtime: LumberRuntime, position: WorksitePosition): Boolean {
        val world = org.bukkit.Bukkit.getWorld(position.world) ?: return false
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return false
        val block = world.getBlockAt(position.x, position.y, position.z)
        val species = MaterialRules.speciesOf(block.type) ?: return false
        return index.contains(runtime.settings.id, block, species) && species == runtime.state.species
    }
}

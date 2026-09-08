package ru.ruscrafting.farms.paper.mine.incident.power

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
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
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import kotlin.math.absoluteValue

internal class MinePowerFailureIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val journal: MineIncidentBlockJournal,
) {
    fun start(runtime: MineRuntime, required: Int, now: Long): Boolean {
        val candidates = candidates(runtime)
        if (candidates.size < required * runtime.rules().targetMultiplier) return false
        if (!incidents.start(runtime, MineIncidentType.POWER_FAILURE, required, now, candidates)) return false
        reconcile(runtime)
        return true
    }

    fun relight(runtime: MineRuntime, targetId: String, player: Player): Boolean {
        val incident = runtime.state.incident ?: return false
        if (!active(runtime)) return false
        val target = runtime.state.objective?.targets?.getOrNull(incident.progress) ?: return false
        if (target.id != targetId) return false
        journal.restoreNow(target.position.lightPosition())
        val completed = incidents.completeTarget(runtime, targetId, player).accepted
        if (completed && !active(runtime)) journal.restore(runtime, INCIDENT_ID)
        return completed
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return false
        val clicked = event.clickedBlock ?: return false
        val runtime = registry.at(clicked.location) ?: return false
        if (!active(runtime)) return false
        val position = WorksitePosition(clicked.world.name, clicked.x, clicked.y, clicked.z)
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == position } ?: return false
        event.isCancelled = true
        relight(runtime, target.id, event.player)
        return true
    }

    fun reconcile(runtime: MineRuntime): Int {
        if (!active(runtime)) return 0
        val existing = journal.positions(runtime, INCIDENT_ID).toSet()
        var scheduled = 0
        runtime.state.objective?.targets.orEmpty().forEachIndexed { ordinal, target ->
            val position = target.position.lightPosition()
            if (position !in existing) {
                journal.prepare(runtime, INCIDENT_ID, ordinal, position, Material.LIGHT)
                scheduled++
            }
        }
        return scheduled
    }

    fun lightPositions(runtime: MineRuntime): List<WorksitePosition> = journal.positions(runtime, INCIDENT_ID)

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == MineIncidentType.POWER_FAILURE

    private fun candidates(runtime: MineRuntime): List<ObjectiveTargetCandidate> =
        index.loadedTargets(runtime.settings.id, MineAnchorRole.POWER)
            .filter {
                index.isLiveTarget(runtime.settings.id, it, MineAnchorRole.POWER, runtime.railMaterials) &&
                    it.lightPosition().blockType() == Material.AIR
            }
            .mapIndexed { order, position ->
                ObjectiveTargetCandidate(
                    "power_${order + 1}_${token(position.x)}_${token(position.z)}",
                    position, ObjectiveTargetRole("power_switch"), order.toLong(),
                )
            }

    private fun WorksitePosition.lightPosition() = copy(y = y + 1)
    private fun WorksitePosition.blockType(): Material? = Bukkit.getWorld(world)
        ?.takeIf { it.isChunkLoaded(x shr 4, z shr 4) }?.getBlockAt(x, y, z)?.type
    private fun token(value: Int): String = if (value < 0) "m${value.toLong().absoluteValue}" else value.toString()

    private companion object { const val INCIDENT_ID = "power_failure" }
}

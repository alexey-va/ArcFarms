package ru.ruscrafting.farms.paper.mine.incident.sequence

import org.bukkit.event.block.Action
import org.bukkit.Sound
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.blockType
import ru.ruscrafting.farms.paper.mine.incident.orderMineIncidentPositions
import ru.ruscrafting.farms.paper.mine.incident.isIncidentSurface
import ru.ruscrafting.farms.paper.mine.incident.entity.hasMineObjectiveMarkerSpace
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import kotlin.math.absoluteValue

/** Non-resetting multi-target interaction. Any remaining target may be activated. */
internal abstract class MineSequenceIncident(
    private val type: MineIncidentType,
    private val anchorRole: MineAnchorRole,
    private val targetRole: String,
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val requireStructuralSurface: Boolean = true,
    private val requireDirectClickSpace: Boolean = true,
    private val successSound: Sound,
    private val candidateStock: ru.ruscrafting.farms.paper.mine.incident.MineIncidentCandidateStock? = null,
) {
    fun start(runtime: MineRuntime, required: Int, now: Long): Boolean {
        val candidates = candidates(runtime, required)
        if (candidates.size < required * runtime.rules().targetMultiplier) return false
        return incidents.start(runtime, type, required, now, candidates)
    }

    fun use(runtime: MineRuntime, targetId: String, player: org.bukkit.entity.Player, timingAccepted: Boolean = true): Boolean {
        runtime.state.incident ?: return false
        if (!active(runtime) || !timingAccepted) return false
        val target = runtime.state.objective?.targets?.firstOrNull { it.id == targetId } ?: return false
        if (!actionable(target.position)) return false
        val result = incidents.completeTarget(runtime, targetId, player)
        if (result.accepted) player.playSound(player.location, successSound, 0.75f, 1.0f)
        return result.accepted
    }

    /** Entity-marker route; the parent router validates the PDC kind before calling this. */
    fun onInteractEntity(
        runtime: MineRuntime,
        targetId: String,
        player: org.bukkit.entity.Player,
        timingAccepted: Boolean = true,
    ): Boolean {
        if (!active(runtime)) return false
        val target = runtime.state.objective?.targets?.firstOrNull { it.id == targetId } ?: return false
        if (target.status == ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus.COMPLETED) return false
        use(runtime, targetId, player, timingAccepted)
        return true
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
        if (runtime.state.incident?.takeIf { active(runtime) } == null) return null
        return runtime.state.objective?.targets?.firstOrNull { it.status != ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus.COMPLETED }?.position
    }

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == type

    private fun candidates(runtime: MineRuntime, required: Int): List<ObjectiveTargetCandidate> =
        orderMineIncidentPositions(
            runtime,
            (candidateStock?.candidates(runtime, type) ?: index.loadedTargets(runtime.settings.id, anchorRole).take(32))
                .filter {
                        index.isLiveTarget(runtime.settings.id, it, anchorRole, runtime.railMaterials) &&
                        (!requireStructuralSurface || runtime.isIncidentSurface(it)) &&
                        (!requireDirectClickSpace || hasMineObjectiveMarkerSpace(it)) &&
                        (!isCrystalTarget() || it.blockType()?.name?.endsWith("AMETHYST_CLUSTER") == true)
                },
            required * runtime.rules().targetMultiplier * 2,
            type.ordinal.toLong() + 1L,
        )
            .mapIndexed { order, position ->
                ObjectiveTargetCandidate(
                    "${targetRole}_${order + 1}_${token(position.x)}_${token(position.y)}_${token(position.z)}".take(48),
                    position,
                    ObjectiveTargetRole(targetRole),
                    order.toLong(),
                )
            }

    private fun token(value: Int): String = if (value < 0) "m${value.toLong().absoluteValue}" else value.toString()

    private fun isCrystalTarget(): Boolean = anchorRole == MineAnchorRole.CRYSTAL

    private fun actionable(position: WorksitePosition): Boolean =
        !isCrystalTarget() || position.blockType() == org.bukkit.Material.AMETHYST_CLUSTER
}

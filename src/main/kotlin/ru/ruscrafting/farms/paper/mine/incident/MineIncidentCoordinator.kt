package ru.ruscrafting.farms.paper.mine.incident

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftEvent
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineTransitionCoordinator

/** Shared incident state transitions. Individual incidents still own all world semantics. */
internal class MineIncidentCoordinator(
    private val transitions: MineTransitionCoordinator,
    private val state: WorksiteStatePort,
) {
    fun start(
        runtime: MineRuntime,
        type: MineIncidentType,
        required: Int,
        now: Long,
        candidates: List<ObjectiveTargetCandidate> = emptyList(),
    ): Boolean {
        val started = MineShiftEngine.startIncident(runtime.state, type, required, now)
        if (!started.accepted) return false
        val state = if (candidates.isEmpty()) started.state else started.state.copy(
            objective = ObjectiveTargetPool.plan(
                WorksiteObjectiveKey(runtime.settings.id, "incident_${type.name.lowercase()}", started.state.sequence),
                required,
                candidates,
            ),
        )
        transitions.apply(runtime, started.copy(state = state), null)
        return true
    }

    fun completeTarget(runtime: MineRuntime, targetId: String, player: Player): EngineResult<MineShiftState, MineShiftEvent> {
        val objective = runtime.state.objective ?: return EngineResult(runtime.state, false)
        val completed = ObjectiveTargetPool.complete(objective, targetId, player.uniqueId)
        if (!completed.accepted) return EngineResult(runtime.state, false)
        return work(runtime, player, state = runtime.state.copy(objective = completed.state))
    }

    fun work(
        runtime: MineRuntime,
        player: Player,
        amount: Int = 1,
        state: MineShiftState = runtime.state,
    ): EngineResult<MineShiftState, MineShiftEvent> {
        val worked = MineShiftEngine.workIncident(state, player.uniqueId, amount)
        if (!worked.accepted) return worked
        val incident = worked.state.incident
        val result = if (incident != null && incident.progress >= incident.required) {
            val resolved = MineShiftEngine.resolveIncident(worked.state)
            EngineResult(
                resolved.state,
                accepted = resolved.accepted,
                contribution = worked.contribution,
                events = worked.events + resolved.events,
            )
        } else worked
        transitions.apply(runtime, result, player)
        return result
    }

    fun invalidate(runtime: MineRuntime, targetId: String): Boolean {
        val objective = runtime.state.objective ?: return false
        val replacement = objective.reserve.firstOrNull { candidate ->
            candidate.id != targetId && objective.targets.none { it.id == candidate.id || it.position == candidate.position }
        }
        val invalidated = ObjectiveTargetPool.invalidate(objective, targetId, replacement)
        if (!invalidated.accepted) return false
        runtime.state = runtime.state.copy(objective = invalidated.state)
        state.persistAsync()
        return true
    }
}

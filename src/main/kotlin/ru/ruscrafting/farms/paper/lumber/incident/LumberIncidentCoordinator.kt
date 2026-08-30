package ru.ruscrafting.farms.paper.lumber.incident

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.LumberShiftEvent
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberTransitionCoordinator

internal class LumberIncidentCoordinator(
    private val transitions: LumberTransitionCoordinator,
    private val port: WorksiteRuntimePort,
) {
    fun start(
        runtime: LumberRuntime,
        type: LumberIncidentType,
        required: Int,
        now: Long,
        candidates: List<ObjectiveTargetCandidate> = emptyList(),
        deadlineAt: Long = 0L,
    ): Boolean {
        val started = LumberShiftEngine.startIncident(runtime.state, type, required, now, deadlineAt)
        if (!started.accepted) return false
        val state = if (candidates.isEmpty()) {
            started.state
        } else {
            started.state.copy(
                objective = ObjectiveTargetPool.plan(
                    WorksiteObjectiveKey(runtime.settings.id, "incident_${type.name.lowercase()}", started.state.sequence),
                    required,
                    candidates,
                ),
            )
        }
        transitions.apply(runtime, started.copy(state = state), null)
        return true
    }

    fun completeTarget(runtime: LumberRuntime, targetId: String, player: Player): EngineResult<LumberShiftState, LumberShiftEvent> {
        val objective = runtime.state.objective ?: return EngineResult(runtime.state, false)
        val completed = ObjectiveTargetPool.complete(objective, targetId, player.uniqueId)
        if (!completed.accepted) return EngineResult(runtime.state, false)
        val worked = LumberShiftEngine.workIncident(
            runtime.state.copy(objective = completed.state),
            player.uniqueId,
        )
        val incident = worked.state.incident
        val result = if (incident != null && incident.progress >= incident.required) {
            val resolved = LumberShiftEngine.resolveIncident(worked.state)
            EngineResult(
                resolved.state,
                accepted = resolved.accepted,
                contribution = worked.contribution,
                events = worked.events + resolved.events,
            )
        } else {
            worked
        }
        transitions.apply(runtime, result, player)
        return result
    }

    fun invalidate(
        runtime: LumberRuntime,
        targetId: String,
        replacement: ObjectiveTargetCandidate? = null,
    ): Boolean {
        val objective = runtime.state.objective ?: return false
        val validReplacement = replacement ?: objective.reserve.firstOrNull { candidate ->
            candidate.id != targetId && objective.targets.none { it.id == candidate.id || it.position == candidate.position }
        }
        val result = ObjectiveTargetPool.invalidate(objective, targetId, validReplacement)
        if (!result.accepted) return false
        runtime.state = runtime.state.copy(objective = result.state)
        port.persistAsync()
        return true
    }
}

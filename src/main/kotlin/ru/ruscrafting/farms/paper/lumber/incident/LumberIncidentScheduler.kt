package ru.ruscrafting.farms.paper.lumber.incident

import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.incident.beetle.LumberBarkBeetleIncident
import ru.ruscrafting.farms.paper.lumber.incident.conveyor.LumberConveyorIncident
import ru.ruscrafting.farms.paper.lumber.incident.fire.LumberForestFireIncident
import ru.ruscrafting.farms.paper.lumber.incident.jam.LumberSawJamIncident
import ru.ruscrafting.farms.paper.lumber.incident.load.LumberLostLoadIncident
import ru.ruscrafting.farms.paper.lumber.incident.rush.LumberRushOrderIncident
import ru.ruscrafting.farms.paper.lumber.incident.warped.LumberWarpedBatchIncident
import ru.ruscrafting.farms.paper.lumber.incident.windthrow.LumberWindthrowIncident

/** Starts the persisted schedule at phase-compatible checkpoints without scanning or mutating while empty. */
internal class LumberIncidentScheduler(
    private val windthrow: LumberWindthrowIncident,
    private val beetles: LumberBarkBeetleIncident,
    private val sawJam: LumberSawJamIncident,
    private val conveyor: LumberConveyorIncident,
    private val fire: LumberForestFireIncident,
    private val lostLoad: LumberLostLoadIncident,
    private val warped: LumberWarpedBatchIncident,
    private val rush: LumberRushOrderIncident,
    private val state: WorksiteStatePort,
) {
    private val retryAfter = mutableMapOf<String, Long>()

    fun tick(runtime: LumberRuntime, now: Long, onlineParticipants: Int): Boolean {
        if (onlineParticipants <= 0 || runtime.state.phase == LumberPhase.INCIDENT) return false
        val type = runtime.state.incidentSchedule.getOrNull(runtime.state.incidentCursor) ?: return false
        if (!eligible(type, runtime.state.phase)) return false
        val retryKey = "${runtime.settings.id}:${runtime.state.sequence}:${runtime.state.incidentCursor}"
        if (now < (retryAfter[retryKey] ?: 0L)) return false
        val started = force(runtime, type, now)
        if (started) retryAfter.remove(retryKey) else retryAfter[retryKey] = now + RETRY_MILLIS
        return started
    }

    fun force(runtime: LumberRuntime, type: LumberIncidentType, now: Long): Boolean {
        if (runtime.state.phase == LumberPhase.INCIDENT || !eligible(type, runtime.state.phase)) return false
        return when (type) {
            LumberIncidentType.WINDTHROW -> windthrow.start(runtime, required(runtime, type), now)
            LumberIncidentType.BARK_BEETLES -> beetles.start(runtime, required(runtime, type), now)
            LumberIncidentType.SAW_JAM -> sawJam.start(runtime, required(runtime, type), now)
            LumberIncidentType.CONVEYOR_BREAKDOWN -> conveyor.start(runtime, required(runtime, type), now)
            LumberIncidentType.FOREST_FIRE -> fire.start(runtime, required(runtime, type), now)
            LumberIncidentType.LOST_LOAD -> lostLoad.start(runtime, required(runtime, type), now)
            LumberIncidentType.WARPED_BATCH -> warped.start(runtime, required(runtime, type), now)
            LumberIncidentType.RUSH_ORDER -> rush.start(runtime, now, RUSH_DURATION_MILLIS).also { accepted ->
                if (accepted) {
                    runtime.state = runtime.state.copy(incidentCursor = runtime.state.incidentCursor + 1)
                    state.persistAsync()
                }
            }
        }
    }

    fun cleanup() = retryAfter.clear()

    private fun eligible(type: LumberIncidentType, phase: LumberPhase): Boolean {
        val current = PHASE_RANK[phase] ?: return false
        val required = INCIDENT_RANK.getValue(type)
        return if (type == LumberIncidentType.RUSH_ORDER) phase == LumberPhase.STACKING else current >= required
    }

    private fun required(runtime: LumberRuntime, type: LumberIncidentType): Int = when (type) {
        LumberIncidentType.WINDTHROW -> minOf(2, runtime.rules().fellingQuota)
        LumberIncidentType.BARK_BEETLES -> minOf(3, runtime.rules().fellingQuota)
        LumberIncidentType.SAW_JAM -> minOf(3, runtime.settings.stationMaterials.size).coerceAtLeast(1)
        LumberIncidentType.CONVEYOR_BREAKDOWN -> 2
        LumberIncidentType.FOREST_FIRE -> 3
        LumberIncidentType.LOST_LOAD -> 2
        LumberIncidentType.WARPED_BATCH -> 3
        LumberIncidentType.RUSH_ORDER -> 1
    }

    private companion object {
        const val RETRY_MILLIS = 5_000L
        const val RUSH_DURATION_MILLIS = 75_000L
        val PHASE_RANK = mapOf(
            LumberPhase.FELLING to 0,
            LumberPhase.SKIDDING to 1,
            LumberPhase.SAWING to 2,
            LumberPhase.STACKING to 3,
            LumberPhase.DISPATCH to 4,
        )
        val INCIDENT_RANK = mapOf(
            LumberIncidentType.WINDTHROW to 0,
            LumberIncidentType.BARK_BEETLES to 0,
            LumberIncidentType.FOREST_FIRE to 0,
            LumberIncidentType.LOST_LOAD to 1,
            LumberIncidentType.SAW_JAM to 2,
            LumberIncidentType.CONVEYOR_BREAKDOWN to 2,
            LumberIncidentType.WARPED_BATCH to 3,
            LumberIncidentType.RUSH_ORDER to 3,
        )
    }
}

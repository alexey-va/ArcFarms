package ru.ruscrafting.farms.domain

import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Progress is active survival time, never wall time spent offline or without participants. */
object FarmTornadoEngine {
    fun initialize(current: FarmShiftState, anchors: List<FarmPointPosition>, seconds: Int): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(anchors.size in 1..32 && seconds in 10..180)
        if (!active(current) || current.specialIncident != null) return EngineResult(current, false)
        return EngineResult(current.copy(
            specialIncident = FarmSpecialIncidentState(points = anchors),
            incidentRequired = seconds,
            incidentProgress = 0,
        ), true)
    }

    fun second(current: FarmShiftState, participants: Set<UUID>): EngineResult<FarmShiftState, FarmShiftEvent> {
        if (!active(current) || current.specialIncident == null || participants.isEmpty()) return EngineResult(current, false)
        val next = current.copy(incidentProgress = current.incidentProgress + 1)
        if (next.incidentProgress < next.incidentRequired) return EngineResult(next, true)
        val credited = current.contributors.toMutableMap()
        participants.forEach { credited[it] = (credited[it] ?: 0) + 1 }
        return FarmShiftEngine.completeIncident(next.copy(contributors = credited), contribution = 0)
            .copy(contributionCredits = participants.associateWith { 1 })
    }

    fun active(state: FarmShiftState): Boolean = state.phase == FarmPhase.INCIDENT && state.incidentType == FarmIncidentType.TORNADO
}

/** Shared, deterministic geometry for the particle ropes and tumbling block orbits. */
object FarmTornadoShape {
    fun funnel(fraction: Double, angle: Double, time: Double, height: Double, radius: Double): FarmMotionVector {
        val ring = 0.9 + (radius - 0.9) * fraction * fraction
        val lean = fraction * fraction * 2.0
        return FarmMotionVector(
            cos(angle) * ring + sin(time * 0.35) * lean,
            fraction * height,
            sin(angle) * ring + cos(time * 0.29) * lean,
        )
    }

    fun debris(index: Int, count: Int, time: Double, height: Double, radius: Double): FarmMotionVector {
        val life = (index.toDouble() / count + time * 0.085) % 1.0
        val rise = (life / 0.8).coerceAtMost(1.0)
        val angle = index * 2.399963229728653 + time * 3.6 + rise * PI * 2
        val orbit = funnel(rise * 0.85, angle, time, height, radius)
        val eject = ((life - 0.8) / 0.2).coerceAtLeast(0.0)
        return FarmMotionVector(
            orbit.x + cos(angle) * eject * 5.0,
            orbit.y - eject * eject * height * 0.4,
            orbit.z + sin(angle) * eject * 5.0,
        )
    }
}

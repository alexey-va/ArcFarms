package ru.ruscrafting.farms.domain

import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A timed hazard alongside harvesting; its clock pauses while the farm has no participants. */
object FarmTornadoEngine {
    fun start(current: FarmShiftState, seconds: Int = 45): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(seconds in 10..180)
        if (current.phase != FarmPhase.HARVESTING || current.tornado != null) return EngineResult(current, false)
        return EngineResult(current.copy(
            tornado = FarmTornadoState(durationSeconds = seconds),
            incidentsResolved = (current.incidentsResolved + 1).coerceAtMost(MAX_FARM_INCIDENTS),
            placementSequence = current.nextPlacementSequence(),
        ), true,
            events = listOf(FarmShiftEvent.TORNADO_STARTED))
    }

    fun initialize(current: FarmShiftState, anchors: List<FarmPointPosition>, seconds: Int): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(anchors.size in 1..32 && seconds in 10..180)
        val existing = current.tornado ?: return EngineResult(current, false)
        if (existing.points.isNotEmpty() || current.phase != FarmPhase.HARVESTING || current.specialIncident != null) return EngineResult(current, false)
        return EngineResult(current.copy(
            tornado = existing.copy(points = anchors, durationSeconds = seconds, elapsedSeconds = existing.elapsedSeconds.coerceAtMost(seconds - 1)),
        ), true)
    }

    fun second(current: FarmShiftState, participants: Set<UUID>): EngineResult<FarmShiftState, FarmShiftEvent> {
        val tornado = current.tornado ?: return EngineResult(current, false)
        if (!active(current) || participants.isEmpty()) return EngineResult(current, false)
        val elapsed = tornado.elapsedSeconds + 1
        if (elapsed < tornado.durationSeconds) return EngineResult(current.copy(tornado = tornado.copy(elapsedSeconds = elapsed)), true)
        return EngineResult(current.copy(tornado = null), true, events = listOf(FarmShiftEvent.TORNADO_RESOLVED))
    }

    fun active(state: FarmShiftState): Boolean = state.phase == FarmPhase.HARVESTING && state.tornado != null
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

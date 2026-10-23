package ru.ruscrafting.farms.domain

import kotlin.math.abs
import kotlin.math.hypot

/** Input filters for the two fixed floor jobs; committed steps use MineIncidentState.progress. */
object MineFloorActivity {
    const val WINCH_STEPS = 4
    const val PUMP_STEPS = 6
    const val PULL_DISTANCE = 2.8
    const val PULL_X = -1.65
    const val PULL_START_Z = 1.65
    const val PUMP_STROKES = 4

    data class Pull(val x: Double, val z: Double, val distance: Double = 0.0)
    data class Pump(val downAt: Long? = null, val nextStrokeAt: Long = 0L)
    data class Stroke(val state: Pump, val accepted: Boolean)

    /** A real, bounded walk in the pull lane; side-steps and position jumps do not raise cargo. */
    fun pull(previous: Pull, x: Double, z: Double): Pull? {
        if (!x.isFinite() || !z.isFinite() || abs(x - PULL_X) > .7 ||
            z !in (PULL_START_Z - .4)..(PULL_START_Z + PULL_DISTANCE + .55) ||
            hypot(x - previous.x, z - previous.z) > .85) return null
        val distance = maxOf(previous.distance, (z - PULL_START_Z).coerceIn(0.0, PULL_DISTANCE))
        return Pull(x, z, distance)
    }

    /** One full down/up stroke. Holding Shift, rapid toggles and returning after a long absence do not count. */
    fun pump(previous: Pump, sneaking: Boolean, now: Long): Stroke = when {
        sneaking && previous.downAt == null -> Stroke(previous.copy(downAt = now), false)
        !sneaking && previous.downAt != null -> {
            val accepted = now >= previous.nextStrokeAt && now - previous.downAt in 350L..1_800L
            Stroke(Pump(nextStrokeAt = if (accepted) now + 450L else previous.nextStrokeAt), accepted)
        }
        else -> Stroke(previous, false)
    }
}

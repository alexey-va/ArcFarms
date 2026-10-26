package ru.ruscrafting.farms.domain.worksite

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sign

data class WorksiteCrankState(
    val x: Double,
    val z: Double,
    val angle: Double,
    val direction: Int = 0,
)

enum class WorksiteCrankSampleStatus {
    ENTERED,
    MOVED,
    REVERSED,
    STATIONARY,
    OUTSIDE,
    TELEPORTED,
}

data class WorksiteCrankSample(
    val state: WorksiteCrankState?,
    val acceptedRadians: Double,
    val status: WorksiteCrankSampleStatus,
)

/**
 * Pure movement filter for a worksite walking ring.
 *
 * Direction changes never remove progress, but the reversing sample is ignored so
 * walking back and forth in one spot cannot manufacture completed laps.
 */
object WorksiteCrankTracker {
    const val FULL_LAP_RADIANS: Double = PI * 2.0

    fun sample(
        previous: WorksiteCrankState?,
        x: Double,
        z: Double,
        centerX: Double,
        centerZ: Double,
        innerRadius: Double,
        outerRadius: Double,
        radiusTolerance: Double = 0.0,
        maxStepDistance: Double,
    ): WorksiteCrankSample {
        val centerDistance = hypot(x - centerX, z - centerZ)
        val acceptedInnerRadius = (innerRadius - radiusTolerance).coerceAtLeast(MIN_CENTER_DISTANCE)
        val acceptedOuterRadius = outerRadius + radiusTolerance
        if (centerDistance !in acceptedInnerRadius..acceptedOuterRadius) {
            return WorksiteCrankSample(null, 0.0, WorksiteCrankSampleStatus.OUTSIDE)
        }
        val angle = atan2(z - centerZ, x - centerX)
        if (previous == null) {
            return WorksiteCrankSample(
                WorksiteCrankState(x, z, angle),
                0.0,
                WorksiteCrankSampleStatus.ENTERED,
            )
        }
        if (hypot(x - previous.x, z - previous.z) > maxStepDistance) {
            return WorksiteCrankSample(
                WorksiteCrankState(x, z, angle),
                0.0,
                WorksiteCrankSampleStatus.TELEPORTED,
            )
        }
        val delta = normalize(angle - previous.angle)
        if (abs(delta) < MIN_ANGULAR_STEP) {
            return WorksiteCrankSample(
                previous.copy(x = x, z = z, angle = angle),
                0.0,
                WorksiteCrankSampleStatus.STATIONARY,
            )
        }
        val direction = sign(delta).toInt()
        if (previous.direction != 0 && previous.direction != direction) {
            return WorksiteCrankSample(
                WorksiteCrankState(x, z, angle, direction),
                0.0,
                WorksiteCrankSampleStatus.REVERSED,
            )
        }
        return WorksiteCrankSample(
            WorksiteCrankState(x, z, angle, direction),
            abs(delta),
            WorksiteCrankSampleStatus.MOVED,
        )
    }

    private fun normalize(angle: Double): Double {
        var normalized = angle
        while (normalized > PI) normalized -= FULL_LAP_RADIANS
        while (normalized < -PI) normalized += FULL_LAP_RADIANS
        return normalized
    }

    private const val MIN_ANGULAR_STEP = 0.004
    private const val MIN_CENTER_DISTANCE = 0.5
}

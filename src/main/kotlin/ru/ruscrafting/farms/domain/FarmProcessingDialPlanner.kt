package ru.ruscrafting.farms.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class FarmProcessingDialPoint(
    val position: FarmPointPosition,
    val inSuccessWindow: Boolean,
)

data class FarmProcessingDialPlan(
    val center: FarmPointPosition,
    val ring: List<FarmProcessingDialPoint>,
    val marker: FarmProcessingDialPoint,
)

/** Pure geometry for the vertical timing dial rendered in front of the processing machine. */
object FarmProcessingDialPlanner {
    fun plan(
        machine: FarmPointPosition,
        phase: Int,
        periodTicks: Int,
        successWindowTicks: Int,
        centerYOffset: Double,
        rightOffset: Double,
        forwardOffset: Double,
        radius: Double,
        pointCount: Int,
    ): FarmProcessingDialPlan {
        require(phase in 0 until periodTicks) { "Processing dial phase is outside its period" }
        require(successWindowTicks in 1 until periodTicks / 2) { "Processing dial success window is invalid" }
        require(centerYOffset.isFinite() && rightOffset.isFinite() && forwardOffset.isFinite()) {
            "Processing dial offset must be finite"
        }
        require(radius.isFinite() && radius > 0.0) { "Processing dial radius must be positive" }
        require(pointCount in 12..64) { "Processing dial point count must be in 12..64" }
        val center = FarmProcessingLayout.offset(machine, rightOffset, forwardOffset, centerYOffset)
        val successCenter = periodTicks / 2
        fun point(pointPhase: Int): FarmProcessingDialPoint {
            val normalized = Math.floorMod(pointPhase, periodTicks)
            val angle = normalized.toDouble() / periodTicks * PI * 2.0 - PI / 2.0
            return FarmProcessingDialPoint(
                position = FarmProcessingLayout.offset(center, cos(angle) * radius, 0.0, sin(angle) * radius),
                inSuccessWindow = abs(normalized - successCenter) <= successWindowTicks / 2,
            )
        }
        return FarmProcessingDialPlan(
            center = center,
            ring = List(pointCount) { index ->
                point((index.toDouble() * periodTicks / pointCount).roundToInt())
            },
            marker = point(phase),
        )
    }
}

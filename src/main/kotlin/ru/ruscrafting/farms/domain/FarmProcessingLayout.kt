package ru.ruscrafting.farms.domain

import kotlin.math.cos
import kotlin.math.sin

data class FarmProcessingLayout(
    val anchor: FarmPointPosition,
    val machine: FarmPointPosition,
    val wheel: FarmPointPosition,
    val inputRack: FarmPointPosition,
    val inputDrop: FarmPointPosition,
    val outputChute: FarmPointPosition,
    val outputPallet: FarmPointPosition,
    val labels: List<FarmPointPosition>,
    val footprint: List<FarmPointPosition>,
) {
    companion object {
        const val WIDTH = 9
        const val DEPTH = 5

        fun create(anchor: FarmPointPosition): FarmProcessingLayout {
            val machine = offset(anchor, 0.0, 0.0, 0.0)
            val wheel = offset(anchor, 0.0, 0.55, 1.15)
            val inputRack = offset(anchor, -3.0, 0.0, 0.35)
            val inputDrop = offset(anchor, -1.15, 0.45, 0.7)
            val outputChute = offset(anchor, 1.35, 0.25, 0.7)
            val outputPallet = offset(anchor, 3.0, 0.0, 0.25)
            return FarmProcessingLayout(
                anchor = anchor,
                machine = machine,
                wheel = wheel,
                inputRack = inputRack,
                inputDrop = inputDrop,
                outputChute = outputChute,
                outputPallet = outputPallet,
                labels = listOf(
                    offset(inputRack, 0.0, 0.0, 1.85),
                    offset(machine, 0.0, 0.0, 2.35),
                    offset(outputPallet, 0.0, 0.0, 1.85),
                ),
                footprint = (-4..4).flatMap { right ->
                    (-2..2).map { forward -> offset(anchor, right.toDouble(), forward.toDouble(), 0.0) }
                },
            )
        }

        fun packagePosition(base: FarmPointPosition, index: Int, delivered: Boolean = false): FarmPointPosition {
            require(index in 0..15) { "Processing package index is invalid" }
            val column = index % 2
            val row = (index / 2) % 2
            val layer = index / 4
            val right = (column - 0.5) * if (delivered) 0.72 else 0.86
            val forward = (row - 0.5) * if (delivered) 0.58 else 0.74
            return offset(base, right, forward, layer * 0.52)
        }

        /** `right` is local east-west; `forward` follows the anchor yaw. */
        fun offset(base: FarmPointPosition, right: Double, forward: Double, up: Double): FarmPointPosition {
            val radians = Math.toRadians(base.yaw.toDouble())
            val forwardX = -sin(radians)
            val forwardZ = cos(radians)
            val rightX = cos(radians)
            val rightZ = sin(radians)
            return base.copy(
                x = base.x + rightX * right + forwardX * forward,
                y = base.y + up,
                z = base.z + rightZ * right + forwardZ * forward,
                pitch = 0f,
            )
        }
    }
}

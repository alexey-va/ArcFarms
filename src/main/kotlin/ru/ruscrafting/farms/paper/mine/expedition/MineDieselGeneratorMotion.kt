package ru.ruscrafting.farms.paper.mine.expedition

import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Slider-crank linkage for the six cylinders; all distances are model-local blocks. */
internal object MineDieselGeneratorMotion {
    const val CRANK_RADIUS = .45f
    const val ROD_LENGTH = 2.5f
    val motions = (0..5).flatMap { listOf("diesel_piston_$it", "diesel_rod_$it") }.toSet()
    private val offsets = floatArrayOf(0f, (2 * PI / 3).toFloat(), (4 * PI / 3).toFloat(),
        (4 * PI / 3).toFloat(), (2 * PI / 3).toFloat(), 0f)

    private fun phase(part: MineDisplayBlueprints.Part, phase: Float): Float =
        phase + offsets[part.motion.last().digitToInt()]

    fun rotation(part: MineDisplayBlueprints.Part, phase: Float): Quaternionf {
        if (part.motion.startsWith("diesel_piston_")) return Quaternionf().rotateZ(part.angle)
        val x = CRANK_RADIUS * sin(phase(part, phase))
        val rise = sqrt(ROD_LENGTH * ROD_LENGTH - x * x)
        return Quaternionf().rotateZ(part.angle + atan2(-x, rise))
    }

    fun center(part: MineDisplayBlueprints.Part, phase: Float): Vector3f {
        val theta = phase(part, phase)
        val x = -CRANK_RADIUS * sin(theta)
        val y = CRANK_RADIUS * cos(theta)
        val rise = sqrt(ROD_LENGTH * ROD_LENGTH - x * x)
        return if (part.motion.startsWith("diesel_piston_")) {
            Vector3f(part.center).add(part.pivot).add(0f, y + rise, 0f)
        } else {
            // The rod's centre and orientation share the same two pin endpoints.
            Quaternionf().rotateZ(atan2(x, rise)).transform(Vector3f(part.center))
                .add(part.pivot).add(x / 2f, y + rise / 2f, 0f)
        }
    }
}

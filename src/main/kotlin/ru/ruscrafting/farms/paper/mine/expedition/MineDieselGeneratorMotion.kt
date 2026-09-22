package ru.ruscrafting.farms.paper.mine.expedition

import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Slider-crank linkage for the six cylinders; all distances are model-local blocks. */
internal object MineDieselGeneratorMotion {
    const val CYCLE_MILLIS = 6_000L
    const val IGNITION_MILLIS = 1_000L
    const val IGNITION_WINDOW_MILLIS = 180L
    const val EXHAUST_MILLIS = 450L
    const val CRANK_RADIUS = .45f
    const val ROD_LENGTH = 2.5f
    val motions = (0..5).flatMap { listOf("diesel_piston_$it", "diesel_rod_$it",
        "diesel_valve_inlet_$it", "diesel_valve_exhaust_$it") }.toSet()
    private val offsets = floatArrayOf(0f, (2 * PI / 3).toFloat(), (4 * PI / 3).toFloat(),
        (4 * PI / 3).toFloat(), (2 * PI / 3).toFloat(), 0f)

    // Evenly spaced firing TDCs, matching the authored crank throws.
    private val firingSteps = intArrayOf(0, 2, 1, 4, 5, 3)
    fun ignitionCylinder(now: Long): Int = firingSteps.indexOf(((now % CYCLE_MILLIS) / IGNITION_MILLIS).toInt())
    fun camAngle(cylinder: Int, inlet: Boolean): Float =
        -(firingSteps[cylinder] * PI / 3 + if (inlet) 5 * PI / 4 else 3 * PI / 4).toFloat()

    fun valveLift(cylinder: Int, inlet: Boolean, crankPhase: Float): Float {
        val theta = crankPhase / 2 + camAngle(cylinder, inlet)
        // Flat follower touches the lower edge of the same rectangular nose
        // used by the model; .316 bounds the segmented base-circle surface.
        return (.22f * cos(theta) + .21f * abs(cos(theta)) + .09f * abs(sin(theta)) - .316f)
            .coerceAtLeast(0f)
    }

    private fun phase(part: MineDisplayBlueprints.Part, phase: Float): Float =
        phase + offsets[part.motion.last().digitToInt()]

    fun rotation(part: MineDisplayBlueprints.Part, phase: Float): Quaternionf {
        if (part.motion.startsWith("diesel_valve_")) return Quaternionf().rotateZ(part.angle)
        if (part.motion.startsWith("diesel_piston_")) return Quaternionf().rotateZ(part.angle)
        val x = CRANK_RADIUS * sin(phase(part, phase))
        val rise = sqrt(ROD_LENGTH * ROD_LENGTH - x * x)
        return Quaternionf().rotateZ(part.angle + atan2(-x, rise))
    }

    fun center(part: MineDisplayBlueprints.Part, phase: Float): Vector3f {
        if (part.motion.startsWith("diesel_valve_")) return Vector3f(part.center).add(0f,
            -valveLift(part.motion.last().digitToInt(), part.motion.startsWith("diesel_valve_inlet_"), phase), 0f)
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

package ru.ruscrafting.farms.paper.lumber.sawmill

import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillGeometry.SCENE_SCALE
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One clamped log, one longitudinal cut, one retained slab, then a clear return. */
internal object CedarSawmillAnimation {
    const val DURATION_SECONDS = 25.0
    const val SAMPLE_SECONDS = 0.1
    const val LOG_LENGTH = 4.4
    const val LOG_BOTTOM = 1.27
    const val LOG_HEIGHT = 0.72
    const val SECTION_COUNT = 5

    data class Pose(val partId: String, val center: Vector3f, val size: Vector3f, val rotation: Quaternionf, val visible: Boolean)
    data class Frame(
        val elapsedSeconds: Double,
        val contact: Boolean,
        val finished: Boolean,
        val poses: List<Pose>,
        val carriageZ: Double,
        val forwardZ: Double,
        val lateral: Double,
        val cutThreshold: Double,
        val outputZ: Double,
        val split: Double,
    )
    private data class State(
        val time: Double, val forwardZ: Double, val carriageZ: Double, val lateral: Double,
        val split: Double, val angle: Double, val contact: Boolean, val cutThreshold: Double, val outputZ: Double,
    )

    fun sample(seconds: Double): Frame {
        require(seconds.isFinite()) { "Sawmill time must be finite" }
        val state = state(seconds.coerceIn(0.0, DURATION_SECONDS))
        val poses = CedarSawmillGeometry.parts.map { part ->
            val center = Vector3f(part.center)
            val size = Vector3f(part.size)
            val rotation = Quaternionf(part.rotation)
            var visible = true
            fun turn(angle: Double) {
                val q = Quaternionf().rotationX(angle.toFloat())
                center.sub(part.pivot).rotate(q).add(part.pivot)
                rotation.premul(q)
            }
            when (part.motion) {
                "blade", "pulley" -> turn(state.angle * part.ratio)
                "arbor" -> rotation.rotationX(state.angle.toFloat())
                "lever" -> turn(smooth(state.time / .7) * .7)
                "carriage", "carriage-wheel", "headblock" -> {
                    center.z += state.carriageZ.toFloat()
                    if (part.motion == "headblock") center.x += state.lateral.toFloat()
                    if (part.motion == "carriage-wheel") rotation.rotationX(((state.carriageZ + 5.8) / .155).toFloat())
                }
                "timber-raw", "timber-sawn", "timber-slab" -> {
                    val sectionStart = -LOG_LENGTH / 2 + part.section * LOG_LENGTH / SECTION_COUNT
                    val sectionEnd = sectionStart + LOG_LENGTH / SECTION_COUNT
                    val raw = part.motion == "timber-raw"
                    val low = if (raw) sectionStart else max(sectionStart, state.cutThreshold)
                    val high = if (raw) min(sectionEnd, state.cutThreshold) else sectionEnd
                    val length = max(0.0, high - low)
                    visible = length > .0001
                    size.set(1f, 1f, max(length, .00001).toFloat())
                    center.set(state.lateral.toFloat(), (LOG_BOTTOM + LOG_HEIGHT / 2).toFloat(),
                        (state.carriageZ + (low + high) / 2).toFloat())
                    if (part.motion == "timber-slab") {
                        val tilt = -PI / 2 * state.split
                        rotation.rotationZ(tilt.toFloat())
                        val minimumY = CedarSawmillGeometry.slabContactVertices.minOf { Vector3f(it).rotate(rotation).y }.toDouble()
                        val grounded = LOG_BOTTOM - minimumY
                        center.set((.3925 + state.split * .74).toFloat(),
                            mix(LOG_BOTTOM + LOG_HEIGHT / 2, grounded, state.split).toFloat(),
                            (state.outputZ + (low + high) / 2).toFloat())
                    }
                }
                "sawdust" -> {
                    val cycle = .72
                    val offset = part.section * cycle / 12
                    val birth = floor((state.time - offset) / cycle) * cycle + offset
                    val age = state.time - birth
                    visible = birth >= 0 && age < .48 && state(max(0.0, birth)).contact
                    val seed = sin(part.section * 73.7 + floor(birth * 30) * 2.1) * .5 + .5
                    val chipSize = .022 + seed * .018
                    size.set(chipSize.toFloat(), (chipSize * .5).toFloat(), (chipSize * 1.5).toFloat())
                    center.set((.27 + age * (seed - .5) * .55).toFloat(),
                        max(.1, LOG_BOTTOM + .09 - age * (1.2 + seed * .6) - 3.8 * age * age).toFloat(),
                        (-.85 - age * (.3 + seed * .5)).toFloat())
                    rotation.rotationXYZ((age * 8).toFloat(), (seed * 7).toFloat(), (age * 5).toFloat())
                }
            }
            Pose(part.id, center.mul(SCENE_SCALE), size.mul(SCENE_SCALE), rotation, visible)
        }
        return Frame(state.time, state.contact, state.time >= DURATION_SECONDS, poses,
            state.carriageZ * SCENE_SCALE, state.forwardZ * SCENE_SCALE, state.lateral * SCENE_SCALE,
            state.cutThreshold * SCENE_SCALE, state.outputZ * SCENE_SCALE, state.split)
    }

    private fun state(time: Double): State {
        val forwardZ = mix(-5.8, 3.6, feedEase((time - 1.2) / 12.6))
        val carriageZ = if (time < 16.4) forwardZ else mix(3.6, -5.8, smooth((time - 16.4) / 6.6))
        val speed = smooth(time / 1.5) * (1 - smooth((time - 23) / 2))
        fun primitive(x: Double) = x * x * x - .5 * x * x * x * x
        val poweredTime = when {
            time < 1.5 -> 1.5 * primitive(time / 1.5)
            time <= 23 -> time - .75
            else -> 22.25 + min(time - 23, 2.0) - 2 * primitive(((time - 23) / 2).coerceIn(0.0, 1.0))
        }
        return State(time, forwardZ, carriageZ, -.46 * smooth((time - 14.2) / 1.5),
            smooth((time - 14.1) / 2.1), -poweredTime * PI * 4.4,
            time < 14 && forwardZ + LOG_LENGTH / 2 > -.97 && forwardZ - LOG_LENGTH / 2 < .97 && speed > .1,
            // Never derive this from the returning carriage: a sawn face cannot grow bark back.
            (-forwardZ).coerceIn(-LOG_LENGTH / 2, LOG_LENGTH / 2), if (time < 14) forwardZ else 3.6)
    }

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    private fun mix(from: Double, to: Double, fraction: Double) = from + (to - from) * fraction
    private fun feedEase(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        val edge = .12
        val area = 1 - edge
        return when {
            t < edge -> t * t / (2 * edge * area)
            t > 1 - edge -> 1 - (1 - t) * (1 - t) / (2 * edge * area)
            else -> (t - edge / 2) / area
        }
    }
}

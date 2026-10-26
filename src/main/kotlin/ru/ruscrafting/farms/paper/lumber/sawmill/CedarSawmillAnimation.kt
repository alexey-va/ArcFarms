package ru.ruscrafting.farms.paper.lumber.sawmill

import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillGeometry.SCENE_SCALE
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Two deliberate cuts of the same log; each completed pass waits for the next lever press. */
internal object CedarSawmillAnimation {
    const val DURATION_SECONDS = 25.0
    const val SAMPLE_SECONDS = 0.1
    const val LOG_LENGTH = 4.4
    const val LOG_BOTTOM = 1.27
    const val LOG_HEIGHT = 0.72
    const val SECTION_COUNT = 5
    const val OUTPUT_X = -2.15
    private const val SECOND_SET = .35
    private const val FINAL_WIDTH = .365
    private const val FINAL_SCALE = FINAL_WIDTH / .715
    private const val FINAL_OFFSET = -.48 + .48 * FINAL_SCALE

    data class Pose(val partId: String, val center: Vector3f, val size: Vector3f, val rotation: Quaternionf, val visible: Boolean)
    data class Frame(
        val elapsedSeconds: Double, val contact: Boolean, val finished: Boolean, val poses: List<Pose>,
        val carriageZ: Double, val forwardZ: Double, val lateral: Double, val cutThreshold: Double,
        val outputZ: Double, val split: Double, val passIndex: Int,
    )
    private data class State(
        val time: Double, val forwardZ: Double, val carriageZ: Double, val lateral: Double,
        val split: Double, val angle: Double, val contact: Boolean, val cutThreshold: Double, val outputZ: Double,
    )
    private val sawnVertices = CedarSawmillGeometry.sourceDocument().getAsJsonObject("nativeModels")
        .getAsJsonObject("sawn").getAsJsonArray("boxes").flatMap { element ->
            val box = element.asJsonObject
            val low = box.getAsJsonArray("min")
            val high = box.getAsJsonArray("max")
            listOf(Vector3f(low[0].asFloat, low[1].asFloat, 0f), Vector3f(low[0].asFloat, high[1].asFloat, 0f),
                Vector3f(high[0].asFloat, low[1].asFloat, 0f), Vector3f(high[0].asFloat, high[1].asFloat, 0f))
        }
    private val boardVertices = listOf(Vector3f(-.14f, -.36f, 0f), Vector3f(-.14f, .36f, 0f),
        Vector3f(.14f, -.36f, 0f), Vector3f(.14f, .36f, 0f))

    fun sample(seconds: Double, passIndex: Int = 0): Frame {
        require(seconds.isFinite() && passIndex in 0..1)
        val state = state(seconds.coerceIn(0.0, DURATION_SECONDS), passIndex)
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
            // Lift, turn and settle onto the exact preceding output's top, after the whole cut clears the blade.
            fun offbear(start: Double, base: Double, fromX: Double, vertices: List<Vector3f>, xScale: Double = 1.0) {
                val progress = smooth((state.time - start) / 2.0)
                rotation.rotationZ((-PI / 2 * progress).toFloat())
                val minimumY = vertices.minOf { Vector3f(it).mul(xScale.toFloat(), 1f, 1f).rotate(rotation).y }.toDouble()
                center.x = mix(fromX, OUTPUT_X, progress).toFloat()
                center.y = (mix(LOG_BOTTOM + LOG_HEIGHT / 2, base - minimumY, progress) + .30 * sin(PI * progress)).toFloat()
            }
            when (part.motion) {
                "blade", "pulley" -> turn(state.angle * part.ratio)
                "arbor" -> rotation.rotationX(state.angle.toFloat())
                "lever" -> turn(smooth(state.time / .7) * (1 - smooth((state.time - 23) / 2)) * .7)
                "carriage", "carriage-wheel", "headblock" -> {
                    center.z += state.carriageZ.toFloat()
                    if (part.motion == "headblock") {
                        // The stock moves over the headblocks; the wooden bolster must never cross the blade slot.
                        center.x += (if (part.id.startsWith("timber-bolster")) min(0.0, state.lateral) else state.lateral).toFloat()
                    }
                    if (part.motion == "carriage-wheel") rotation.rotationX(((state.carriageZ + 5.8) / .155).toFloat())
                }
                "timber-raw", "timber-sawn", "timber-slab", "timber-final", "timber-board" -> {
                    val first = -LOG_LENGTH / 2 + part.section * LOG_LENGTH / SECTION_COUNT
                    val last = first + LOG_LENGTH / SECTION_COUNT
                    val beforeCut = part.motion == "timber-raw" || (passIndex == 1 && part.motion == "timber-sawn")
                    val retainedOutput = passIndex == 1 && part.motion == "timber-slab"
                    val active = when (part.motion) {
                        "timber-raw" -> passIndex == 0
                        "timber-final", "timber-board" -> passIndex == 1
                        else -> true
                    }
                    val low = if (retainedOutput || beforeCut) first else max(first, state.cutThreshold)
                    val high = if (retainedOutput || !beforeCut) last else min(last, state.cutThreshold)
                    val length = max(0.0, high - low)
                    visible = active && length > .0001
                    size.set(1f, 1f, max(length, .00001).toFloat())
                    center.set(state.lateral.toFloat(), (LOG_BOTTOM + LOG_HEIGHT / 2).toFloat(),
                        (state.carriageZ + (low + high) / 2).toFloat())
                    when (part.motion) {
                        "timber-slab" -> {
                            center.z = ((if (retainedOutput) 3.6 else state.outputZ) + (low + high) / 2).toFloat()
                            if (retainedOutput) {
                                rotation.rotationZ((-PI / 2).toFloat())
                                center.x = OUTPUT_X.toFloat()
                                center.y = (LOG_BOTTOM + .0875).toFloat()
                            } else offbear(14.0, LOG_BOTTOM, .3925, CedarSawmillGeometry.slabContactVertices)
                        }
                        "timber-board" -> {
                            size.set(.28f, .72f, max(length, .00001).toFloat())
                            center.z = (state.outputZ + (low + high) / 2).toFloat()
                            offbear(14.0, LOG_BOTTOM + .175, .445, boardVertices)
                        }
                        "timber-final" -> {
                            size.x = FINAL_SCALE.toFloat()
                            center.x += FINAL_OFFSET.toFloat()
                            if (state.time >= 16.2) {
                                center.z = (3.6 + (low + high) / 2).toFloat()
                                offbear(16.2, LOG_BOTTOM + .175 + .28, SECOND_SET + FINAL_OFFSET, sawnVertices, FINAL_SCALE)
                            }
                        }
                    }
                }
                "sawdust" -> {
                    val cycle = .72
                    val offset = part.section * cycle / 12
                    val birth = floor((state.time - offset) / cycle) * cycle + offset
                    val age = state.time - birth
                    visible = birth >= 0 && age < .48 && state(max(0.0, birth), passIndex).contact
                    val seed = sin(part.section * 73.7 + floor(birth * 30) * 2.1) * .5 + .5
                    val chipSize = .022 + seed * .018
                    size.set(chipSize.toFloat(), (chipSize * .5).toFloat(), (chipSize * 1.5).toFloat())
                    center.set((.27 + age * (seed - .5) * .55).toFloat(),
                        max(.1, LOG_BOTTOM + .09 - age * (1.2 + seed * .6) - 3.8 * age * age).toFloat(),
                        (-.85 - age * (.3 + seed * .5)).toFloat())
                    rotation.rotationXYZ((age * 8).toFloat(), (seed * 7).toFloat(), (age * 5).toFloat())
                }
            }
            Pose(part.id, center.mul(SCENE_SCALE).rotate(CedarSawmillGeometry.sceneRotation), size.mul(SCENE_SCALE),
                rotation.premul(CedarSawmillGeometry.sceneRotation), visible)
        }
        return Frame(state.time, state.contact, state.time >= DURATION_SECONDS, poses,
            state.carriageZ * SCENE_SCALE, state.forwardZ * SCENE_SCALE, state.lateral * SCENE_SCALE,
            state.cutThreshold * SCENE_SCALE, state.outputZ * SCENE_SCALE, state.split, passIndex)
    }

    private fun state(time: Double, pass: Int): State {
        val forwardZ = mix(-5.8, 3.6, feedEase((time - 1.2) / 12.6))
        val returnAt = if (pass == 0) 16.4 else 18.4
        val carriageZ = if (time < returnAt) forwardZ else mix(3.6, -5.8, smooth((time - returnAt) / (23 - returnAt)))
        val lateral = if (pass == 0) -.46 * smooth((time - 14.2) / 1.5) else
            mix(-.46, SECOND_SET, smooth(time / 1.2)) - .46 * smooth((time - 18.4) / 1.5)
        val speed = smooth(time / 1.5) * (1 - smooth((time - 23) / 2))
        fun primitive(x: Double) = x * x * x - .5 * x * x * x * x
        val poweredTime = when {
            time < 1.5 -> 1.5 * primitive(time / 1.5)
            time <= 23 -> time - .75
            else -> 22.25 + min(time - 23, 2.0) - 2 * primitive(((time - 23) / 2).coerceIn(0.0, 1.0))
        }
        // Fifty whole turns (and75 at the1.5 pulley ratio) meet the next ready pose exactly.
        val angle = if (time == DURATION_SECONDS) 0.0 else -poweredTime * PI * 100 / 23.25
        return State(time, forwardZ, carriageZ, lateral, smooth((time - 14) / 2), angle,
            time < 14 && forwardZ + LOG_LENGTH / 2 > -.97 && forwardZ - LOG_LENGTH / 2 < .97 && speed > .1,
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

package ru.ruscrafting.farms.paper.lumber.sawmill

import com.google.gson.Gson
import com.google.gson.JsonArray
import java.nio.file.Files
import java.nio.file.Path
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillGeometry.SCENE_SCALE
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/** Offline export of the exact runtime sampler; never used as a second runtime. */
internal object CedarSawmillExport {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Supply the sawmill export JSON path" }
        write(args[0])
    }

    @JvmStatic
    fun write(path: String) {
        val destination = Path.of(path)
        val document = CedarSawmillGeometry.sourceDocument()
        document.addProperty("cycleSeconds", CedarSawmillAnimation.DURATION_SECONDS)
        document.addProperty("sampleSeconds", CedarSawmillAnimation.SAMPLE_SECONDS)
        document.addProperty("logLength", CedarSawmillAnimation.LOG_LENGTH * SCENE_SCALE)
        document.addProperty("logBottom", CedarSawmillAnimation.LOG_BOTTOM * SCENE_SCALE)
        document.addProperty("logHeight", CedarSawmillAnimation.LOG_HEIGHT * SCENE_SCALE)
        val passes = JsonArray()
        val sweptMin = Vector3f(Float.POSITIVE_INFINITY)
        val sweptMax = Vector3f(Float.NEGATIVE_INFINITY)
        for (passIndex in 0..1) {
            val frames = JsonArray()
            var previousThreshold = Double.POSITIVE_INFINITY
            val partIds = CedarSawmillGeometry.parts.map { it.id }
            for (tick in 0..250) {
                val frame = CedarSawmillAnimation.sample(tick / 10.0, passIndex)
                check(frame.cutThreshold <= previousThreshold + 1e-8) { "Cut face regrew at tick $tick" }
                previousThreshold = frame.cutThreshold
                check(frame.poses.map { it.partId } == partIds) { "Unstable part identities" }
                val output = JsonArray()
                for (value in listOf(frame.elapsedSeconds, frame.forwardZ, frame.carriageZ, frame.lateral,
                    frame.cutThreshold, frame.outputZ, frame.split, if (frame.contact) 1.0 else 0.0)) output.add(value)
                val poses = JsonArray()
                frame.poses.forEachIndexed { index, pose ->
                    check(pose.center.isFinite && pose.size.isFinite && pose.rotation.isFinite) { "Invalid pose: ${pose.partId}" }
                    if (CedarSawmillGeometry.parts[index].control) {
                        val envelope = CedarSawmillGeometry.interactionSize
                        val halfWidth = maxOf(envelope.x, envelope.z) / 2
                        for (x in listOf(-.5f, .5f)) for (y in listOf(-.5f, .5f)) for (z in listOf(-.5f, .5f)) {
                            val corner = Vector3f(x, y, z).mul(pose.size).rotate(pose.rotation).add(pose.center)
                                .sub(CedarSawmillGeometry.interactionCenter)
                            check(abs(corner.x) <= halfWidth && abs(corner.z) <= halfWidth && abs(corner.y) <= envelope.y / 2) {
                                "Lever escapes native Interaction envelope: tick $tick / ${pose.partId}"
                            }
                        }
                    }
                    val part = CedarSawmillGeometry.parts[index]
                    if (pose.visible && part.motion != "sawdust") {
                        val boxes = part.itemModel?.let { document.getAsJsonObject("nativeModels")
                            .getAsJsonObject(it.substringAfterLast('_')).getAsJsonArray("boxes") }
                        val hulls = boxes?.map { element ->
                            val box = element.asJsonObject
                            listOf("min", "max").map { key -> box.getAsJsonArray(key).let { Vector3f(it[0].asFloat, it[1].asFloat, it[2].asFloat) } }
                        } ?: listOf(listOf(Vector3f(-.5f), Vector3f(.5f)))
                        for ((low, high) in hulls) for (x in listOf(low.x, high.x)) for (y in listOf(low.y, high.y)) for (z in listOf(low.z, high.z)) {
                            val point = Vector3f(x, y, z).mul(pose.size).rotate(pose.rotation).add(pose.center).add(CedarSawmillGeometry.origin)
                            sweptMin.min(point)
                            sweptMax.max(point)
                        }
                    }
                    if (tick == 0 || CedarSawmillGeometry.parts[index].moving) {
                        val record = JsonArray()
                        record.add(index)
                        record.add(if (pose.visible) 1 else 0)
                        for (value in listOf(pose.center.x, pose.center.y, pose.center.z, pose.size.x, pose.size.y, pose.size.z,
                            pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)) record.add(round(value * 1e6) / 1e6)
                        poses.add(record)
                    }
                }
                output.add(poses)
                frames.add(output)
            }
            val finalFrame = CedarSawmillAnimation.sample(25.0, passIndex)
            check(finalFrame.finished && !finalFrame.contact)
            val timberIds = CedarSawmillGeometry.parts.filter { it.motion.startsWith("timber-") }.map { it.id }.toSet()
            check(finalFrame.poses.count { it.partId in timberIds && it.visible } == if (passIndex == 0) 10 else 15)
            check(finalFrame.outputZ > finalFrame.carriageZ)
            // Regression: independently lerped orbital centers shrink a 1.035m tooth radius
            // to0.79748m at the half-frame. A native rigid model must keep every radius.
            check(1.035 * kotlin.math.cos(Math.toRadians(79.2 / 2)) < .80)
            val rotors = CedarSawmillGeometry.parts.withIndex().filter { (_, part) -> part.motion in setOf("blade", "pulley") }
            check(rotors.size == 3 && rotors.all { it.value.itemModel != null })
            for (tick in 0 until 250) {
                val before = CedarSawmillAnimation.sample(tick / 10.0, passIndex)
                val after = CedarSawmillAnimation.sample((tick + 1) / 10.0, passIndex)
                for ((index, part) in rotors) for (fraction in listOf(.25f, .5f, .75f)) {
                    val a = before.poses[index]
                    val b = after.poses[index]
                    val center = Vector3f(a.center).lerp(b.center, fraction)
                    val pivot = Vector3f(part.pivot).mul(SCENE_SCALE)
                    check(center.distance(pivot) < 1e-5f) { "Rotor center follows a chord: ${part.id}" }
                    val rotation = Quaternionf(a.rotation).slerp(b.rotation, fraction)
                    val size = Vector3f(a.size).lerp(b.size, fraction)
                    val kind = part.itemModel!!.substringAfterLast('_')
                    val boxes = document.getAsJsonObject("nativeModels").getAsJsonObject(kind).getAsJsonArray("boxes")
                    for (box in boxes) for (end in listOf("min", "max")) {
                        val point = box.asJsonObject.getAsJsonArray(end)
                        val local = Vector3f(point[0].asFloat, point[1].asFloat, point[2].asFloat).mul(size)
                        val radius = hypot(local.y, local.z)
                        val transformed = Vector3f(local).rotate(rotation).add(center).sub(pivot)
                        check(abs(hypot(transformed.y, transformed.z) - radius) < 1e-5f) { "Rotor deforms between frames: ${part.id}" }
                    }
                }
            }
            passes.add(frames)
        }
        val ready = CedarSawmillAnimation.sample(25.0, 0)
        val next = CedarSawmillAnimation.sample(0.0, 1)
        ready.poses.zip(next.poses).forEach { (a, b) ->
            check(a.visible == b.visible) { "Stock/output visibility changed between passes: ${a.partId}" }
            if (a.visible) check(a.center.distance(b.center) < 1e-5 && a.size.distance(b.size) < 1e-5 &&
                abs(abs(a.rotation.dot(b.rotation)) - 1) < 1e-5) { "Material jumped between passes: ${a.partId}" }
        }
        check(sweptMin.x >= 183 && sweptMin.y >= 73 - 1e-5 && sweptMin.z >= 167 &&
            sweptMax.x <= 196 && sweptMax.y < 78 && sweptMax.z <= 194) { "Sawmill escapes the captured hall: $sweptMin / $sweptMax" }
        document.add("sweptBounds", Gson().toJsonTree(mapOf("min" to listOf(sweptMin.x, sweptMin.y, sweptMin.z), "max" to listOf(sweptMax.x, sweptMax.y, sweptMax.z))))
        document.add("passes", passes)
        document.add("frames", passes[0])
        Files.createDirectories(destination.parent)
        Files.writeString(destination, Gson().toJson(document))
        println("Exported ${CedarSawmillGeometry.parts.size} native parts and ${passes.size()} passes / 502 runtime frames to $destination")
    }
}

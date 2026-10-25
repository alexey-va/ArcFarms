package ru.ruscrafting.farms.paper.lumber.sawmill

import com.google.gson.Gson
import com.google.gson.JsonArray
import java.nio.file.Files
import java.nio.file.Path
import org.joml.Quaternionf
import org.joml.Vector3f
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
        document.addProperty("logBottom", CedarSawmillAnimation.LOG_BOTTOM)
        document.addProperty("logHeight", CedarSawmillAnimation.LOG_HEIGHT)
        val frames = JsonArray()
        var previousThreshold = Double.POSITIVE_INFINITY
        val partIds = CedarSawmillGeometry.parts.map { it.id }
        for (tick in 0..250) {
            val frame = CedarSawmillAnimation.sample(tick / 10.0)
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
        val finalFrame = CedarSawmillAnimation.sample(25.0)
        check(finalFrame.finished && !finalFrame.contact)
        val timberIds = CedarSawmillGeometry.parts.filter { it.motion.startsWith("timber-") }.map { it.id }.toSet()
        check(finalFrame.poses.count { it.partId in timberIds && it.visible } == 10)
        check(finalFrame.outputZ > finalFrame.carriageZ)
        // Regression: independently lerped orbital centers shrink a 1.035m tooth radius
        // to0.79748m at the half-frame. A native rigid model must keep every radius.
        check(1.035 * kotlin.math.cos(Math.toRadians(79.2 / 2)) < .80)
        val rotors = CedarSawmillGeometry.parts.withIndex().filter { (_, part) -> part.motion in setOf("blade", "pulley") }
        check(rotors.size == 3 && rotors.all { it.value.itemModel != null })
        for (tick in 0 until 250) {
            val before = CedarSawmillAnimation.sample(tick / 10.0)
            val after = CedarSawmillAnimation.sample((tick + 1) / 10.0)
            for ((index, part) in rotors) for (fraction in listOf(.25f, .5f, .75f)) {
                val a = before.poses[index]
                val b = after.poses[index]
                val center = Vector3f(a.center).lerp(b.center, fraction)
                check(center.distance(part.pivot) < 1e-5f) { "Rotor center follows a chord: ${part.id}" }
                val rotation = Quaternionf(a.rotation).slerp(b.rotation, fraction)
                val size = Vector3f(a.size).lerp(b.size, fraction)
                val kind = part.itemModel!!.substringAfterLast('_')
                val boxes = document.getAsJsonObject("nativeModels").getAsJsonObject(kind).getAsJsonArray("boxes")
                for (box in boxes) for (end in listOf("min", "max")) {
                    val point = box.asJsonObject.getAsJsonArray(end)
                    val local = Vector3f(point[0].asFloat, point[1].asFloat, point[2].asFloat).mul(size)
                    val radius = hypot(local.y, local.z)
                    val transformed = Vector3f(local).rotate(rotation).add(center).sub(part.pivot)
                    check(abs(hypot(transformed.y, transformed.z) - radius) < 1e-5f) { "Rotor deforms between frames: ${part.id}" }
                }
            }
        }
        document.add("frames", frames)
        Files.createDirectories(destination.parent)
        Files.writeString(destination, Gson().toJson(document))
        println("Exported ${CedarSawmillGeometry.parts.size} native parts and ${frames.size()} runtime frames to $destination")
    }
}

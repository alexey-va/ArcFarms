package ru.ruscrafting.farms.paper.mine.floorwork

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.MineFloorActivity
import ru.ruscrafting.farms.domain.MineIncidentType

class MineFloorActivityGeometryTest : FunSpec({
    test("industrial assemblies retain bounded part identities through their full swept poses") {
        for (type in listOf(MineIncidentType.FREIGHT_WINCH, MineIncidentType.DRAINAGE_PUMP)) {
            val initial = MineFloorActivityGeometry.parts(type, MineFloorActivityGeometry.Pose())
            val bounds = MineFloorActivityGeometry.bounds(type)
            (initial.size <= 96) shouldBe true
            (bounds.maxX - bounds.minX >= 12f) shouldBe true
            for (frame in 0..40) {
                val phase = frame / 40f
                val pose = if (type == MineIncidentType.FREIGHT_WINCH)
                    MineFloorActivityGeometry.Pose(progress = 2, pull = 1f, swing = phase)
                else MineFloorActivityGeometry.Pose(progress = 5, press = phase, drain = phase)
                val parts = MineFloorActivityGeometry.parts(type, pose)
                parts.map { it.id } shouldBe initial.map { it.id }
                parts.all { it.size.x > 0 && it.size.y > 0 && it.size.z > 0 } shouldBe true
                parts.filter { it.visible }.forEach { part ->
                    corners(part).forEach { corner ->
                        (corner.x >= bounds.minX - .001f && corner.x <= bounds.maxX + .001f) shouldBe true
                        (corner.z >= bounds.minZ - .001f && corner.z <= bounds.maxZ + .001f) shouldBe true
                        (corner.y >= -.025f && corner.y <= bounds.height + .001f) shouldBe true
                    }
                }
            }
        }
    }

    test("gantry transports one load then seats it on rollers before moving to the collection end") {
        val type = MineIncidentType.FREIGHT_WINCH
        val landed = MineFloorActivityGeometry.parts(type, MineFloorActivityGeometry.Pose(progress = 2, pull = 1f, swing = .7f))
        val crate = landed.first { it.id == "crate" }
        val roller = landed.first { it.id == "receiver_roll_0" }
        (crate.center.y - crate.size.y / 2).toDouble() shouldBe
            ((roller.center.y + roller.size.y / 2).toDouble() plusOrMinus .0001)
        crate.center.x.toDouble() shouldBe (roller.center.x.toDouble() plusOrMinus .0001)
        crate.center.z.toDouble() shouldBe (roller.center.z.toDouble() plusOrMinus .0001)
        val delivered = MineFloorActivityGeometry.parts(type, MineFloorActivityGeometry.Pose(progress = 3))
        delivered.first { it.id == "crate" }.center.distance(Vector3f(-4.5f, 1.7f, 2.4f)).toDouble() shouldBe (0.0 plusOrMinus .0001)
        // The hoist withdraws continuously while the same crate travels forward on the rollers.
        val next = MineFloorActivityGeometry.parts(type, MineFloorActivityGeometry.Pose(progress = 2, pull = 1f, swing = .701f))
        (next.first { it.id == "spreader" }.center.distance(landed.first { it.id == "spreader" }.center) < .03f) shouldBe true
        val returning = MineFloorActivityGeometry.parts(type, MineFloorActivityGeometry.Pose(progress = 4, returning = 1f))
        returning.first { it.id == "crate" }.visible shouldBe false
        val rest = MineFloorActivityGeometry.parts(type, MineFloorActivityGeometry.Pose())
        returning.first { it.id == "spreader" }.center.distance(rest.first { it.id == "spreader" }.center).toDouble() shouldBe
            (0.0 plusOrMinus .0001)
    }

    test("pump beam, manual grip link and piston stay joined through all four pressure strokes") {
        for (frame in 0..20) {
            val parts = MineFloorActivityGeometry.parts(MineIncidentType.DRAINAGE_PUMP,
                MineFloorActivityGeometry.Pose(progress = 2, press = frame / 20f))
            val beam = parts.first { it.id == "beam" }
            val link = parts.first { it.id == "handle_link" }
            val grip = parts.first { it.id == "grip" }
            val end = beam.rotation.transform(Vector3f(0f, 0f, beam.size.z / 2)).add(beam.center)
            Vector3f(link.center.x, link.center.y + link.size.y / 2, link.center.z).distance(end).toDouble() shouldBe (0.0 plusOrMinus .0001)
            (link.center.y - link.size.y / 2).toDouble() shouldBe (grip.center.y.toDouble() plusOrMinus .0001)
            val piston = parts.first { it.id == "piston" }
            val back = beam.rotation.transform(Vector3f(0f, 0f, -beam.size.z / 2)).add(beam.center)
            Vector3f(piston.center.x, piston.center.y + piston.size.y / 2, piston.center.z).distance(back).toDouble() shouldBe (0.0 plusOrMinus .0001)
        }
    }

    test("repair pipe is the same part on the rack, in hand and exactly between connected flanges") {
        val carried = MineFloorActivityGeometry.parts(MineIncidentType.DRAINAGE_PUMP,
            MineFloorActivityGeometry.Pose(pipe = Vector3f(-1f, 1f, 2f)))
        val installed = MineFloorActivityGeometry.parts(MineIncidentType.DRAINAGE_PUMP, MineFloorActivityGeometry.Pose(progress = 1))
        carried.map { it.id } shouldBe installed.map { it.id }
        val pipe = installed.first { it.id == "repair_pipe" }
        pipe.center shouldBe MineFloorActivityGeometry.PIPE_SOCKET
        val a = installed.first { it.id == "flange_a" }; val b = installed.first { it.id == "flange_b" }
        (pipe.center.x - pipe.size.x / 2).toDouble() shouldBe ((a.center.x + a.size.x / 2).toDouble() plusOrMinus .0001)
        (pipe.center.x + pipe.size.x / 2).toDouble() shouldBe ((b.center.x - b.size.x / 2).toDouble() plusOrMinus .0001)
        val empty = MineFloorActivityGeometry.parts(MineIncidentType.DRAINAGE_PUMP, MineFloorActivityGeometry.Pose(progress = 6, drain = 1f))
        (empty.first { it.id == "water" }.size.y < .04f) shouldBe true
    }

    test("operator lanes leave player body clearance through every moving pose") {
        for (frame in 0..20) {
            val f = frame / 20f
            val winch = MineFloorActivityGeometry.parts(MineIncidentType.FREIGHT_WINCH, MineFloorActivityGeometry.Pose(pull = f))
            val winchPlayer = Vector3f(MineFloorActivity.PULL_X.toFloat(), .9f, (MineFloorActivity.PULL_START_Z + MineFloorActivity.PULL_DISTANCE * f).toFloat())
            winch.filterNot { it.id in setOf("pull_rope", "pull_handle") }.forEach { part ->
                intersectsPlayer(part, winchPlayer) shouldBe false
            }
            val pump = MineFloorActivityGeometry.parts(MineIncidentType.DRAINAGE_PUMP, MineFloorActivityGeometry.Pose(progress = 2, press = f))
            pump.forEach { part -> intersectsPlayer(part, Vector3f(MineFloorActivityGeometry.PUMP_OPERATOR_X, .9f, 2f)) shouldBe false }
        }
    }

    test("whole machinery and interaction frame rotate together at every cardinal yaw") {
        for (yaw in listOf(0f, 90f, -90f, 180f)) {
            val anchor = Location(null, 43.5, 97.0, 18.5, yaw, 0f)
            for (type in listOf(MineIncidentType.FREIGHT_WINCH, MineIncidentType.DRAINAGE_PUMP)) {
                for (point in MineFloorActivityGeometry.controls(type).values) {
                    val result = MineFloorActivityScene.local(anchor, MineFloorActivityScene.at(anchor, point))
                    result.distance(point).toDouble() shouldBe (0.0 plusOrMinus .0001)
                }
            }
        }
    }
})

private fun corners(part: MineFloorActivityGeometry.Part): List<Vector3f> =
    listOf(-.5f, .5f).flatMap { x -> listOf(-.5f, .5f).flatMap { y -> listOf(-.5f, .5f).map { z ->
        part.rotation.transform(Vector3f(part.size.x * x, part.size.y * y, part.size.z * z)).add(part.center)
    } } }

private fun intersectsPlayer(part: MineFloorActivityGeometry.Part, body: Vector3f): Boolean {
    val points = corners(part)
    return points.maxOf { it.x } > body.x - .3f && points.minOf { it.x } < body.x + .3f &&
        points.maxOf { it.y } > .06f && points.minOf { it.y } < 1.8f &&
        points.maxOf { it.z } > body.z - .3f && points.minOf { it.z } < body.z + .3f
}

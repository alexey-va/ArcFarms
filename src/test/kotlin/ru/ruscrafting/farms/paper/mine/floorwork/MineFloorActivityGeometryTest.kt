package ru.ruscrafting.farms.paper.mine.floorwork

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.MineIncidentType

class MineFloorActivityGeometryTest : FunSpec({
    test("winch retains all part identities while lifting and turning cargo onto its receiver") {
        val type = MineIncidentType.FREIGHT_WINCH
        val initial = MineFloorActivityGeometry.parts(type, MineFloorActivityGeometry.Pose())
        for (frame in 0..20) {
            val posed = MineFloorActivityGeometry.parts(type,
                MineFloorActivityGeometry.Pose(progress = 2, pull = 1f, swing = frame / 20f))
            posed.map { it.id } shouldBe initial.map { it.id }
            val crate = posed.first { it.id == "crate" }
            (crate.center.y - crate.size.y / 2 >= .1f) shouldBe true
            (posed.size <= 32) shouldBe true
        }
        val delivered = MineFloorActivityGeometry.parts(type, MineFloorActivityGeometry.Pose(progress = 3))
        val crate = delivered.first { it.id == "crate" }
        val receiver = delivered.first { it.id == "receiver" }
        (crate.center.y - crate.size.y / 2).toDouble() shouldBe
            ((receiver.center.y + receiver.size.y / 2).toDouble() plusOrMinus .0001)
        crate.center.x.toDouble() shouldBe (receiver.center.x.toDouble() plusOrMinus .0001)
        crate.center.z.toDouble() shouldBe (receiver.center.z.toDouble() plusOrMinus .0001)
    }

    test("pump grip remains attached to the same hinged handle through each full stroke") {
        for (frame in 0..10) {
            val parts = MineFloorActivityGeometry.parts(MineIncidentType.DRAINAGE_PUMP,
                MineFloorActivityGeometry.Pose(progress = 2, press = frame / 10f))
            val handle = parts.first { it.id == "handle" }
            val grip = parts.first { it.id == "grip" }
            val end = handle.rotation.transform(Vector3f(0f, 0f, handle.size.z / 2)).add(handle.center)
            end.distance(grip.center).toDouble() shouldBe (0.0 plusOrMinus .0001)
        }
    }

    test("carried pipe and installed pipe share identity and drained basin retains positive bounded geometry") {
        val carried = MineFloorActivityGeometry.parts(MineIncidentType.DRAINAGE_PUMP,
            MineFloorActivityGeometry.Pose(pipe = Vector3f(-1f, 1f, 2f)))
        val installed = MineFloorActivityGeometry.parts(MineIncidentType.DRAINAGE_PUMP,
            MineFloorActivityGeometry.Pose(progress = 1))
        carried.map { it.id } shouldBe installed.map { it.id }
        installed.first { it.id == "repair_pipe" }.center shouldBe Vector3f(1.28f, .6f, .3f)
        val empty = MineFloorActivityGeometry.parts(MineIncidentType.DRAINAGE_PUMP,
            MineFloorActivityGeometry.Pose(progress = 6, drain = 1f))
        empty.all { it.size.x > 0 && it.size.y > 0 && it.size.z > 0 } shouldBe true
        (empty.first { it.id == "water" }.size.y < .04f) shouldBe true
        (empty.size <= 32) shouldBe true
    }

    test("whole machinery and interaction frame rotate together at every cardinal yaw") {
        for (yaw in listOf(0f, 90f, -90f, 180f)) {
            val anchor = Location(null, 43.5, 97.0, 18.5, yaw, 0f)
            val point = Vector3f(-1.65f, .85f, 4.45f)
            val result = MineFloorActivityScene.local(anchor, MineFloorActivityScene.at(anchor, point))
            result.distance(point).toDouble() shouldBe (0.0 plusOrMinus .0001)
        }
    }
})

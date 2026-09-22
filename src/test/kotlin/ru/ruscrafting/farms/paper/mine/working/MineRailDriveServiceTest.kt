package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class MineRailDriveServiceTest : FunSpec({
    test("service anchors use the same negative-heading rotation as the drive rig") {
        val offset = MineRailDriveServiceGeometry.localOffset(90f, 1.0, .7, 0.0)

        (abs(offset.x) < .0001f) shouldBe true
        offset.y shouldBe .7f
        offset.z shouldBe 1f
    }

    test("jam poses visibly withdraw and change angle for each accepted pry") {
        val offsets = (0..2).map(MineRailDriveServiceGeometry::jamOffset)
        val angles = (0..2).map(MineRailDriveServiceGeometry::jamYawOffset)

        offsets.map { it.z } shouldBe listOf(0.0, -.18, -.34)
        angles shouldBe listOf(0, 12, -14)
        offsets.zipWithNext().all { (previous, current) -> current.z < previous.z } shouldBe true
    }
})

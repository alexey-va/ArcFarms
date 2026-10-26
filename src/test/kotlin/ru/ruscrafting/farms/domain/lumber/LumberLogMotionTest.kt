package ru.ruscrafting.farms.domain.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class LumberLogMotionTest : FunSpec({
    test("walking ahead pulls the log behind and never overtakes the carrier") {
        val carrier = LumberPose("cedar", 0.0, 73.0, 8.0)
        var log = LumberPose("cedar", 0.0, 73.0, 0.0)
        repeat(20) { log = LumberLogMotion.advance(log, carrier, 5.25) }
        log.z shouldBe (2.75 plusOrMinus .00001)
        log.yaw shouldBe 0f
        log.x shouldBe 0.0
    }

    test("looking around or approaching the log does not push it away") {
        val log = LumberPose("cedar", 0.0, 73.0, 0.0)
        val near = LumberPose("cedar", 0.0, 73.0, 4.0, 180f)
        LumberLogMotion.advance(log, near, 5.25) shouldBe log
        LumberLogMotion.advance(log, near.copy(yaw = -90f), 5.25) shouldBe log
        LumberLogMotion.advance(log, near.copy(world = "other", z = 20.0), 5.25) shouldBe log
    }
})

package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmPlayerTimeTransitionTest : FunSpec({
    test("night transition takes the shortest circular route across midnight") {
        FarmPlayerTimeTransition.step(23_800, 200, 250) shouldBe FarmPlayerTimeStep(50, false)
        FarmPlayerTimeTransition.step(50, 200, 250) shouldBe FarmPlayerTimeStep(200, true)
    }

    test("night transition moves backward when that route is shorter") {
        FarmPlayerTimeTransition.step(19_000, 18_000, 400) shouldBe FarmPlayerTimeStep(18_600, false)
        FarmPlayerTimeTransition.step(18_200, 18_000, 400) shouldBe FarmPlayerTimeStep(18_000, true)
    }

    test("six second transition is bounded for the four updates per second ambient loop") {
        FarmPlayerTimeTransition.maximumStep(6) shouldBe 500
    }

    test("twelve second transition is smooth at the per-tick update rate") {
        FarmPlayerTimeTransition.maximumStep(12, updatesPerSecond = 20) shouldBe 50
    }
})

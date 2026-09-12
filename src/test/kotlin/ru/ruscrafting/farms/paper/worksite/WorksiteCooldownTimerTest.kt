package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorksiteCooldownTimerTest : FunSpec({
    test("remaining seconds rounds up until the deadline") {
        WorksiteCooldownTimer.remainingSeconds(deadline = 141_000L, now = 0L) shouldBe 141L
        WorksiteCooldownTimer.remainingSeconds(deadline = 141_000L, now = 140_001L) shouldBe 1L
        WorksiteCooldownTimer.remainingSeconds(deadline = 141_000L, now = 141_000L) shouldBe 0L
    }

    test("progress is bounded and reaches full at the deadline") {
        WorksiteCooldownTimer.progress(deadline = 5_000L, now = 0L, durationMillis = 5_000L) shouldBe 0.0f
        WorksiteCooldownTimer.progress(deadline = 5_000L, now = 5_000L, durationMillis = 5_000L) shouldBe 1.0f
        WorksiteCooldownTimer.progress(deadline = 5_000L, now = 8_000L, durationMillis = 5_000L) shouldBe 1.0f
    }
})

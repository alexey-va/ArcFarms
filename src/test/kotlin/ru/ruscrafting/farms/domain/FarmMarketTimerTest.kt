package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmMarketTimerTest : FunSpec({
    test("rush delivery duration scales with quota and stays inside configured bounds") {
        FarmMarketTimer.durationMillis(8, 45, 3.0, 120, 300) shouldBe 120_000
        FarmMarketTimer.durationMillis(32, 45, 3.0, 120, 300) shouldBe 141_000
        FarmMarketTimer.durationMillis(128, 45, 3.0, 120, 300) shouldBe 300_000
        FarmMarketTimer.durationMillis(256, 30, 0.3, 90, 180) shouldBe 107_000
    }

    test("remaining seconds rounds up so the display never reaches zero early") {
        FarmMarketTimer.remainingSeconds(deadlineAt = 141_000, now = 0) shouldBe 141
        FarmMarketTimer.remainingSeconds(deadlineAt = 141_000, now = 140_001) shouldBe 1
        FarmMarketTimer.remainingSeconds(deadlineAt = 141_000, now = 141_000) shouldBe 0
    }
})

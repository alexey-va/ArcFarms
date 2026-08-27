package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmPerkStateTest : FunSpec({
    test("weekly point reset keeps unexpired temporary perks") {
        val state = FarmPlayerPerks(
            weekStartEpochDay = 100,
            spentPoints = 250,
            activeUntil = mapOf(
                FarmPerkType.HARVEST_AREA to 20_000,
                FarmPerkType.SPEED to 5_000,
            ),
        )

        state.forWeek(107, now = 10_000) shouldBe FarmPlayerPerks(
            weekStartEpochDay = 107,
            spentPoints = 0,
            activeUntil = mapOf(FarmPerkType.HARVEST_AREA to 20_000),
        )
    }

    test("the current week does not rewrite the ledger") {
        val state = FarmPlayerPerks(weekStartEpochDay = 107, spentPoints = 25)
        state.forWeek(107, now = 10_000) shouldBe state
    }
})

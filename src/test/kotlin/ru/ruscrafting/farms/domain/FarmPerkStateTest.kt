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

    test("an active perk cannot be extended or charged twice") {
        val activeUntil = 30_000L
        val state = FarmPlayerPerks(
            weekStartEpochDay = 107,
            spentPoints = 250,
            activeUntil = mapOf(FarmPerkType.HARVEST_AREA to activeUntil),
        )

        state.purchasePerk(
            type = FarmPerkType.HARVEST_AREA,
            price = 250,
            durationMillis = 72_000,
            weeklyContribution = 10_000,
            now = 10_000,
        ) shouldBe FarmPerkPurchaseResult.AlreadyActive(activeUntil)
    }

    test("an expired perk can be bought again from the current time") {
        val state = FarmPlayerPerks(
            weekStartEpochDay = 107,
            spentPoints = 250,
            activeUntil = mapOf(FarmPerkType.SPEED to 9_000),
        )

        state.purchasePerk(
            type = FarmPerkType.SPEED,
            price = 180,
            durationMillis = 72_000,
            weeklyContribution = 1_000,
            now = 10_000,
        ) shouldBe FarmPerkPurchaseResult.Purchased(
            state.copy(
                spentPoints = 430,
                activeUntil = mapOf(FarmPerkType.SPEED to 82_000),
            ),
            activeUntil = 82_000,
        )
    }
})

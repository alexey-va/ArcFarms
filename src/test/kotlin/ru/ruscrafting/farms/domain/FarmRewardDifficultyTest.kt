package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

class FarmRewardDifficultyTest : FunSpec({
    val common = FarmOrder(
        "common_order",
        mapOf("WHEAT" to 640, "CARROTS" to 320),
        incidentTypes = FarmIncidentType.entries,
    )
    val rare = common.copy(
        id = "rare_order",
        required = mapOf("WHEAT" to 1_440, "BEETROOTS" to 480),
        rarity = FarmContractRarity.RARE,
    )
    val rules = FarmRules(
        incidentTriggerPercents = listOf(15, 32, 50, 68, 85),
        incidentQuota = 4,
        cooldownMillis = 1_000,
        incidentCountMin = 3,
        incidentCountMax = 5,
    )

    test("every completed order receives a meaningful overall increase") {
        FarmRewardDifficulty.multiplierPercent(common, rules, sequence = 0) shouldBeGreaterThanOrEqual 150
    }

    test("more and harder events plus a rare larger order pay more") {
        val shorter = FarmRewardDifficulty.multiplierPercent(common, rules, sequence = 0)
        val longer = FarmRewardDifficulty.multiplierPercent(common, rules, sequence = 2)
        val rareLonger = FarmRewardDifficulty.multiplierPercent(rare, rules, sequence = 2)

        longer shouldBeGreaterThan shorter
        rareLonger shouldBeGreaterThan longer
    }
})

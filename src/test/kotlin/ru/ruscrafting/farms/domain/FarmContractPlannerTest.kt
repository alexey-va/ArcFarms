package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmContractPlannerTest : FunSpec({
    val commonA = FarmOrder("common_a", mapOf("WHEAT" to 100))
    val commonB = FarmOrder("common_b", mapOf("CARROTS" to 100))
    val rareA = FarmOrder(
        "rare_a",
        mapOf("POTATOES" to 400),
        rarity = FarmContractRarity.RARE,
    )
    val rareB = FarmOrder(
        "rare_b",
        mapOf("BEETROOTS" to 400),
        rarity = FarmContractRarity.RARE,
    )

    test("rare roll selects only the rare pool while ordinary shifts rotate common contracts") {
        val orders = listOf(commonA, commonB, rareA, rareB)

        FarmContractPlanner.select(orders, 20, 19, 0).id shouldBe "rare_a"
        FarmContractPlanner.select(orders, 20, 19, 1).id shouldBe "rare_b"
        FarmContractPlanner.select(orders, 20, 20, 0).id shouldBe "common_a"
        FarmContractPlanner.select(orders, 20, 99, 1).id shouldBe "common_b"
    }

    test("a zero rare chance never selects a rare contract") {
        val orders = listOf(commonA, rareA)

        FarmContractPlanner.select(orders, 0, 0, 99).id shouldBe "common_a"
    }

    test("cart milestones are monotonic quarters of the full order") {
        FarmContractPlanner.harvestMilestone(0, 320) shouldBe 0
        FarmContractPlanner.harvestMilestone(79, 320) shouldBe 0
        FarmContractPlanner.harvestMilestone(80, 320) shouldBe 1
        FarmContractPlanner.harvestMilestone(159, 320) shouldBe 1
        FarmContractPlanner.harvestMilestone(160, 320) shouldBe 2
        FarmContractPlanner.harvestMilestone(240, 320) shouldBe 3
        FarmContractPlanner.harvestMilestone(320, 320) shouldBe 4
        FarmContractPlanner.harvestMilestone(400, 320) shouldBe 4
    }

    test("harvest feedback checkpoints cover every ten percent without exceeding completion") {
        FarmContractPlanner.harvestCheckpoint(0, 1_000) shouldBe 0
        FarmContractPlanner.harvestCheckpoint(99, 1_000) shouldBe 0
        FarmContractPlanner.harvestCheckpoint(100, 1_000) shouldBe 1
        FarmContractPlanner.harvestCheckpoint(650, 1_000) shouldBe 6
        FarmContractPlanner.harvestCheckpoint(1_000, 1_000) shouldBe 10
        FarmContractPlanner.harvestCheckpoint(2_000, 1_000) shouldBe 10
    }
})

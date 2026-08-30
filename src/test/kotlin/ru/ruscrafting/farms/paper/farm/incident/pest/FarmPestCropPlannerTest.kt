package ru.ruscrafting.farms.paper.farm.incident.pest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.FarmPlotPosition
import java.util.UUID

class FarmPestCropPlannerTest : FunSpec({
    test("stale nearest beds do not consume a pest damage quota") {
        val pestId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val staleNearest = (0..7).map { x -> FarmPlotPosition("world", x, 64, 0) }
        val liveFarther = listOf(
            FarmPlotPosition("world", 8, 64, 0),
            FarmPlotPosition("world", 9, 64, 0),
        )
        val plans = listOf(PestCropCandidatePlan(pestId, staleNearest + liveFarther))

        val selected = FarmPestCropPlanner.select(
            plans = plans,
            eligible = liveFarther.toSet(),
            alreadyDamaged = emptySet(),
            totalLimit = 2,
            perPestLimit = 2,
        )

        selected shouldBe liveFarther.map { pestId to it }
    }

    test("overlapping pest plans never claim the same crop twice") {
        val firstPest = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondPest = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val shared = FarmPlotPosition("world", 5, 64, 5)
        val secondChoice = FarmPlotPosition("world", 6, 64, 5)
        val plans = listOf(
            PestCropCandidatePlan(firstPest, listOf(shared)),
            PestCropCandidatePlan(secondPest, listOf(shared, secondChoice)),
        )

        FarmPestCropPlanner.select(
            plans = plans,
            eligible = setOf(shared, secondChoice),
            alreadyDamaged = emptySet(),
            totalLimit = 2,
            perPestLimit = 1,
        ) shouldBe listOf(firstPest to shared, secondPest to secondChoice)
    }
})

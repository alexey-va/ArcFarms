package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class FarmWaterPlannerTest : FunSpec({
    val dry = FarmPlotPosition("world", 10, 64, 10)

    test("water source may be placed five blocks from a dry plot") {
        FarmWaterPlanner.canPlace(FarmPlotPosition("world", 15, 65, 10), listOf(dry), radius = 5) shouldBe true
    }

    test("water source outside the bounded flow radius is rejected") {
        FarmWaterPlanner.canPlace(FarmPlotPosition("world", 16, 65, 10), listOf(dry), radius = 5) shouldBe false
        FarmWaterPlanner.canPlace(FarmPlotPosition("other", 10, 65, 10), listOf(dry), radius = 5) shouldBe false
    }

    test("only dry plots actually reached by tracked water are completed") {
        val second = FarmPlotPosition("world", 11, 64, 10)
        val unreached = FarmPlotPosition("world", 12, 64, 10)
        val reached = FarmWaterPlanner.reachedPlots(
            listOf(dry, second, unreached),
            listOf(
                FarmPlotPosition("world", 10, 65, 10),
                FarmPlotPosition("world", 11, 65, 10),
                FarmPlotPosition("other", 12, 65, 10),
            ),
        )

        reached.toList().shouldContainExactly(dry, second)
    }

    test("a valid source immediately reaches every dry plot within five blocks") {
        val reached = FarmWaterPlanner.reachedPlotsWithinRadius(
            FarmPlotPosition("world", 10, 65, 10),
            listOf(dry, FarmPlotPosition("world", 13, 64, 14), FarmPlotPosition("world", 16, 64, 10)),
            radius = 5,
        )

        reached.toList().shouldContainExactly(dry, FarmPlotPosition("world", 13, 64, 14))
    }

    test("watering reports only fully completed connected drought patches") {
        val firstPatch = listOf(dry, FarmPlotPosition("world", 11, 64, 10))
        val secondPatch = listOf(FarmPlotPosition("world", 20, 64, 20), FarmPlotPosition("world", 21, 64, 20))

        FarmWaterPlanner.completedPatchCount(firstPatch + secondPatch, firstPatch) shouldBe 1
        FarmWaterPlanner.completedPatchCount(firstPatch + secondPatch, listOf(dry)) shouldBe 0
    }
})

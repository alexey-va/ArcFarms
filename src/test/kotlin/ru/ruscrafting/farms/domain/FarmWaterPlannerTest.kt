package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class FarmWaterPlannerTest : FunSpec({
    val dry = FarmPlotPosition("world", 10, 64, 10)

    test("water source may be placed five blocks from a dry plot") {
        FarmWaterPlanner.canPlace(FarmPlotPosition("world", 15, 65, 10), listOf(dry), radius = 7) shouldBe true
    }

    test("water source outside the bounded flow radius is rejected") {
        FarmWaterPlanner.canPlace(FarmPlotPosition("world", 18, 65, 10), listOf(dry), radius = 7) shouldBe false
        FarmWaterPlanner.canPlace(FarmPlotPosition("other", 10, 65, 10), listOf(dry), radius = 7) shouldBe false
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
})

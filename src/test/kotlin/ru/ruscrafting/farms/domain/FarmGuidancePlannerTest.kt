package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class FarmGuidancePlannerTest : FunSpec({
    test("planting highlights every missing bed only after the remaining set becomes small") {
        val plots = (0 until 12).map { index -> FarmPlotPosition("world", index, 64, 0) }

        FarmGuidancePlanner.individualMissingPlots(plots, threshold = 10) shouldContainExactly emptyList()
        FarmGuidancePlanner.individualMissingPlots(plots.takeLast(10), threshold = 10) shouldContainExactly plots.takeLast(10)
        FarmGuidancePlanner.individualMissingPlots(plots.takeLast(2).reversed(), threshold = 10) shouldContainExactly plots.takeLast(2)
    }

    test("delivery anchor varies inside the nearest safe candidates and ignores distant positions") {
        val candidates = listOf(
            FarmDeliveryPosition("world", 1.5, 65.0, 0.5),
            FarmDeliveryPosition("world", 2.5, 65.0, 0.5),
            FarmDeliveryPosition("world", 3.5, 65.0, 0.5),
            FarmDeliveryPosition("world", 80.5, 65.0, 0.5),
        )

        FarmDeliveryPlanner.selectAnchor(candidates, 0.5, 0.5, selectionIndex = 0) shouldBe candidates[0]
        FarmDeliveryPlanner.selectAnchor(candidates, 0.5, 0.5, selectionIndex = 1) shouldBe candidates[1]
        FarmDeliveryPlanner.selectAnchor(candidates, 0.5, 0.5, selectionIndex = 2) shouldBe candidates[2]
    }
})

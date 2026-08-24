package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmCarePlannerTest : FunSpec({
    val field = (0 until 12).flatMap { x ->
        (0 until 10).map { z -> FarmPlotPosition("world", x, 64, z) }
    }

    test("care targets are spread across a large field instead of clustering at its center") {
        val selected = FarmCarePlanner.spread(field, 5, selectionIndex = 7)

        selected.size shouldBe 5
        selected.distinct().size shouldBe 5
        selected.all { it in field } shouldBe true
        val spanX = selected.maxOf(FarmPlotPosition::x) - selected.minOf(FarmPlotPosition::x)
        val spanZ = selected.maxOf(FarmPlotPosition::z) - selected.minOf(FarmPlotPosition::z)
        (spanX >= 8) shouldBe true
        (spanZ >= 7) shouldBe true
    }

    test("storm cover anchors follow the actual field corners even on an uneven patch") {
        val uneven = field.filterNot { it.x > 8 && it.z > 7 }
        val corners = FarmCarePlanner.corners(uneven)

        corners.size shouldBe 4
        corners.all { it in uneven } shouldBe true
        corners.minOf(FarmPlotPosition::x) shouldBe 0
        corners.maxOf(FarmPlotPosition::x) shouldBe 11
        corners.minOf(FarmPlotPosition::z) shouldBe 0
        corners.maxOf(FarmPlotPosition::z) shouldBe 9
    }

    test("small fields degrade to available unique targets") {
        val tiny = field.take(2)
        FarmCarePlanner.spread(tiny, 8, 0) shouldBe tiny
        FarmCarePlanner.corners(tiny) shouldBe tiny
    }

    test("moving underground target chooses space away from occupied mounds") {
        val occupied = listOf(FarmPointPosition("world", 0.5, 65.0, 0.5))
        val relocated = requireNotNull(FarmCarePlanner.relocate(field, occupied, selectionIndex = 3))

        (relocated.x >= 10) shouldBe true
        (relocated.z >= 8) shouldBe true
    }
})

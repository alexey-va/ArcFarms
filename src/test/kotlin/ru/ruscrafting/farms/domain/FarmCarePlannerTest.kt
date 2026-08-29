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

    test("physical care objects scale by workers and stop at the configured cap") {
        FarmCarePlanner.targetCount(0, 15, 45, 200) shouldBe 15
        FarmCarePlanner.targetCount(2, 15, 45, 200) shouldBe 30
        FarmCarePlanner.targetCount(8, 15, 45, 200) shouldBe 45
        FarmCarePlanner.targetCount(8, 15, 45, 12) shouldBe 12
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

    test("mole candidate search accepts the configured ninety six probes") {
        val largeField = (0 until 20).flatMap { x ->
            (0 until 20).map { z -> FarmPlotPosition("world", x, 64, z) }
        }

        FarmCarePlanner.spread(largeField, 96, selectionIndex = 17).size shouldBe 96
    }

    test("moving underground target chooses space away from occupied mounds") {
        val occupied = listOf(FarmPointPosition("world", 0.5, 65.0, 0.5))
        val relocated = requireNotNull(FarmCarePlanner.relocate(field, occupied, selectionIndex = 3))

        (relocated.x >= 10) shouldBe true
        (relocated.z >= 8) shouldBe true
    }

    test("disease frontier grows only around an existing outbreak") {
        val source = FarmPointPosition("world", 5.5, 65.0, 5.5)
        val selected = requireNotNull(FarmCarePlanner.diseaseFrontier(field, listOf(source), 2.0, 3))

        val dx = selected.x + 0.5 - source.x
        val dz = selected.z + 0.5 - source.z
        (dx * dx + dz * dz <= 4.0) shouldBe true
        selected shouldBe field.first { it == selected }
        selected shouldBe FarmCarePlanner.diseaseFrontier(field, listOf(source), 2.0, 3)
    }

    test("scene anchor changes traversal order without moving field targets") {
        val targets = listOf(
            FarmCareTarget(0, FarmCareRole.VALVE, FarmPointPosition("world", 0.5, 65.0, 0.5)),
            FarmCareTarget(1, FarmCareRole.VALVE, FarmPointPosition("world", 10.5, 65.0, 0.5)),
            FarmCareTarget(2, FarmCareRole.VALVE, FarmPointPosition("world", 10.5, 65.0, 10.5)),
        )

        val oriented = FarmCarePlanner.orient(targets, FarmPointPosition("world", 12.0, 65.0, 1.0))

        oriented.map(FarmCareTarget::position).toSet() shouldBe targets.map(FarmCareTarget::position).toSet()
        oriented.map(FarmCareTarget::id) shouldBe listOf(0, 1, 2)
        oriented.first().position shouldBe targets[1].position
        oriented[1].position shouldBe targets[0].position
    }

    test("scene without an anchor keeps its procedural order") {
        val targets = field.take(3).mapIndexed { index, plot ->
            FarmCareTarget(
                index,
                FarmCareRole.SCARECROW,
                FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.0, plot.z + 0.5),
            )
        }

        FarmCarePlanner.orient(targets, null) shouldBe targets
    }

    test("rebuilding an oriented scene preserves progress by field position") {
        val first = FarmPointPosition("world", 0.5, 65.0, 0.5)
        val second = FarmPointPosition("world", 10.5, 65.0, 0.5)
        val previous = listOf(
            FarmCareTarget(0, FarmCareRole.VALVE, first, progress = 1),
            FarmCareTarget(1, FarmCareRole.VALVE, second),
        )
        val rebuilt = listOf(
            FarmCareTarget(0, FarmCareRole.VALVE, second),
            FarmCareTarget(1, FarmCareRole.VALVE, first),
        )

        FarmCarePlanner.preserveProgress(previous, rebuilt).map(FarmCareTarget::progress) shouldBe listOf(0, 1)
    }

    test("moving a unique hive preserves its completed state") {
        val previous = listOf(
            FarmCareTarget(0, FarmCareRole.HIVE, FarmPointPosition("world", 0.5, 65.0, 0.5), progress = 1),
        )
        val rebuilt = listOf(
            FarmCareTarget(0, FarmCareRole.HIVE, FarmPointPosition("world", 20.5, 65.0, 20.5)),
        )

        FarmCarePlanner.preserveProgress(previous, rebuilt).single().progress shouldBe 1
    }
})

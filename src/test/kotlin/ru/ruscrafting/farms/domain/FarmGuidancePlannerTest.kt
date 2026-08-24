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

    test("farm objects stay away from their objective and within reach of participants") {
        val candidates = (1..40).map { x -> FarmDeliveryPosition("world", x + 0.5, 65.0, 0.5) }

        val selected = FarmDeliveryPlanner.selectTargets(
            candidates = candidates,
            objectiveX = 0.5,
            objectiveZ = 0.5,
            participants = listOf(18.5 to 0.5),
            minimumObjectiveDistance = 10.0,
            maximumParticipantDistance = 12.0,
            targetCount = 4,
            selectionIndex = 3,
        )

        selected.size shouldBe 4
        selected.all { it.x >= 10.5 } shouldBe true
        selected.all { kotlin.math.abs(it.x - 18.5) <= 12.0 } shouldBe true
        (selected.maxOf(FarmDeliveryPosition::x) - selected.minOf(FarmDeliveryPosition::x) >= 8.0) shouldBe true
    }

    test("dynamic farm targets keep five blocks apart when the field has enough safe ground") {
        val candidates = (0..20).flatMap { x ->
            (0..20).map { z -> FarmDeliveryPosition("world", x + 0.5, 65.0, z + 0.5) }
        }

        val selected = FarmDeliveryPlanner.selectTargets(
            candidates = candidates,
            objectiveX = 10.5,
            objectiveZ = 10.5,
            participants = listOf(10.5 to 10.5),
            minimumObjectiveDistance = 0.0,
            maximumParticipantDistance = 16.0,
            targetCount = 6,
            selectionIndex = 7,
            minimumTargetDistance = 5.0,
        )

        selected.size shouldBe 6
        selected.indices.all { left ->
            (left + 1 until selected.size).all { right ->
                val dx = selected[left].x - selected[right].x
                val dz = selected[left].z - selected[right].z
                dx * dx + dz * dz >= 25.0
            }
        } shouldBe true
    }

    test("farm object placement degrades to available safe ground in a tiny fixture") {
        val candidates = listOf(
            FarmDeliveryPosition("world", 1.5, 65.0, 0.5),
            FarmDeliveryPosition("world", 2.5, 65.0, 0.5),
        )

        FarmDeliveryPlanner.selectTargets(
            candidates,
            objectiveX = 0.5,
            objectiveZ = 0.5,
            participants = listOf(100.0 to 100.0),
            minimumObjectiveDistance = 10.0,
            maximumParticipantDistance = 3.0,
            targetCount = 4,
            selectionIndex = 0,
        ).toSet() shouldBe candidates.toSet()
    }
})

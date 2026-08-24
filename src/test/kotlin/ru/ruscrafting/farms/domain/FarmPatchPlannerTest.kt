package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class FarmPatchPlannerTest : FunSpec({
    test("planner returns a compact configurable patch from irrigated rows") {
        val candidates = buildList {
            for (x in 0 until 20) {
                for (z in listOf(0, 2, 4, 6, 8, 10)) add(FarmPlotPosition("world", x, 64, z))
            }
        }

        val selected = FarmPatchPlanner.select(candidates, FarmPlotPosition("world", 0, 65, 0), 100, 100)

        selected.size shouldBe 100
        selected.distinct().size shouldBe 100
        selected.all { it in candidates } shouldBe true
    }

    test("planner uses the largest available component when target size cannot be reached") {
        val small = (0 until 4).map { FarmPlotPosition("world", it, 64, 0) }
        val large = (20 until 37).map { FarmPlotPosition("world", it, 64, 0) }

        val selected = FarmPatchPlanner.select(small + large, FarmPlotPosition("world", 0, 64, 0), 100)

        selected shouldContainExactlyInAnyOrder large
    }

    test("planner does not bridge distant plots or another world") {
        val local = listOf(
            FarmPlotPosition("world", 0, 64, 0),
            FarmPlotPosition("world", 1, 64, 0),
        )
        val distant = listOf(
            FarmPlotPosition("world", 10, 64, 0),
            FarmPlotPosition("world", 11, 64, 0),
        )
        val otherWorld = FarmPlotPosition("other", 0, 64, 0)

        FarmPatchPlanner.select(local + distant + otherWorld, FarmPlotPosition("world", 0, 64, 0), 10)
            .shouldContainExactlyInAnyOrder(local)
    }

    test("planner prefers the nearest component when several can satisfy the target") {
        val nearby = (0 until 6).map { FarmPlotPosition("world", it, 64, 0) }
        val distant = (100 until 106).map { FarmPlotPosition("world", it, 64, 0) }

        val selected = FarmPatchPlanner.select(
            distant + nearby,
            FarmPlotPosition("world", 0, 64, 0),
            4,
        )

        selected.size shouldBe 4
        selected.all { it in nearby } shouldBe true
    }

    test("planner cycles through separate beds instead of always selecting the center bed") {
        val beds = listOf(0, 30, 60).map { start ->
            (start until start + 6).flatMap { x ->
                (0 until 4).map { z -> FarmPlotPosition("world", x, 64, z) }
            }
        }
        val candidates = beds.flatten()
        val anchor = FarmPlotPosition("world", 30, 64, 0)

        val selectedBeds = (0L..2L).map { sequence ->
            FarmPatchPlanner.select(candidates, anchor, targetSize = 20, maxSize = 30, selectionIndex = sequence).toSet()
        }

        selectedBeds.toSet().size shouldBe 3
        selectedBeds.forEach { selected -> beds.any { selected == it.toSet() } shouldBe true }
    }

    test("planner expands a selected bed to its whole same-height component within the cap") {
        val bed = (0 until 12).flatMap { x ->
            (0 until 10).map { z -> FarmPlotPosition("world", x, 64, z) }
        }
        val upperTerrace = (0 until 12).map { x -> FarmPlotPosition("world", x, 65, 0) }

        val selected = FarmPatchPlanner.select(
            bed + upperTerrace,
            FarmPlotPosition("world", 0, 64, 0),
            targetSize = 100,
            maxSize = 160,
        )

        selected.shouldContainExactlyInAnyOrder(bed)
    }

    test("planner bounds a huge connected field instead of absorbing thousands of plots") {
        val hugeField = (0 until 50).flatMap { x ->
            (0 until 20).map { z -> FarmPlotPosition("world", x, 64, z) }
        }

        val selected = FarmPatchPlanner.select(
            hugeField,
            FarmPlotPosition("world", 25, 64, 10),
            targetSize = 100,
            maxSize = 160,
        )

        selected.size shouldBe 160
        selected.all { it.y == 64 && it in hugeField } shouldBe true
    }

    test("planner can allocate a large bounded field for machinery") {
        val hugeField = (0 until 64).flatMap { x ->
            (0 until 32).map { z -> FarmPlotPosition("world", x, 64, z) }
        }

        val selected = FarmPatchPlanner.select(
            hugeField,
            FarmPlotPosition("world", 32, 64, 16),
            targetSize = 640,
            maxSize = 1_024,
        )

        selected.size shouldBe 1_024
        selected.all(hugeField::contains) shouldBe true
    }

    test("planner rotates bounded patches across one huge connected field") {
        val hugeField = (0 until 50).flatMap { x ->
            (0 until 20).map { z -> FarmPlotPosition("world", x, 64, z) }
        }
        val anchor = FarmPlotPosition("world", 25, 64, 10)

        val first = FarmPatchPlanner.select(hugeField, anchor, 100, 160, selectionIndex = 0).toSet()
        val second = FarmPatchPlanner.select(hugeField, anchor, 100, 160, selectionIndex = 1).toSet()

        first.size shouldBe 160
        second.size shouldBe 160
        (first == second) shouldBe false
        (first.intersect(second).size < 40) shouldBe true
    }

    test("recovery expands an old partial patch without resetting completed plots") {
        val wholeBed = (0 until 12).flatMap { x ->
            (0 until 10).map { z -> FarmPlotPosition("world", x, 64, z) }
        }
        val oldPatch = wholeBed.filter { it.x < 10 }

        val expanded = FarmPatchPlanner.expand(wholeBed, oldPatch, maxSize = 160)

        expanded.shouldContainExactlyInAnyOrder(wholeBed)
        expanded.containsAll(oldPatch) shouldBe true
    }

    test("planner bridges one-block irrigation but not a wider path or another height") {
        val left = (0 until 4).flatMap { x -> (0 until 4).map { z -> FarmPlotPosition("world", x, 64, z) } }
        val right = (7 until 11).flatMap { x -> (0 until 4).map { z -> FarmPlotPosition("world", x, 64, z) } }
        val raised = (0 until 4).flatMap { x -> (0 until 4).map { z -> FarmPlotPosition("world", x, 65, z) } }

        val selected = FarmPatchPlanner.select(left + right + raised, left.first(), 12, 20)

        selected.shouldContainExactlyInAnyOrder(left)
    }

    test("planner returns no patch when no usable plots were discovered") {
        FarmPatchPlanner.select(emptyList(), FarmPlotPosition("world", 0, 64, 0), 100) shouldBe emptyList()
    }

    test("drought planner creates several distant patches with bounded sizes") {
        val candidates = listOf(0, 100, 200).flatMap { start ->
            (start until start + 8).map { x -> FarmPlotPosition("world", x, 64, 0) }
        }

        val patches = FarmDroughtPlanner.select(candidates, candidates.first(), targetSize = 12, patchCount = 3)

        patches.map(List<FarmPlotPosition>::size) shouldBe listOf(4, 4, 4)
        patches.flatten().distinct().size shouldBe 12
        val centers = patches.map { patch -> patch.map(FarmPlotPosition::x).average() }.sorted()
        (centers[0] < 10 && centers[1] in 100.0..110.0 && centers[2] > 190) shouldBe true
    }

    test("drought planner degrades to available beds without inventing targets") {
        val candidates = listOf(
            FarmPlotPosition("world", 0, 64, 0),
            FarmPlotPosition("world", 10, 64, 0),
        )

        val patches = FarmDroughtPlanner.select(candidates, candidates.first(), targetSize = 12, patchCount = 3)

        patches.flatten().shouldContainExactlyInAnyOrder(candidates)
        patches.all { it.isNotEmpty() } shouldBe true
    }
})

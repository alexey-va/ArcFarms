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

        val selected = FarmPatchPlanner.select(candidates, FarmPlotPosition("world", 0, 65, 0), 100)

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

    test("planner returns no patch when no usable plots were discovered") {
        FarmPatchPlanner.select(emptyList(), FarmPlotPosition("world", 0, 64, 0), 100) shouldBe emptyList()
    }
})

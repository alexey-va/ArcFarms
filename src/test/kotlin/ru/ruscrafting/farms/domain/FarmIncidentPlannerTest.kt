package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmIncidentPlannerTest : FunSpec({
    val field = (0 until 60).flatMap { x ->
        (0 until 20).map { z -> FarmPlotPosition("world", x, 64, z) }
    }

    test("incident centers rotate and stay dispersed across the whole field") {
        val first = FarmIncidentPlanner.dispersedCenters(field, count = 3, selectionIndex = 1)
        val second = FarmIncidentPlanner.dispersedCenters(field, count = 3, selectionIndex = 2)

        first.size shouldBe 3
        second.size shouldBe 3
        (first.toSet() == second.toSet()) shouldBe false
        (first.minOf { a -> first.filterNot { it == a }.minOf { b -> distance(a, b) } } >= 300L) shouldBe true
    }

    test("drought planner creates one bounded non-overlapping group per requested patch") {
        val patches = FarmIncidentPlanner.droughtPatches(field, targetSize = 40, patchCount = 3, selectionIndex = 17)

        patches.size shouldBe 3
        patches.sumOf(List<FarmPlotPosition>::size) shouldBe 40
        patches.flatten().distinct().size shouldBe 40
        patches.all { it.isNotEmpty() } shouldBe true
    }

    test("drought grows outward from its existing patches without replacing them") {
        val initial = FarmIncidentPlanner.droughtPatches(field, targetSize = 9, patchCount = 3, selectionIndex = 17).flatten().toSet()
        val grown = FarmIncidentPlanner.growDroughtPatches(field, initial, targetSize = 18, patchCount = 3, selectionIndex = 17)

        grown.size shouldBe 18
        grown.containsAll(initial) shouldBe true
        (grown.maxOf { candidate -> initial.minOf { selected -> distance(candidate, selected) } } <= 1L) shouldBe true
    }

    test("drought spawn limit expands by bounded timed steps") {
        FarmIncidentPlanner.droughtSpawnLimit(40, 10, 5, 3_000, 1_000, 1_000) shouldBe 10
        FarmIncidentPlanner.droughtSpawnLimit(40, 10, 5, 3_000, 1_000, 7_100) shouldBe 20
        FarmIncidentPlanner.droughtSpawnLimit(40, 10, 5, 3_000, 1_000, 100_000) shouldBe 40
    }
})

private fun distance(first: FarmPlotPosition, second: FarmPlotPosition): Long {
    val dx = first.x.toLong() - second.x
    val dz = first.z.toLong() - second.z
    return dx * dx + dz * dz
}

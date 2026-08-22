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
})

private fun distance(first: FarmPlotPosition, second: FarmPlotPosition): Long {
    val dx = first.x.toLong() - second.x
    val dz = first.z.toLong() - second.z
    return dx * dx + dz * dz
}

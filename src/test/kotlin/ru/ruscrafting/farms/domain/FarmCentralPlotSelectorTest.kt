package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class FarmCentralPlotSelectorTest : FunSpec({
    val field = (0..60).flatMap { x ->
        (0..20).map { z -> FarmPlotPosition("world", x, 64, z) }
    }

    test("interest points prefer the field interior while retaining spacing") {
        val selected = FarmCentralPlotSelector.select(field, count = 5, minimumSpacing = 8.0, selectionIndex = 17L)

        selected.size shouldBe 5
        selected.distinct().size shouldBe 5
        selected.all { abs(it.x - 30) <= 18 && abs(it.z - 10) <= 10 } shouldBe true
        selected.indices.all { first ->
            (first + 1 until selected.size).all { second -> distanceSquared(selected[first], selected[second]) >= 64.0 }
        } shouldBe true
    }

    test("outer beds remain a fallback when the interior cannot satisfy spacing") {
        val candidates = listOf(
            FarmPlotPosition("world", 9, 64, 10),
            FarmPlotPosition("world", 10, 64, 10),
            FarmPlotPosition("world", 11, 64, 10),
            FarmPlotPosition("world", 0, 64, 10),
            FarmPlotPosition("world", 20, 64, 10),
        )

        val selected = FarmCentralPlotSelector.select(candidates, count = 3, minimumSpacing = 8.0, selectionIndex = 3L)

        selected.size shouldBe 3
        selected.any { it.x == 0 } shouldBe true
        selected.any { it.x == 20 } shouldBe true
    }

    test("selection remains deterministic and rotates its central seed") {
        val first = FarmCentralPlotSelector.select(field, count = 5, minimumSpacing = 8.0, selectionIndex = 17L)
        val repeated = FarmCentralPlotSelector.select(field, count = 5, minimumSpacing = 8.0, selectionIndex = 17L)
        val next = FarmCentralPlotSelector.select(field, count = 5, minimumSpacing = 8.0, selectionIndex = 18L)

        repeated shouldBe first
        (next.toSet() == first.toSet()) shouldBe false
    }
})

private fun distanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Double {
    val dx = (first.x - second.x).toDouble()
    val dz = (first.z - second.z).toDouble()
    return dx * dx + dz * dz
}

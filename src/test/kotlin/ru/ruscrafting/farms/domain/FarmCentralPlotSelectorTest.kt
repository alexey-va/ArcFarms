package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import kotlin.math.hypot

class FarmCentralPlotSelectorTest : FunSpec({
    val field = (0..60).flatMap { x ->
        (0..20).map { z -> FarmPlotPosition("world", x, 64, z) }
    }

    test("interest points form a varied middle ring instead of clustering at the center") {
        val selected = FarmCentralPlotSelector.select(field, count = 5, minimumSpacing = 8.0, selectionIndex = 17L)

        selected.size shouldBe 5
        selected.distinct().size shouldBe 5
        selected.none { normalizedRadius(it, field) < 0.30 } shouldBe true
        selected.count { normalizedRadius(it, field) <= 0.90 } shouldBeGreaterThanOrEqual 4
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

    test("successive layouts mostly use the middle ring but occasionally explore wider beds") {
        val layouts = (0L until 54L).map { selectionIndex ->
            FarmCentralPlotSelector.select(field, count = 3, minimumSpacing = 12.0, selectionIndex = selectionIndex)
        }
        val radii = layouts.flatten().map { normalizedRadius(it, field) }

        layouts.all { selected ->
            selected.indices.all { first ->
                (first + 1 until selected.size).all { second -> distanceSquared(selected[first], selected[second]) >= 144.0 }
            }
        } shouldBe true
        (radii.count { it in 0.40..0.84 } >= radii.size * 2 / 3) shouldBe true
        radii.any { it > 0.90 } shouldBe true
        radii.none { it < 0.20 } shouldBe true
    }

    test("missing middle-ring beds fall back to every valid part of the field") {
        val sparse = listOf(
            FarmPlotPosition("world", 0, 64, 0),
            FarmPlotPosition("world", 0, 64, 20),
            FarmPlotPosition("world", 30, 64, 10),
            FarmPlotPosition("world", 60, 64, 0),
            FarmPlotPosition("world", 60, 64, 20),
        )

        val selected = FarmCentralPlotSelector.select(sparse, count = 4, minimumSpacing = 12.0, selectionIndex = 41L)

        selected.size shouldBe 4
        selected.distinct().size shouldBe 4
        selected.any { it.x == 0 } shouldBe true
        selected.any { it.x == 60 } shouldBe true
    }
})

private fun distanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Double {
    val dx = (first.x - second.x).toDouble()
    val dz = (first.z - second.z).toDouble()
    return dx * dx + dz * dz
}

private fun normalizedRadius(position: FarmPlotPosition, field: Collection<FarmPlotPosition>): Double {
    val centerX = (field.minOf(FarmPlotPosition::x) + field.maxOf(FarmPlotPosition::x)) / 2.0
    val centerZ = (field.minOf(FarmPlotPosition::z) + field.maxOf(FarmPlotPosition::z)) / 2.0
    val halfWidth = ((field.maxOf(FarmPlotPosition::x) - field.minOf(FarmPlotPosition::x)) / 2.0).coerceAtLeast(1.0)
    val halfDepth = ((field.maxOf(FarmPlotPosition::z) - field.minOf(FarmPlotPosition::z)) / 2.0).coerceAtLeast(1.0)
    return hypot((position.x - centerX) / halfWidth, (position.z - centerZ) / halfDepth)
}

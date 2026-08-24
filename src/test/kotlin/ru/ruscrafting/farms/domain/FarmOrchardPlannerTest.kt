package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmOrchardPlannerTest : FunSpec({
    val canopy = (0..24 step 4).flatMap { x ->
        (0..24 step 4).map { z -> FarmPlotPosition("world", x, 72, z) }
    }

    test("apple anchors are deterministic and stay separated across the orchard") {
        val first = FarmOrchardPlanner.select(canopy, targetCount = 8, minimumSpacing = 6.0, selectionIndex = 17)
        val repeated = FarmOrchardPlanner.select(canopy, targetCount = 8, minimumSpacing = 6.0, selectionIndex = 17)

        first shouldBe repeated
        first.size shouldBe 8
        first.distinct().size shouldBe 8
        first.forEachIndexed { index, anchor ->
            first.drop(index + 1).all { other ->
                val dx = anchor.x - other.x
                val dz = anchor.z - other.z
                dx * dx + dz * dz >= 36
            } shouldBe true
        }
    }

    test("small orchards degrade to available spaced anchors") {
        val sparse = listOf(
            FarmPlotPosition("world", 0, 72, 0),
            FarmPlotPosition("world", 1, 72, 0),
            FarmPlotPosition("world", 10, 72, 0),
        )

        val selected = FarmOrchardPlanner.select(sparse, targetCount = 8, minimumSpacing = 4.0, selectionIndex = 0)

        selected shouldBe listOf(sparse[0], sparse[2])
    }

    test("selection index rotates the first orchard anchor") {
        FarmOrchardPlanner.select(canopy, 3, 0.0, 0).first() shouldBe canopy.first()
        FarmOrchardPlanner.select(canopy, 3, 0.0, 1).first() shouldBe canopy[1]
    }
})

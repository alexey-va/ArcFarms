package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmOrchardPlannerTest : FunSpec({
    val canopy = (0..24 step 4).flatMap { x ->
        (0..24 step 4).map { z -> FarmPlotPosition("world", x, 72, z) }
    }

    test("apple anchors are deterministic and stay separated across the orchard") {
        val first = FarmOrchardPlanner.select(canopy, placementCount = 8, minimumSpacing = 6.0, selectionIndex = 17)
        val repeated = FarmOrchardPlanner.select(canopy, placementCount = 8, minimumSpacing = 6.0, selectionIndex = 17)

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

    test("small orchards use every available anchor when strict spacing cannot fill the scene") {
        val sparse = listOf(
            FarmPlotPosition("world", 0, 72, 0),
            FarmPlotPosition("world", 1, 72, 0),
            FarmPlotPosition("world", 10, 72, 0),
        )

        val selected = FarmOrchardPlanner.select(sparse, placementCount = 8, minimumSpacing = 4.0, selectionIndex = 0)

        selected.toSet() shouldBe sparse.toSet()
        selected.size shouldBe 3
    }

    test("selection index randomizes orchard placement deterministically") {
        FarmOrchardPlanner.select(canopy, 12, 0.0, 0) shouldBe FarmOrchardPlanner.select(canopy, 12, 0.0, 0)
        (FarmOrchardPlanner.select(canopy, 12, 0.0, 0) == FarmOrchardPlanner.select(canopy, 12, 0.0, 1)) shouldBe false
    }

    test("large orchards can expose two hundred random apples") {
        val large = (0 until 20).flatMap { x ->
            (0 until 20).map { z -> FarmPlotPosition("world", x * 2, 72, z * 2) }
        }

        FarmOrchardPlanner.select(large, 200, 2.0, 91).size shouldBe 200
    }
})

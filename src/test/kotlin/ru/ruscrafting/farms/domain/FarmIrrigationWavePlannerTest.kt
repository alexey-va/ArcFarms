package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class FarmIrrigationWavePlannerTest : FunSpec({
    test("groups managed beds into deterministic circular fronts") {
        val source = FarmPointPosition("world", 0.5, 65.0, 0.5)
        val center = FarmPlotPosition("world", 0, 64, 0)
        val nearEast = FarmPlotPosition("world", 1, 64, 0)
        val nearNorth = FarmPlotPosition("world", 0, 64, 1)
        val farEast = FarmPlotPosition("world", 3, 64, 0)

        val rings = FarmIrrigationWavePlanner.rings(
            listOf(farEast, nearNorth, center, nearEast),
            source,
            ringWidth = 1.25,
        )

        rings.map(FarmIrrigationRing::index) shouldContainExactly listOf(0, 2)
        rings[0].plots shouldContainExactly listOf(center, nearEast, nearNorth)
        rings[1].plots shouldContainExactly listOf(farEast)
        rings[1].radius shouldBe 3.75
    }

    test("rejects a non-positive ring width") {
        runCatching {
            FarmIrrigationWavePlanner.rings(emptyList(), FarmPointPosition("world", 0.0, 0.0, 0.0), 0.0)
        }.isFailure shouldBe true
    }
})

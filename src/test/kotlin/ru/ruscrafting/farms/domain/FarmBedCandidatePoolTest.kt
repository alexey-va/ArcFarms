package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmBedCandidatePoolTest : FunSpec({
    test("local discovery supplements the full indexed farm instead of replacing it") {
        val indexed = (0 until 100).map { FarmPlotPosition("world", it, 64, 0) }
        val local = indexed.take(2) + FarmPlotPosition("world", 100, 64, 0)

        val candidates = FarmBedCandidatePool.merge(indexed, local) { it.x != 37 }

        candidates.size shouldBe 100
        (FarmPlotPosition("world", 99, 64, 0) in candidates) shouldBe true
        (FarmPlotPosition("world", 100, 64, 0) in candidates) shouldBe true
        (FarmPlotPosition("world", 37, 64, 0) in candidates) shouldBe false
    }
})

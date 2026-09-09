package ru.ruscrafting.farms.paper.farm.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.FarmPlotPosition

class FarmUndergroundEntrySelectionTest : FunSpec({
    test("shared selection keeps underground entries away from the field edge") {
        val beds = buildList {
            for (x in 0..40) for (z in 0..40) add(FarmPlotPosition("world", x, 64, z))
        }

        val selected = FarmUndergroundEntrySelection.candidates(
            beds = beds,
            minimumBoundaryDistance = 10,
            candidateAttempts = 12,
            salt = 41L,
            hasRegionClearance = { plot, distance -> plot.x in distance..40 - distance && plot.z in distance..40 - distance },
        )

        selected.isNotEmpty() shouldBe true
        selected.all { it.x in 15.5..25.5 && it.z in 15.5..25.5 } shouldBe true
    }
})

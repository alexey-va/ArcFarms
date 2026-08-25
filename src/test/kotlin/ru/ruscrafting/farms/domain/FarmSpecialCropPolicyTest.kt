package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmSpecialCropPolicyTest : FunSpec({
    val planned = FarmPlotPosition("world", 1, 64, 1)
    val managedElsewhere = FarmPlotPosition("world", 40, 64, 40)
    val decorative = FarmPlotPosition("world", 41, 64, 40)

    test("rush delivery accepts any managed crop bed instead of a hidden planned subset") {
        FarmSpecialCropPolicy.isEligible(
            FarmIncidentType.MARKET,
            managedElsewhere,
            plannedPlots = setOf(planned),
            managedPlots = setOf(planned, managedElsewhere),
        ) shouldBe true
        FarmSpecialCropPolicy.isEligible(
            FarmIncidentType.MARKET,
            decorative,
            plannedPlots = setOf(planned),
            managedPlots = setOf(planned, managedElsewhere),
        ) shouldBe false
    }

    test("night shift remains limited to its explicitly highlighted crops") {
        FarmSpecialCropPolicy.isEligible(
            FarmIncidentType.NIGHT_SHIFT,
            managedElsewhere,
            plannedPlots = setOf(planned),
            managedPlots = setOf(planned, managedElsewhere),
        ) shouldBe false
        FarmSpecialCropPolicy.isEligible(
            FarmIncidentType.NIGHT_SHIFT,
            planned,
            plannedPlots = setOf(planned),
            managedPlots = setOf(planned, managedElsewhere),
        ) shouldBe true
    }
})

package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmIncidentRecoveryTest : FunSpec({
    test("unloaded or failed crop repairs remain durable for a later retry") {
        val restoredDrought = FarmPlotPosition("world", 1, 63, 1)
        val pendingDrought = FarmPlotPosition("world", 2, 63, 1)
        val restoredPest = FarmCropDamage(FarmPlotPosition("world", 3, 63, 1), "WHEAT")
        val pendingPest = FarmCropDamage(FarmPlotPosition("world", 4, 63, 1), "CARROTS")
        val state = FarmShiftState(
            droughtDamagedPlots = setOf(restoredDrought, pendingDrought),
            pestDamagedCrops = listOf(restoredPest, pendingPest),
        )

        val recovered = FarmIncidentRecovery.recover(
            state,
            restoreDrought = { it == restoredDrought },
            restorePest = { it == restoredPest },
        )

        recovered.droughtDamagedPlots shouldBe setOf(pendingDrought)
        recovered.pestDamagedCrops shouldBe listOf(pendingPest)
        FarmIncidentRecovery.pending(recovered) shouldBe true
    }

    test("recovery is complete only after every damaged crop is repaired") {
        val plot = FarmPlotPosition("world", 1, 63, 1)
        val recovered = FarmIncidentRecovery.recover(
            FarmShiftState(droughtDamagedPlots = setOf(plot)),
            restoreDrought = { true },
            restorePest = { true },
        )

        FarmIncidentRecovery.pending(recovered) shouldBe false
    }
})

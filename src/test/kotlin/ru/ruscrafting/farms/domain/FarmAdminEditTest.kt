package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmAdminEditTest : FunSpec({
    test("admin plot deletion removes every recovery reference without resetting remaining progress") {
        val removed = FarmPlotPosition("world", 1, 63, 1)
        val retained = FarmPlotPosition("world", 2, 63, 1)
        val result = FarmAdminEdit.removePlot(
            FarmShiftState(
                phase = FarmPhase.PLANTING,
                sequence = 4,
                orderId = "order",
                progress = mapOf("WHEAT" to 0),
                preparationPatch = listOf(removed, retained),
                preparationCrop = "WHEAT",
                preparationReleased = true,
                tilledPlots = setOf(removed, retained),
                plantedPlots = setOf(removed),
                preparationProgress = 2,
                plantingProgress = 1,
                preparationRequired = 2,
                droughtPlots = setOf(removed),
                droughtDamagedPlots = setOf(removed),
                pestNests = listOf(FarmPestNest(removed, health = 2)),
                pestDamagedCrops = listOf(FarmCropDamage(removed, "WHEAT")),
            ),
            removed,
        )

        result.state.phase shouldBe FarmPhase.PLANTING
        result.state.preparationPatch shouldBe listOf(retained)
        result.state.tilledPlots shouldBe setOf(retained)
        result.state.plantedPlots shouldBe emptySet()
        result.state.preparationRequired shouldBe 1
        result.state.droughtPlots shouldBe emptySet()
        result.state.droughtDamagedPlots shouldBe emptySet()
        result.state.pestNests shouldBe emptyList()
        result.state.pestDamagedCrops shouldBe emptyList()
        result.pestNestRemoved shouldBe true
    }

    test("removing the final tracked bed intentionally retires the active shift") {
        val plot = FarmPlotPosition("world", 1, 63, 1)
        val result = FarmAdminEdit.removePlot(
            FarmShiftState(
                phase = FarmPhase.PREPARATION,
                sequence = 9,
                orderId = "order",
                progress = mapOf("WHEAT" to 0),
                preparationPatch = listOf(plot),
                preparationCrop = "WHEAT",
                preparationRequired = 1,
            ),
            plot,
        )

        result.state shouldBe FarmShiftState(sequence = 9)
        result.shiftRetired shouldBe true
    }

    test("retiring the final bed preserves off-patch incident recovery") {
        val removed = FarmPlotPosition("world", 1, 63, 1)
        val damaged = FarmPlotPosition("world", 10, 63, 10)
        val result = FarmAdminEdit.removePlot(
            FarmShiftState(
                phase = FarmPhase.PREPARATION,
                sequence = 9,
                orderId = "order",
                progress = mapOf("WHEAT" to 0),
                preparationPatch = listOf(removed),
                preparationCrop = "WHEAT",
                preparationRequired = 1,
                droughtDamagedPlots = setOf(damaged),
            ),
            removed,
        )

        result.state.phase shouldBe FarmPhase.COOLDOWN
        result.state.droughtDamagedPlots shouldBe setOf(damaged)
        result.state.cooldownEndsAt shouldBe 1
        result.state.outcome shouldBe ShiftOutcome.COMPLETED
        result.shiftRetired shouldBe true
    }
})

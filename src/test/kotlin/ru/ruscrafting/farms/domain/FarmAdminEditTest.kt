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

    test("bulk removal clears every selected recovery reference and preserves unselected beds") {
        val selected = (0 until 4).map { FarmPlotPosition("world", it, 63, 1) }.toSet()
        val retained = (4 until 8).map { FarmPlotPosition("world", it, 63, 1) }.toSet()
        val result = FarmAdminEdit.removePlots(
            FarmShiftState(
                phase = FarmPhase.CARE,
                sequence = 10,
                orderId = "order",
                preparationPatch = (selected + retained).toList(),
                preparationCrop = "WHEAT",
                preparationReleased = true,
                tilledPlots = selected + retained,
                plantedPlots = selected + retained,
                preparationProgress = 8,
                plantingProgress = 8,
                preparationRequired = 8,
                careType = FarmCareType.WEEDS,
                careTargets = listOf(
                    FarmCareTarget(1, FarmCareRole.WEED_ROOT, FarmPointPosition("world", 0.5, 64.0, 1.5)),
                    FarmCareTarget(2, FarmCareRole.WEED_ROOT, FarmPointPosition("world", 7.5, 64.0, 1.5)),
                ),
                droughtPlots = selected,
                pestNests = listOf(FarmPestNest(selected.first(), health = 2)),
            ),
            selected,
        )

        result.state.preparationPatch.toSet() shouldBe retained
        result.state.tilledPlots shouldBe retained
        result.state.plantedPlots shouldBe retained
        result.state.preparationRequired shouldBe retained.size
        result.state.careTargets.map(FarmCareTarget::id) shouldBe listOf(2)
        result.careTargetIds shouldBe setOf(1)
        result.state.droughtPlots shouldBe emptySet()
        result.state.pestNests shouldBe emptyList()
        result.shiftRetired shouldBe false
    }

    test("removing a machine endpoint keeps the persisted route available for the remaining field") {
        val endpoint = FarmPlotPosition("world", 0, 63, 0)
        val retained = FarmPlotPosition("world", 1, 63, 0)
        val targets = listOf(
            FarmCareTarget(
                0,
                FarmCareRole.SEEDER_HORSE,
                FarmPointPosition("world", 0.5, 64.0, 0.5),
                progress = 1,
            ),
            FarmCareTarget(1, FarmCareRole.SEEDER_WAYPOINT, FarmPointPosition("world", 0.5, 64.0, 0.5)),
        )

        val result = FarmAdminEdit.removePlot(
            FarmShiftState(
                phase = FarmPhase.CARE,
                sequence = 11,
                orderId = "order",
                preparationPatch = listOf(endpoint, retained),
                preparationCrop = "WHEAT",
                preparationReleased = true,
                preparationRequired = 2,
                careType = FarmCareType.SEEDER,
                careTargets = targets,
            ),
            endpoint,
        )

        result.state.phase shouldBe FarmPhase.CARE
        result.state.preparationPatch shouldBe listOf(retained)
        result.state.careTargets shouldBe targets
        result.careTargetIds shouldBe emptySet()
    }

    test("admin removal retires removed night targets without losing crop recovery") {
        val removed = FarmPlotPosition("world", 1, 63, 1)
        val retained = FarmPlotPosition("world", 2, 63, 1)
        val result = FarmAdminEdit.removePlot(
            FarmShiftState(
                phase = FarmPhase.INCIDENT,
                sequence = 12,
                orderId = "order",
                preparationPatch = listOf(removed, retained),
                preparationCrop = "WHEAT",
                preparationRequired = 2,
                incidentCrop = "WHEAT",
                incidentType = FarmIncidentType.NIGHT_SHIFT,
                incidentRequired = 2,
                specialIncident = FarmSpecialIncidentState(plots = listOf(removed, retained)),
                specialDamagedCrops = listOf(FarmCropDamage(retained, "WHEAT")),
            ),
            removed,
        )

        result.state.phase shouldBe FarmPhase.HARVESTING
        result.state.specialIncident shouldBe null
        result.state.specialDamagedCrops shouldBe listOf(FarmCropDamage(retained, "WHEAT"))
        result.state.incidentsResolved shouldBe 1
    }
})

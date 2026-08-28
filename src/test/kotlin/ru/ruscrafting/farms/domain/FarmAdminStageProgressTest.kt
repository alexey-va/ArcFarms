package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmAdminStageProgressTest : FunSpec({
    test("completed admin stage counts every tracked plot even when the gameplay quota is 90 percent") {
        val patch = (0 until 39).map { FarmPlotPosition("world", it, 63, 0) }
        val prepared = FarmShiftState(
            phase = FarmPhase.PREPARATION,
            preparationPatch = patch,
            preparationCrop = "WHEAT",
            preparationRequired = 36,
        )

        val harvesting = FarmAdminStageProgress.completed(prepared, planted = true)

        harvesting.tilledPlots.size shouldBe 39
        harvesting.plantedPlots.size shouldBe 39
        harvesting.preparationProgress shouldBe 39
        harvesting.plantingProgress shouldBe 39
        harvesting.preparationRequired shouldBe 36
    }

    test("forced incidents keep one completion slot even after the scheduled incident quota") {
        val patch = listOf(FarmPlotPosition("world", 0, 63, 0))
        val rules = FarmRules(
            incidentTriggerPercents = listOf(15, 32, 50, 68, 85),
            incidentQuota = 4,
            cooldownMillis = 0,
            incidentCountMin = 3,
            incidentCountMax = 5,
        )
        val current = FarmShiftState(
            sequence = 7,
            placementSequence = 12,
            preparationPatch = patch,
            incidentsResolved = MAX_FARM_INCIDENTS,
        )

        val forced = FarmAdminStageProgress.forcedIncident(current, rules)

        forced.incidentsResolved shouldBe rules.incidentTargetCount(current.sequence) - 1
        forced.placementSequence shouldBe 13
        val skipped = FarmSpecialIncidentEngine.skipUnavailable(
            forced.copy(
                phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.GIANT_CROP,
                incidentProgress = 0,
                specialIncident = null,
            ),
        )
        skipped.state.incidentsResolved shouldBe rules.incidentTargetCount(current.sequence)
    }
})

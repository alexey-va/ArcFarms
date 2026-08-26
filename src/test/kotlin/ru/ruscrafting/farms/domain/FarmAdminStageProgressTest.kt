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
})

package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState

class FarmRuntimeFactoryTest : FunSpec({
    test("legacy empty preparation is retired before the runtime can strand a shift") {
        val legacy = FarmShiftState(
            phase = FarmPhase.PREPARATION,
            preparationCrop = "WHEAT",
            preparationProgress = 7,
            plantingProgress = 4,
            preparationRequired = 10,
            tilledPlots = setOf(FarmPlotPosition("world", 1, 64, 1)),
            plantedPlots = setOf(FarmPlotPosition("world", 1, 64, 1)),
        )

        FarmRuntimeFactory.repairLegacyPreparation(legacy) shouldBe legacy.copy(
            phase = FarmPhase.HARVESTING,
            preparationCrop = null,
            preparationProgress = 0,
            plantingProgress = 0,
            preparationRequired = 0,
            tilledPlots = emptySet(),
            plantedPlots = emptySet(),
        )
    }

    test("a valid preparation patch is not rewritten") {
        val active = FarmShiftState(
            phase = FarmPhase.PREPARATION,
            preparationCrop = "WHEAT",
            preparationPatch = listOf(FarmPlotPosition("world", 1, 64, 1)),
            preparationRequired = 1,
        )

        FarmRuntimeFactory.repairLegacyPreparation(active) shouldBe active
    }
})

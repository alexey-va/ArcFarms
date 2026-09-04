package ru.ruscrafting.farms.paper.farm.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmProcessingStage

class FarmIncidentGuidanceTest : FunSpec({
    test("every incident has its own action bar guidance") {
        FarmIncidentType.entries.associateWith { type ->
            farmIncidentHintKey(type, processingStage = null, marketAccepted = false)
        } shouldContainExactly mapOf(
            FarmIncidentType.PESTS to MessageKey.FARM_PESTS_REQUIRED,
            FarmIncidentType.DROUGHT to MessageKey.FARM_DROUGHT_REQUIRED,
            FarmIncidentType.BIRDS to MessageKey.FARM_BIRDS_REQUIRED,
            FarmIncidentType.FOOD_DELIVERY to MessageKey.FARM_ROUTE_REQUIRED,
            FarmIncidentType.GIANT_CROP to MessageKey.FARM_GIANT_CROP_TOOL,
            FarmIncidentType.CHANNELS to MessageKey.FARM_CHANNELS_PROGRESS,
            FarmIncidentType.NIGHT_SHIFT to MessageKey.FARM_SPECIAL_PROGRESS,
            FarmIncidentType.MARKET to MessageKey.FARM_MARKET_REQUIRED,
            FarmIncidentType.PROCESSING to MessageKey.FARM_PROCESSING_LOADING_HINT,
            FarmIncidentType.BARN_FIRE to MessageKey.FARM_BARN_FIRE_AIM_HINT,
            FarmIncidentType.FROST to MessageKey.FARM_FROST_REQUIRED,
            FarmIncidentType.BOAR_BREAKOUT to MessageKey.FARM_BOAR_BREAKOUT_REQUIRED,
            FarmIncidentType.RIVAL_RAID to MessageKey.FARM_RIVAL_RAID_REQUIRED,
            FarmIncidentType.TORNADO to MessageKey.FARM_TORNADO_REQUIRED,
        )
    }

    test("stateful incidents resolve the matching action bar variant") {
        farmIncidentHintKey(
            FarmIncidentType.MARKET,
            processingStage = null,
            marketAccepted = true,
        ) shouldBe MessageKey.FARM_MARKET_ACTIVE

        FarmProcessingStage.entries.forEach { stage ->
            farmIncidentHintKey(
                FarmIncidentType.PROCESSING,
                processingStage = stage,
                marketAccepted = false,
            ) shouldBe when (stage) {
                FarmProcessingStage.LOADING -> MessageKey.FARM_PROCESSING_LOADING_HINT
                FarmProcessingStage.OPERATING -> MessageKey.FARM_PROCESSING_OPERATING_HINT
                FarmProcessingStage.PACKING -> MessageKey.FARM_PROCESSING_PACKING_HINT
            }
        }
    }
})

package ru.ruscrafting.farms.paper.farm.supply

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.farm.FarmFieldPoiVisibility

class FarmSupplyVisibilityPolicyTest : FunSpec({
    test("idle supply points use the configured nearby distance") {
        FarmSupplyKind.entries.forEach { kind ->
            FarmSupplyVisibilityPolicy.viewRange(
                state = FarmShiftState(),
                kind = kind,
                nearbyDistanceBlocks = 15.0f,
                configuredFullRange = 2.0f,
            ) shouldBe FarmFieldPoiVisibility.nearby(15.0f)
        }
    }

    test("the supply needed by the current stage glows across the full field") {
        val cases = listOf(
            FarmShiftState(phase = FarmPhase.PREPARATION) to FarmSupplyKind.TOOL,
            FarmShiftState(phase = FarmPhase.PLANTING) to FarmSupplyKind.SEEDS,
            FarmShiftState(phase = FarmPhase.CARE, careType = FarmCareType.WEEDS) to FarmSupplyKind.TOOL,
            FarmShiftState(phase = FarmPhase.CARE, careType = FarmCareType.DISEASE) to FarmSupplyKind.TOOL,
            FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.DROUGHT) to FarmSupplyKind.WATER,
            FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.BIRDS) to FarmSupplyKind.ARCHERY,
            FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.BARN_FIRE) to FarmSupplyKind.FIRE,
        )

        cases.forEach { (state, kind) ->
            FarmSupplyVisibilityPolicy.required(state) shouldBe kind
            FarmSupplyVisibilityPolicy.viewRange(
                state,
                kind,
                nearbyDistanceBlocks = 15.0f,
                configuredFullRange = 2.0f,
            ) shouldBe 3.0f
            FarmSupplyKind.entries.filterNot { it == kind }.forEach { inactive ->
                FarmSupplyVisibilityPolicy.viewRange(
                    state,
                    inactive,
                    nearbyDistanceBlocks = 15.0f,
                    configuredFullRange = 2.0f,
                ) shouldBe FarmFieldPoiVisibility.nearby(15.0f)
            }
        }
    }
})

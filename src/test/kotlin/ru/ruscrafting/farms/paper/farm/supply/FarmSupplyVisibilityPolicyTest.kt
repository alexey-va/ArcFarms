package ru.ruscrafting.farms.paper.farm.supply

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.farm.FarmFieldPoiVisibility

class FarmSupplyVisibilityPolicyTest : FunSpec({
    test("idle supply points glow only in the nearby ten-block range") {
        FarmSupplyKind.entries.forEach { kind ->
            FarmSupplyVisibilityPolicy.viewRange(FarmShiftState(), kind, configuredFullRange = 2.0f) shouldBe
                FarmFieldPoiVisibility.NEARBY_VIEW_RANGE
        }
    }

    test("the supply needed by the current stage glows across the full field") {
        val cases = listOf(
            FarmShiftState(phase = FarmPhase.PREPARATION) to FarmSupplyKind.TOOL,
            FarmShiftState(phase = FarmPhase.PLANTING) to FarmSupplyKind.SEEDS,
            FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.DROUGHT) to FarmSupplyKind.WATER,
            FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.BIRDS) to FarmSupplyKind.ARCHERY,
            FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.BARN_FIRE) to FarmSupplyKind.FIRE,
        )

        cases.forEach { (state, kind) ->
            FarmSupplyVisibilityPolicy.viewRange(state, kind, configuredFullRange = 2.0f) shouldBe 3.0f
            FarmSupplyKind.entries.filterNot { it == kind }.forEach { inactive ->
                FarmSupplyVisibilityPolicy.viewRange(state, inactive, configuredFullRange = 2.0f) shouldBe
                    FarmFieldPoiVisibility.NEARBY_VIEW_RANGE
            }
        }
    }
})

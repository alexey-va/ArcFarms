package ru.ruscrafting.farms.paper.farm.supply

import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.farm.FarmFieldPoiVisibility

internal object FarmSupplyVisibilityPolicy {
    fun required(state: FarmShiftState): FarmSupplyKind? = when {
        state.phase == FarmPhase.PREPARATION -> FarmSupplyKind.TOOL
        state.phase == FarmPhase.PLANTING -> FarmSupplyKind.SEEDS
        state.phase == FarmPhase.CARE && state.careType in setOf(FarmCareType.WEEDS, FarmCareType.DISEASE) -> FarmSupplyKind.TOOL
        state.phase == FarmPhase.INCIDENT && state.incidentType == FarmIncidentType.DROUGHT -> FarmSupplyKind.WATER
        state.phase == FarmPhase.INCIDENT && state.incidentType == FarmIncidentType.BIRDS -> FarmSupplyKind.ARCHERY
        state.phase == FarmPhase.INCIDENT && state.incidentType == FarmIncidentType.BARN_FIRE -> FarmSupplyKind.FIRE
        else -> null
    }

    fun viewRange(
        state: FarmShiftState,
        kind: FarmSupplyKind,
        nearbyDistanceBlocks: Float,
        configuredFullRange: Float,
    ): Float =
        if (required(state) == kind) FarmFieldPoiVisibility.fullField(configuredFullRange)
        else FarmFieldPoiVisibility.nearby(nearbyDistanceBlocks)
}

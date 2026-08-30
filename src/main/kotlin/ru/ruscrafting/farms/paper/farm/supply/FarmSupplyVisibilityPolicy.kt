package ru.ruscrafting.farms.paper.farm.supply

import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.farm.FarmFieldPoiVisibility

internal object FarmSupplyVisibilityPolicy {
    fun viewRange(state: FarmShiftState, kind: FarmSupplyKind, configuredFullRange: Float): Float =
        if (isNeeded(state, kind)) FarmFieldPoiVisibility.fullField(configuredFullRange)
        else FarmFieldPoiVisibility.NEARBY_VIEW_RANGE

    private fun isNeeded(state: FarmShiftState, kind: FarmSupplyKind): Boolean = when (kind) {
        FarmSupplyKind.TOOL -> state.phase == FarmPhase.PREPARATION
        FarmSupplyKind.SEEDS -> state.phase == FarmPhase.PLANTING
        FarmSupplyKind.WATER -> state.phase == FarmPhase.INCIDENT && state.incidentType == FarmIncidentType.DROUGHT
        FarmSupplyKind.ARCHERY -> state.phase == FarmPhase.INCIDENT && state.incidentType == FarmIncidentType.BIRDS
        FarmSupplyKind.FIRE -> state.phase == FarmPhase.INCIDENT && state.incidentType == FarmIncidentType.BARN_FIRE
    }
}

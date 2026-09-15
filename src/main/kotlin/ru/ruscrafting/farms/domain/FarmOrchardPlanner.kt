package ru.ruscrafting.farms.domain

import ru.ruscrafting.farms.domain.placement.WorksitePlacementPlanner
import ru.ruscrafting.farms.domain.placement.WorksitePlacementProfiles
import ru.ruscrafting.farms.domain.placement.WorksitePlacementRequest

object FarmOrchardPlanner {
    fun select(
        candidates: Collection<FarmPlotPosition>,
        placementCount: Int,
        minimumSpacing: Double,
        selectionIndex: Long,
    ): List<FarmPlotPosition> {
        require(placementCount in 1..512) { "Apple placement count is invalid" }
        require(minimumSpacing.isFinite() && minimumSpacing >= 0.0) { "Apple target spacing is invalid" }
        return WorksitePlacementPlanner.select(
            candidates = candidates,
            request = WorksitePlacementRequest(placementCount, selectionIndex),
            profile = WorksitePlacementProfiles.evenSpread(minimumSpacing),
            positionOf = FarmPlotPosition::toWorksitePlacementPoint,
        )
    }
}

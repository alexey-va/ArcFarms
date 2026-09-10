package ru.ruscrafting.farms.domain

import ru.ruscrafting.farms.domain.worksite.WorksitePosition

/** Stable placement belongs to the interrupted shift, so a restart cannot move its room. */
data class MineScenarioPlacement(
    val origin: WorksitePosition,
    val entrance: WorksitePosition,
    val floorId: String,
    val destination: WorksitePosition = entrance,
    val destinationFloorId: String = floorId,
) {
    init {
        require(origin.world == entrance.world && entrance.world == destination.world)
        require(floorId.isNotBlank() && destinationFloorId.isNotBlank())
    }
}

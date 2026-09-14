package ru.ruscrafting.farms.domain

import ru.ruscrafting.farms.domain.worksite.WorksitePosition

/** Legacy deserialization marker used only to retire old off-map room incidents safely. */
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

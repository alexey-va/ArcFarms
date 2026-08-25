package ru.ruscrafting.farms.domain

/** Decides which managed crop beds may advance a crop-based special incident. */
object FarmSpecialCropPolicy {
    fun isEligible(
        type: FarmIncidentType,
        position: FarmPlotPosition,
        plannedPlots: Collection<FarmPlotPosition>,
        managedPlots: Collection<FarmPlotPosition>,
    ): Boolean = when (type) {
        FarmIncidentType.NIGHT_SHIFT -> position in plannedPlots
        FarmIncidentType.MARKET -> position in managedPlots
        else -> false
    }
}

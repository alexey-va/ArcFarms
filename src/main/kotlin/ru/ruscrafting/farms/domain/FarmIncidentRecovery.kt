package ru.ruscrafting.farms.domain

object FarmIncidentRecovery {
    fun pending(state: FarmShiftState): Boolean =
        state.droughtDamagedPlots.isNotEmpty() || state.pestDamagedCrops.isNotEmpty()

    /** Removes only entries whose world repair was actually confirmed. */
    fun recover(
        state: FarmShiftState,
        restoreDrought: (FarmPlotPosition) -> Boolean,
        restorePest: (FarmCropDamage) -> Boolean,
    ): FarmShiftState = state.copy(
        droughtDamagedPlots = state.droughtDamagedPlots.filterNot(restoreDrought).toSet(),
        pestDamagedCrops = state.pestDamagedCrops.filterNot(restorePest),
    )
}

package ru.ruscrafting.farms.domain

object FarmIncidentRecovery {
    fun pending(state: FarmShiftState): Boolean =
        state.droughtDamagedPlots.isNotEmpty() || state.pestDamagedCrops.isNotEmpty() ||
            state.specialDamagedCrops.isNotEmpty()

    /** Removes only entries whose world repair was actually confirmed. */
    fun recover(
        state: FarmShiftState,
        restoreDrought: (FarmPlotPosition) -> Boolean,
        restorePest: (FarmCropDamage) -> Boolean,
        restoreSpecial: (FarmCropDamage) -> Boolean = restorePest,
        limit: Int = Int.MAX_VALUE,
    ): FarmShiftState {
        require(limit >= 1) { "Incident recovery limit must be positive" }
        var attempts = 0
        val drought = state.droughtDamagedPlots.filterNot { position ->
            if (attempts >= limit) return@filterNot false
            attempts++
            restoreDrought(position)
        }.toSet()
        val pests = state.pestDamagedCrops.filterNot { damage ->
            if (attempts >= limit) return@filterNot false
            attempts++
            restorePest(damage)
        }
        val special = state.specialDamagedCrops.filterNot { damage ->
            if (attempts >= limit) return@filterNot false
            attempts++
            restoreSpecial(damage)
        }
        return state.copy(
            droughtDamagedPlots = drought,
            pestDamagedCrops = pests,
            specialDamagedCrops = special,
        )
    }
}

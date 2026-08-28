package ru.ruscrafting.farms.domain

/** Keeps admin/debug stage transitions consistent with the tracked patch. */
object FarmAdminStageProgress {
    fun completed(current: FarmShiftState, planted: Boolean): FarmShiftState {
        require(current.preparationPatch.isNotEmpty()) { "Cannot complete an empty farm patch" }
        val plots = current.preparationPatch.toSet()
        return current.copy(
            tilledPlots = plots,
            plantedPlots = if (planted) plots else emptySet(),
            preparationProgress = plots.size,
            plantingProgress = if (planted) plots.size else 0,
        )
    }

    /** Leaves one incident slot available for a repeatedly forced admin scenario. */
    fun forcedIncident(current: FarmShiftState, rules: FarmRules): FarmShiftState {
        val lastCompletedBeforeForced = (rules.incidentTargetCount(current.sequence) - 1).coerceAtLeast(0)
        return completed(current, planted = true).copy(
            placementSequence = current.nextPlacementSequence(),
            incidentsResolved = current.incidentsResolved.coerceIn(0, lastCompletedBeforeForced),
        )
    }
}

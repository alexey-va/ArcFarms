package ru.ruscrafting.farms.domain

data class FarmOrderProgressReconciliation(
    val state: FarmShiftState,
    val changed: Boolean,
)

object FarmOrderProgressReconciler {
    fun reconcile(state: FarmShiftState, required: Map<String, Int>): FarmOrderProgressReconciliation {
        if (state.phase in setOf(FarmPhase.IDLE, FarmPhase.COOLDOWN) || state.progress.keys != required.keys) {
            return FarmOrderProgressReconciliation(state, false)
        }
        val progress = required.mapValues { (crop, quota) ->
            state.progress.getValue(crop).coerceIn(0, quota)
        }.toMutableMap()
        var completed = required.entries.sumOf { (crop, quota) -> progress.getValue(crop).coerceAtMost(quota) }
        val total = required.values.sum()
        if (completed >= total && state.phase in setOf(FarmPhase.HARVESTING, FarmPhase.INCIDENT)) {
            val transitionCrop = required.keys.lastOrNull { progress.getValue(it) > 0 }
            if (transitionCrop != null) {
                progress[transitionCrop] = progress.getValue(transitionCrop) - 1
                completed--
            }
        }
        val reconciled = state.copy(
            progress = progress.toMap(),
            harvestCheckpoint = FarmContractPlanner.harvestCheckpoint(completed, total),
            harvestMilestone = FarmContractPlanner.harvestMilestone(completed, total),
        )
        return FarmOrderProgressReconciliation(reconciled, reconciled != state)
    }
}

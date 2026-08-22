package ru.ruscrafting.farms.domain

import kotlin.math.floor

data class FarmAdminPlotRemoval(
    val state: FarmShiftState,
    val careTargetIds: Set<Int>,
    val pestNestRemoved: Boolean,
    val shiftRetired: Boolean = false,
)

object FarmAdminEdit {
    fun removePlot(state: FarmShiftState, plot: FarmPlotPosition): FarmAdminPlotRemoval {
        val targetIds = state.careTargets.filter { target -> target.position.isAbove(plot) }.mapTo(mutableSetOf()) { it.id }
        val nestRemoved = state.pestNests.any { it.position == plot }
        val patch = state.preparationPatch.filterNot { it == plot }
        if (state.preparationPatch.isNotEmpty() && patch.isEmpty()) {
            val droughtDamage = state.droughtDamagedPlots - plot
            val pestDamage = state.pestDamagedCrops.filterNot { it.position == plot }
            val retiredState = if (droughtDamage.isEmpty() && pestDamage.isEmpty()) {
                FarmShiftState(sequence = state.sequence)
            } else {
                FarmShiftState(
                    phase = FarmPhase.COOLDOWN,
                    sequence = state.sequence,
                    orderId = state.orderId,
                    droughtDamagedPlots = droughtDamage,
                    pestDamagedCrops = pestDamage,
                    cooldownEndsAt = 1,
                    outcome = ShiftOutcome.COMPLETED,
                )
            }
            return FarmAdminPlotRemoval(
                state = retiredState,
                careTargetIds = state.careTargets.mapTo(mutableSetOf()) { it.id },
                pestNestRemoved = nestRemoved,
                shiftRetired = true,
            )
        }

        val tilled = state.tilledPlots - plot
        val planted = state.plantedPlots - plot
        val careTargets = state.careTargets.filterNot { it.id in targetIds }
        val phase = when {
            state.phase == FarmPhase.CARE && (careTargets.isEmpty() || careTargets.all(FarmCareTarget::complete)) -> FarmPhase.HARVESTING
            state.phase in setOf(FarmPhase.PREPARATION, FarmPhase.PLANTING) && patch.isNotEmpty() && planted.size >= patch.size ->
                FarmPhase.HARVESTING
            state.phase == FarmPhase.PREPARATION && patch.isNotEmpty() && tilled.size >= patch.size -> FarmPhase.PLANTING
            else -> state.phase
        }
        return FarmAdminPlotRemoval(
            state = state.copy(
                phase = phase,
                preparationPatch = patch,
                tilledPlots = tilled,
                plantedPlots = planted,
                preparationProgress = tilled.size,
                plantingProgress = planted.size,
                preparationRequired = patch.size,
                careTargets = careTargets,
                droughtPlots = state.droughtPlots - plot,
                droughtDamagedPlots = state.droughtDamagedPlots - plot,
                pestNests = state.pestNests.filterNot { it.position == plot },
                pestDamagedCrops = state.pestDamagedCrops.filterNot { it.position == plot },
            ),
            careTargetIds = targetIds,
            pestNestRemoved = nestRemoved,
        )
    }

    private fun FarmPointPosition.isAbove(plot: FarmPlotPosition): Boolean =
        world == plot.world && floor(x).toInt() == plot.x && floor(z).toInt() == plot.z && floor(y).toInt() == plot.y + 1
}

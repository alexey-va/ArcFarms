package ru.ruscrafting.farms.domain

import kotlin.math.floor

data class FarmAdminPlotRemoval(
    val state: FarmShiftState,
    val careTargetIds: Set<Int>,
    val pestNestRemoved: Boolean,
    val shiftRetired: Boolean = false,
)

object FarmAdminEdit {
    fun removePlot(
        state: FarmShiftState,
        plot: FarmPlotPosition,
        completionPercent: Int = 100,
    ): FarmAdminPlotRemoval = removePlots(state, setOf(plot), completionPercent)

    fun removePlots(
        state: FarmShiftState,
        plots: Set<FarmPlotPosition>,
        completionPercent: Int = 100,
    ): FarmAdminPlotRemoval {
        if (plots.isEmpty()) return FarmAdminPlotRemoval(state, emptySet(), pestNestRemoved = false)
        val targetIds = if (state.careType == FarmCareType.SEEDER) {
            emptySet()
        } else {
            state.careTargets.filter { target -> plots.any { plot -> target.position.isAbove(plot) } }
                .mapTo(mutableSetOf()) { it.id }
        }
        val nestRemoved = state.pestNests.any { it.position in plots }
        val patch = state.preparationPatch.filterNot(plots::contains)
        if (state.preparationPatch.isNotEmpty() && patch.isEmpty()) {
            val droughtDamage = state.droughtDamagedPlots - plots
            val pestDamage = state.pestDamagedCrops.filterNot { it.position in plots }
            val diseaseDamage = state.diseaseDamagedCrops.orEmpty().filterNot { it.position in plots }
            val specialDamage = state.specialDamagedCrops.filterNot { it.position in plots }
            val retiredState = if (
                droughtDamage.isEmpty() && pestDamage.isEmpty() && diseaseDamage.isEmpty() && specialDamage.isEmpty()
            ) {
                FarmShiftState(sequence = state.sequence)
            } else {
                FarmShiftState(
                    phase = FarmPhase.COOLDOWN,
                    sequence = state.sequence,
                    orderId = state.orderId,
                    droughtDamagedPlots = droughtDamage,
                    pestDamagedCrops = pestDamage,
                    diseaseDamagedCrops = diseaseDamage,
                    specialDamagedCrops = specialDamage,
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

        val tilled = state.tilledPlots - plots
        val planted = state.plantedPlots - plots
        val careTargets = state.careTargets.filterNot { it.id in targetIds }
        val careAvailable = careTargets.sumOf(FarmCareTarget::required)
        val careGoal = state.careGoal?.coerceAtMost(careAvailable)?.takeIf { it > 0 }
        val careComplete = careTargets.isEmpty() ||
            careTargets.sumOf(FarmCareTarget::progress) >= (careGoal ?: careAvailable)
        val specialPlots = state.specialIncident?.plots.orEmpty().filterNot(plots::contains)
        val specialDamage = state.specialDamagedCrops.filterNot { it.position in plots }
        val diseaseDamage = state.diseaseDamagedCrops.orEmpty().filterNot { it.position in plots }
        val specialRequired = if (
            state.phase == FarmPhase.INCIDENT &&
            state.incidentType in setOf(FarmIncidentType.NIGHT_SHIFT, FarmIncidentType.MARKET)
        ) {
            state.incidentProgress + specialPlots.count { plot -> specialDamage.none { it.position == plot } }
        } else state.incidentRequired
        val specialResolved = state.phase == FarmPhase.INCIDENT && specialRequired <= state.incidentProgress
        val fieldRequired = patch.takeIf { it.isNotEmpty() }
            ?.let { FarmFieldQuota.required(it.size, completionPercent) }
            ?: 0
        val phase = when {
            specialResolved -> FarmPhase.HARVESTING
            state.phase == FarmPhase.CARE && state.careType == FarmCareType.SEEDER &&
                planted.size >= fieldRequired && (careTargets.isEmpty() || careTargets.all(FarmCareTarget::complete)) ->
                FarmPhase.HARVESTING
            state.phase == FarmPhase.CARE && state.careType != FarmCareType.SEEDER &&
                careComplete -> FarmPhase.HARVESTING
            state.phase in setOf(FarmPhase.PREPARATION, FarmPhase.PLANTING) && patch.isNotEmpty() &&
                planted.size >= fieldRequired ->
                FarmPhase.HARVESTING
            state.phase == FarmPhase.PREPARATION && patch.isNotEmpty() && tilled.size >= fieldRequired -> FarmPhase.PLANTING
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
                preparationRequired = fieldRequired,
                careTargets = careTargets,
                careGoal = careGoal,
                droughtPlots = state.droughtPlots - plots,
                droughtDamagedPlots = state.droughtDamagedPlots - plots,
                pestNests = state.pestNests.filterNot { it.position in plots },
                pestDamagedCrops = state.pestDamagedCrops.filterNot { it.position in plots },
                diseaseDamagedCrops = diseaseDamage,
                specialIncident = if (specialResolved) null else state.specialIncident?.copy(plots = specialPlots),
                specialDamagedCrops = specialDamage,
                incidentCrop = if (specialResolved) null else state.incidentCrop,
                incidentType = if (specialResolved) null else state.incidentType,
                incidentProgress = if (specialResolved) 0 else state.incidentProgress,
                incidentRequired = if (specialResolved) 0 else specialRequired,
                incidentResolved = state.incidentResolved || specialResolved,
                incidentsResolved = (
                    state.incidentsResolved + if (specialResolved) 1 else 0
                ).coerceAtMost(MAX_FARM_INCIDENTS),
            ),
            careTargetIds = targetIds,
            pestNestRemoved = nestRemoved,
        )
    }

    private fun FarmPointPosition.isAbove(plot: FarmPlotPosition): Boolean =
        world == plot.world && floor(x).toInt() == plot.x && floor(z).toInt() == plot.z && floor(y).toInt() == plot.y + 1
}

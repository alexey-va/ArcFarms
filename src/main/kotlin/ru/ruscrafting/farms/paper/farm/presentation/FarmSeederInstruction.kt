package ru.ruscrafting.farms.paper.farm.presentation

import ru.ruscrafting.farms.domain.FarmSeederStage
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.seederStage

/** One presentation mapping for every HUD or transition that explains the active seeder stage. */
internal object FarmSeederInstruction {
    fun path(state: FarmShiftState): String = when (state.seederStage()) {
        FarmSeederStage.TILLING -> "care.seeder.tilling-instruction"
        FarmSeederStage.PLANTING -> "care.seeder.planting-instruction"
        null -> "care.seeder.instruction"
    }
}

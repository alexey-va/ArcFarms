package ru.ruscrafting.farms.paper.lumber

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.LumberShiftEvent
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort

/** The only Paper-side writer for V2 lumber domain transitions. */
internal class LumberTransitionCoordinator(
    private val state: WorksiteStatePort,
    private val stats: WorksiteStatsPort,
) {
    fun apply(
        runtime: LumberRuntime,
        result: EngineResult<LumberShiftState, LumberShiftEvent>,
        actor: Player?,
    ) {
        runtime.state = result.state
        state.traceResult(
            ActivityKind.LUMBER,
            runtime.settings.id,
            actor,
            runtime.state.phase,
            "${runtime.state.felled}/${runtime.rules().fellingQuota}:" +
                "${runtime.state.skidded}/${runtime.rules().skiddingQuota}:" +
                "${runtime.state.sawCuts}/${runtime.rules().sawingQuota}:" +
                "${runtime.state.stacked}/${runtime.rules().stackingQuota}",
            result,
        )
        if (actor != null && result.contribution > 0) {
            stats.recordContribution(actor.uniqueId, ActivityKind.LUMBER, result.contribution)
        }
        state.persistAsync()
    }
}

package ru.ruscrafting.farms.paper.mine

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.MineShiftEvent
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.paper.WorksiteRuntimePort

/** The only Paper-side writer for V2 mine domain transitions. */
internal class MineTransitionCoordinator(private val port: WorksiteRuntimePort) {
    fun apply(runtime: MineRuntime, result: EngineResult<MineShiftState, MineShiftEvent>, actor: Player?) {
        if (!result.accepted && result.events.isEmpty()) return
        runtime.state = result.state
        port.traceResult(
            ActivityKind.MINE,
            runtime.settings.id,
            actor,
            runtime.state.phase,
            "${runtime.state.prospected}/${runtime.rules().prospectingQuota}:" +
                "${runtime.state.mined}/${runtime.rules().miningQuota}:" +
                "${runtime.state.loaded}/${runtime.rules().loadingQuota}:" + runtime.state.routeIndex,
            result,
        )
        if (actor != null && result.contribution > 0) {
            port.recordContribution(actor.uniqueId, ActivityKind.MINE, result.contribution)
        }
        port.persistAsync()
    }
}

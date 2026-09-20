package ru.ruscrafting.farms.paper.mine

import org.bukkit.entity.Player
import net.kyori.adventure.text.Component
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.MineShiftEvent
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort

/** The only Paper-side writer for V2 mine domain transitions. */
internal class MineTransitionCoordinator(
    private val state: WorksiteStatePort,
    private val stats: WorksiteStatsPort,
    private val audience: WorksiteAudiencePort,
    private val locale: ArcFarmsLocale?,
    private val incidentJournal: ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal? = null,
) {
    fun apply(runtime: MineRuntime, result: EngineResult<MineShiftState, MineShiftEvent>, actor: Player?) {
        if (!result.accepted && result.events.isEmpty()) return
        val previous = runtime.state
        runtime.state = result.state
        previous.incident?.takeIf {
            previous.sequence != result.state.sequence || result.state.incident?.objectiveNonce != it.objectiveNonce ||
                result.state.incident?.type != it.type
        }?.let { ended ->
            incidentJournal?.restore(runtime.settings.id, previous.sequence, ended.type.name.lowercase(), ended.objectiveNonce)
        }
        state.traceResult(
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
            stats.recordContribution(actor.uniqueId, ActivityKind.MINE, result.contribution)
        }
        result.events.forEach { event ->
            when (event) {
                MineShiftEvent.INCIDENT_STARTED -> announceIncident(runtime)
                MineShiftEvent.INCIDENT_RESOLVED -> audience.successBurst(runtime.region)
                else -> Unit
            }
        }
        state.persistAsync()
    }

    private fun announceIncident(runtime: MineRuntime) {
        val type = runtime.state.incident?.type ?: return
        val id = type.name.lowercase()
        audience.players(runtime.region).forEach { player ->
            val title = locale?.renderPath("mine.incident-name.$id", player) ?: Component.text(id)
            val subtitle = locale?.renderPath("mine.guidance.$id", player) ?: Component.empty()
            audience.showScreenTitle(player, title, subtitle)
        }
        audience.warningBurst(runtime.region)
    }
}

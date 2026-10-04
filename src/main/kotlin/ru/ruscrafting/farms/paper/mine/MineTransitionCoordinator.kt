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
import ru.ruscrafting.farms.paper.ArcProductTelemetryBridge

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
        val persisted = state.persistAsync()
        val started = result.accepted && MineShiftEvent.STARTED in result.events && actor != null
        val completed = result.accepted && MineShiftEvent.COMPLETED in result.events
        if (started || completed) {
            val playerIds = if (completed) result.state.contributors.entries.map { it.key to it.value }
                .ifEmpty { actor?.let { listOf(it.uniqueId to 0) }.orEmpty() }
            else listOf(actor!!.uniqueId to (result.state.contributors[actor.uniqueId] ?: 0))
            val kind = "mine"
            val zoneId = runtime.settings.id
            val sequence = result.state.sequence
            val startedAt = result.state.startedAt
            val completedAt = if (completed) System.currentTimeMillis() else 0L
            val subject = result.state.orderId
            persisted.whenComplete { _, failure ->
                if (failure == null) {
                    playerIds.forEach { (playerId, contribution) ->
                        if (started) ArcProductTelemetryBridge.worksiteStarted(
                            playerId, kind, zoneId, sequence, subject,
                            mapOf("outcome" to "started"),
                        ) else ArcProductTelemetryBridge.worksiteCompleted(
                            playerId, kind, zoneId, sequence, startedAt, completedAt, contribution, subject,
                        )
                    }
                }
            }
        }
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

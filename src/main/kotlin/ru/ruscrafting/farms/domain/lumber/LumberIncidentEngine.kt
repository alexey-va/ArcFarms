package ru.ruscrafting.farms.domain.lumber

import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.util.UUID

/** Progress is immutable; the Paper owner commits each returned checkpoint before presenting it. */
object LumberIncidentEngine {
    const val CRANK_TURNS = 2
    const val SMOLDER_PILES = 3

    fun isSafeBoundary(current: LumberState): Boolean = !current.sawRunning && !current.boardCartLoaded &&
        current.cartCarrier == null && current.boardsCarrier == null &&
        (current.phase == LumberPhase.SAWING || current.phase == LumberPhase.COLLECTING_BOARDS)

    fun schedule(current: LumberState, positions: List<WorksitePosition>): LumberState {
        if (current.incident != null || current.incidentHandledRoundId == current.roundId) return current
        val type = when {
            current.completedRounds % 4L == 1L && current.phase == LumberPhase.SAWING && current.sawPasses == 1 -> LumberIncidentType.FEED_JAM
            current.completedRounds % 4L == 3L && current.phase == LumberPhase.COLLECTING_BOARDS && current.sawPasses == 2 -> LumberIncidentType.SAWDUST_SMOLDER
            else -> return current
        }
        return start(current, type, positions)
    }

    fun start(current: LumberState, type: LumberIncidentType, positions: List<WorksitePosition> = emptyList(), force: Boolean = false): LumberState {
        if (!isSafeBoundary(current) || current.roundId <= 0 || current.incidentGeneration >= Long.MAX_VALUE - 1 ||
            current.incident != null && !force) return current
        val generation = current.incidentGeneration + 1
        val targets = if (type == LumberIncidentType.SAWDUST_SMOLDER) {
            if (positions.size != SMOLDER_PILES || positions.distinct().size != SMOLDER_PILES ||
                positions.any { it.world != current.tree?.root?.world }) return current
            ObjectiveTargetPool.plan(WorksiteObjectiveKey(LumberEngine.ZONE_ID, "sawdust_smolder", generation), SMOLDER_PILES,
                positions.mapIndexed { index, point -> ObjectiveTargetCandidate("pile_$index", point, ObjectiveTargetRole("sawdust"), index.toLong()) })
        } else null
        return current.copy(incident = LumberIncidentState(type, generation, hotspots = targets),
            incidentGeneration = generation, incidentHandledRoundId = current.roundId, progressVersion = current.progressVersion + 1)
    }

    fun startCrank(current: LumberState, actor: UUID): LumberState {
        val incident = current.incident ?: return current
        if (incident.type != LumberIncidentType.FEED_JAM || incident.crankOperator != null) return current
        return current.copy(incident = incident.copy(crankOperator = actor), progressVersion = current.progressVersion + 1)
    }

    fun completeTurn(current: LumberState, actor: UUID, generation: Long, now: Long = current.phaseStartedAt): LumberState {
        val incident = current.incident ?: return current
        if (incident.type != LumberIncidentType.FEED_JAM || incident.generation != generation || incident.crankOperator != actor) return current
        val turns = incident.crankTurns + 1
        val next = progressed(current, actor).copy(incident = if (turns >= CRANK_TURNS) null else incident.copy(crankTurns = turns))
        return if (next.incident == null && next.phase == LumberPhase.COLLECTING_BOARDS) next.copy(phaseStartedAt = now) else next
    }

    fun extinguish(current: LumberState, targetId: String, actor: UUID, generation: Long, now: Long = current.phaseStartedAt): LumberState {
        val incident = current.incident ?: return current
        if (incident.type != LumberIncidentType.SAWDUST_SMOLDER || incident.generation != generation) return current
        val targets = incident.hotspots ?: return current
        val result = ObjectiveTargetPool.complete(targets, targetId, actor)
        if (!result.accepted) return current
        val next = progressed(current, actor).copy(incident = if (result.state.completed == SMOLDER_PILES) null else incident.copy(hotspots = result.state))
        return if (next.incident == null && next.phase == LumberPhase.COLLECTING_BOARDS) next.copy(phaseStartedAt = now) else next
    }

    fun release(current: LumberState, actor: UUID): LumberState {
        val incident = current.incident ?: return current
        if (incident.crankOperator != actor) return current
        return current.copy(incident = incident.copy(crankOperator = null), progressVersion = current.progressVersion + 1)
    }

    private fun progressed(current: LumberState, actor: UUID) = current.copy(progressVersion = current.progressVersion + 1,
        contributors = current.contributors + (actor to ((current.contributors[actor] ?: 0) + 1).coerceAtMost(100_000)))
}

package ru.ruscrafting.farms.domain

import java.util.UUID

enum class LumberShiftEvent {
    STARTED,
    PROGRESS,
    PHASE_CHANGED,
    INCIDENT_STARTED,
    INCIDENT_RESOLVED,
    COMPLETED,
    RESET,
}

enum class LumberPhase {
    IDLE,
    FELLING,
    SKIDDING,
    SAWING,
    STACKING,
    DISPATCH,
    INCIDENT,
    /** Deserialization-only legacy phase removed from V2. */
    PROCESSING,
    COOLDOWN,
}

enum class LumberIncidentType {
    WINDTHROW,
    BARK_BEETLES,
    SAW_JAM,
    CONVEYOR_BREAKDOWN,
    FOREST_FIRE,
    LOST_LOAD,
    WARPED_BATCH,
    RUSH_ORDER,
}

data class LumberOrder(
    val id: String,
    val species: List<String>,
    val incidents: List<LumberIncidentType>,
) {
    init {
        require(DomainIdentifiers.isOrder(id)) { "Invalid lumber order id: $id" }
        require(species.isNotEmpty() && species.distinct().size == species.size) { "Lumber order species are invalid" }
        require(species.all { it.matches(SPECIES) }) { "Lumber order contains an invalid species" }
        require(incidents.size in 3..8 && incidents.distinct().size == incidents.size) {
            "Lumber order must contain three to eight distinct incidents"
        }
    }

    private companion object {
        val SPECIES = Regex("[A-Z0-9_]{2,32}")
    }
}

data class LumberRules(
    val fellingQuota: Int,
    val processingQuota: Int,
    val processingPerUse: Int,
    val cooldownMillis: Long,
    val skiddingQuota: Int = processingQuota,
    val sawingQuota: Int = processingQuota,
    val stackingQuota: Int = processingQuota,
    val targetMultiplier: Int = 2,
    val incidentCountMin: Int = 3,
    val incidentCountMax: Int = 5,
) {
    init {
        require(fellingQuota in 1..100_000)
        require(processingQuota in 1..100_000)
        require(processingPerUse in 1..processingQuota)
        require(skiddingQuota in 1..100_000)
        require(sawingQuota in 1..100_000)
        require(stackingQuota in 1..100_000)
        require(targetMultiplier in 2..4)
        require(incidentCountMin in 3..5 && incidentCountMax in incidentCountMin..5)
        require(cooldownMillis in 0..3_600_000)
    }
}

data class LumberIncidentState(
    val type: LumberIncidentType,
    val required: Int,
    val progress: Int = 0,
    val objectiveNonce: Long = 0L,
    val startedAt: Long = 0L,
    val deadlineAt: Long = 0L,
    val bonusAvailable: Boolean = true,
    val serviceLeases: Map<String, UUID> = emptyMap(),
) {
    init {
        require(required in 1..100_000)
        require(progress in 0..required)
        require(objectiveNonce >= 0L)
        require(startedAt >= 0L && deadlineAt >= 0L)
    }
}

data class LumberRushOrderState(
    val startedAt: Long,
    val deadlineAt: Long,
    val bonusAvailable: Boolean = true,
    val bonusEarned: Boolean = false,
) {
    init {
        require(startedAt >= 0L && deadlineAt > startedAt)
        require(!bonusEarned || bonusAvailable) { "An earned rush bonus cannot be unavailable" }
    }
}

data class LumberShiftState(
    val engineVersion: Int = 2,
    val phase: LumberPhase = LumberPhase.IDLE,
    val sequence: Long = 0,
    val orderId: String? = null,
    val species: String? = null,
    val felled: Int = 0,
    /** Kept only for reading and running one migration release of V1. */
    val processed: Int = 0,
    val skidded: Int = 0,
    val sawCuts: Int = 0,
    val sawSequence: ru.ruscrafting.farms.domain.lumber.LumberSawSequenceState =
        ru.ruscrafting.farms.domain.lumber.LumberSawSequenceState(),
    val stacked: Int = 0,
    val dispatched: Boolean = false,
    val startedAt: Long = 0,
    val cooldownEndsAt: Long = 0,
    val outcome: ShiftOutcome = ShiftOutcome.NONE,
    val contributors: Map<UUID, Int> = emptyMap(),
    val incidentSchedule: List<LumberIncidentType> = emptyList(),
    val incidentCursor: Int = 0,
    val resumePhase: LumberPhase? = null,
    val resumeObjective: ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState? = null,
    val incident: LumberIncidentState? = null,
    val rushOrder: LumberRushOrderState? = null,
    val objective: ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState? = null,
)

object LumberStateMigration {
    fun migrate(current: LumberShiftState): LumberShiftState = when {
        current.phase == LumberPhase.COOLDOWN -> current
        current.phase == LumberPhase.IDLE -> current.copy(engineVersion = 2)
        current.engineVersion >= 2 && current.phase != LumberPhase.PROCESSING -> current
        else -> LumberShiftState(sequence = current.sequence)
    }
}

object LumberShiftEngine {
    fun start(
        current: LumberShiftState,
        species: String,
        rules: LumberRules,
        now: Long,
    ): EngineResult<LumberShiftState, LumberShiftEvent> {
        if (current.phase != LumberPhase.IDLE) return EngineResult(current, false)
        require(species.matches(Regex("[A-Z0-9_]{2,32}"))) { "Invalid lumber species: $species" }
        return EngineResult(
            LumberShiftState(
                engineVersion = 1,
                phase = LumberPhase.FELLING,
                sequence = current.sequence + 1,
                species = species,
                startedAt = now,
            ),
            true,
            events = listOf(LumberShiftEvent.STARTED),
        )
    }

    fun start(
        current: LumberShiftState,
        order: LumberOrder,
        rules: LumberRules,
        now: Long,
    ): EngineResult<LumberShiftState, LumberShiftEvent> {
        if (current.phase != LumberPhase.IDLE) return EngineResult(current, false)
        val sequence = current.sequence + 1
        val species = order.species[((sequence - 1) % order.species.size).toInt()]
        val countRange = rules.incidentCountMax - rules.incidentCountMin + 1
        val incidentCount = rules.incidentCountMin + (sequence % countRange).toInt()
        require(order.incidents.size >= incidentCount) {
            "Lumber order ${order.id} has fewer incidents than the resolved schedule"
        }
        val offset = (sequence % order.incidents.size).toInt()
        val schedule = order.incidents.indices
            .map { order.incidents[(offset + it) % order.incidents.size] }
            .take(incidentCount)
        return EngineResult(
            LumberShiftState(
                phase = LumberPhase.FELLING,
                sequence = sequence,
                orderId = order.id,
                species = species,
                startedAt = now,
                incidentSchedule = schedule,
            ),
            true,
            events = listOf(LumberShiftEvent.STARTED),
        )
    }

    fun fell(
        current: LumberShiftState,
        rules: LumberRules,
        species: String,
        playerId: UUID,
        now: Long,
    ): EngineResult<LumberShiftState, LumberShiftEvent> {
        val advanced = tick(current, rules, now)
        var state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase != LumberPhase.FELLING || state.species != species) return EngineResult(state, false, events = events)
        val next = (state.felled + 1).coerceAtMost(rules.fellingQuota)
        state = state.copy(felled = next, contributors = contribute(state, playerId, 1))
        events += LumberShiftEvent.PROGRESS
        if (next >= rules.fellingQuota) {
            state = state.copy(phase = if (state.orderId == null) LumberPhase.PROCESSING else LumberPhase.SKIDDING, objective = null)
            events += LumberShiftEvent.PHASE_CHANGED
        }
        return EngineResult(state, true, 1, events)
    }

    fun process(
        current: LumberShiftState,
        rules: LumberRules,
        playerId: UUID,
        now: Long,
    ): EngineResult<LumberShiftState, LumberShiftEvent> {
        val advanced = tick(current, rules, now)
        var state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase != LumberPhase.PROCESSING) return EngineResult(state, false, events = events)
        val next = (state.processed + rules.processingPerUse).coerceAtMost(rules.processingQuota)
        val delta = next - state.processed
        state = state.copy(processed = next, contributors = contribute(state, playerId, delta))
        events += LumberShiftEvent.PROGRESS
        if (next >= rules.processingQuota) {
            state = completed(state, rules, now)
            events += LumberShiftEvent.COMPLETED
        }
        return EngineResult(state, true, delta, events)
    }

    fun skid(current: LumberShiftState, rules: LumberRules, playerId: UUID): EngineResult<LumberShiftState, LumberShiftEvent> =
        progress(current, LumberPhase.SKIDDING, rules.skiddingQuota, LumberPhase.SAWING, playerId) { state, next ->
            state.copy(skidded = next)
        }

    fun saw(current: LumberShiftState, rules: LumberRules, playerId: UUID): EngineResult<LumberShiftState, LumberShiftEvent> =
        progress(current, LumberPhase.SAWING, rules.sawingQuota, LumberPhase.STACKING, playerId) { state, next ->
            state.copy(sawCuts = next)
        }

    fun stack(current: LumberShiftState, rules: LumberRules, playerId: UUID): EngineResult<LumberShiftState, LumberShiftEvent> =
        progress(current, LumberPhase.STACKING, rules.stackingQuota, LumberPhase.DISPATCH, playerId) { state, next ->
            state.copy(stacked = next)
        }

    fun dispatch(
        current: LumberShiftState,
        rules: LumberRules,
        playerId: UUID,
        now: Long,
    ): EngineResult<LumberShiftState, LumberShiftEvent> {
        if (current.phase != LumberPhase.DISPATCH || current.dispatched) return EngineResult(current, false)
        return EngineResult(
            completed(current.copy(dispatched = true, contributors = contribute(current, playerId, 1)), rules, now),
            true,
            contribution = 1,
            events = listOf(LumberShiftEvent.COMPLETED),
        )
    }

    fun startIncident(
        current: LumberShiftState,
        type: LumberIncidentType,
        required: Int,
        now: Long = 0L,
        deadlineAt: Long = 0L,
    ): EngineResult<LumberShiftState, LumberShiftEvent> {
        if (current.phase !in FOREGROUND_PHASES || current.incident != null) return EngineResult(current, false)
        val incident = LumberIncidentState(
            type = type,
            required = required,
            objectiveNonce = current.incidentCursor.toLong() + 1L,
            startedAt = now,
            deadlineAt = deadlineAt,
        )
        return EngineResult(
            current.copy(
                phase = LumberPhase.INCIDENT,
                resumePhase = current.phase,
                resumeObjective = current.objective,
                incident = incident,
                objective = null,
            ),
            true,
            events = listOf(LumberShiftEvent.INCIDENT_STARTED),
        )
    }

    fun workIncident(current: LumberShiftState, playerId: UUID, amount: Int = 1): EngineResult<LumberShiftState, LumberShiftEvent> {
        val incident = current.incident ?: return EngineResult(current, false)
        if (current.phase != LumberPhase.INCIDENT || amount <= 0 || incident.progress >= incident.required) {
            return EngineResult(current, false)
        }
        val next = (incident.progress.toLong() + amount).coerceAtMost(incident.required.toLong()).toInt()
        val delta = next - incident.progress
        return EngineResult(
            current.copy(incident = incident.copy(progress = next), contributors = contribute(current, playerId, delta)),
            true,
            contribution = delta,
            events = listOf(LumberShiftEvent.PROGRESS),
        )
    }

    fun resolveIncident(current: LumberShiftState): EngineResult<LumberShiftState, LumberShiftEvent> {
        val incident = current.incident ?: return EngineResult(current, false)
        val resume = current.resumePhase ?: return EngineResult(current, false)
        if (current.phase != LumberPhase.INCIDENT || incident.progress < incident.required) return EngineResult(current, false)
        return EngineResult(
            current.copy(
                phase = resume,
                resumePhase = null,
                resumeObjective = null,
                incident = null,
                incidentCursor = current.incidentCursor + 1,
                objective = current.resumeObjective,
            ),
            true,
            events = listOf(LumberShiftEvent.INCIDENT_RESOLVED),
        )
    }

    fun tick(current: LumberShiftState, rules: LumberRules, now: Long): EngineResult<LumberShiftState, LumberShiftEvent> {
        if (current.phase == LumberPhase.IDLE) return EngineResult(current, false)
        if (current.phase == LumberPhase.COOLDOWN && now >= current.cooldownEndsAt) {
            return EngineResult(LumberShiftState(sequence = current.sequence), true, events = listOf(LumberShiftEvent.RESET))
        }
        if (current.phase == LumberPhase.FELLING && current.felled >= rules.fellingQuota) {
            return EngineResult(
                current.copy(phase = if (current.orderId == null) LumberPhase.PROCESSING else LumberPhase.SKIDDING),
                true,
                events = listOf(LumberShiftEvent.PHASE_CHANGED),
            )
        }
        if (current.phase == LumberPhase.PROCESSING && current.processed >= rules.processingQuota) {
            return EngineResult(completed(current, rules, now), true, events = listOf(LumberShiftEvent.COMPLETED))
        }
        return EngineResult(current, false)
    }

    private fun progress(
        current: LumberShiftState,
        requiredPhase: LumberPhase,
        quota: Int,
        nextPhase: LumberPhase,
        playerId: UUID,
        update: (LumberShiftState, Int) -> LumberShiftState,
    ): EngineResult<LumberShiftState, LumberShiftEvent> {
        if (current.phase != requiredPhase) return EngineResult(current, false)
        val done = when (requiredPhase) {
            LumberPhase.SKIDDING -> current.skidded
            LumberPhase.SAWING -> current.sawCuts
            LumberPhase.STACKING -> current.stacked
            else -> error("Unsupported lumber progress phase: $requiredPhase")
        }
        if (done >= quota) return EngineResult(current, false)
        val next = done + 1
        var state = update(current, next).copy(contributors = contribute(current, playerId, 1))
        val events = mutableListOf(LumberShiftEvent.PROGRESS)
        if (next >= quota) {
            state = state.copy(phase = nextPhase, objective = null)
            events += LumberShiftEvent.PHASE_CHANGED
        }
        return EngineResult(state, true, 1, events)
    }

    private fun completed(state: LumberShiftState, rules: LumberRules, now: Long): LumberShiftState = state.copy(
        phase = LumberPhase.COOLDOWN,
        cooldownEndsAt = now + rules.cooldownMillis,
        outcome = ShiftOutcome.COMPLETED,
        objective = null,
    )

    private fun contribute(state: LumberShiftState, playerId: UUID, amount: Int): Map<UUID, Int> =
        incrementContribution(state.contributors, playerId, amount)

    private val FOREGROUND_PHASES = setOf(
        LumberPhase.FELLING,
        LumberPhase.SKIDDING,
        LumberPhase.SAWING,
        LumberPhase.STACKING,
        LumberPhase.DISPATCH,
    )
}

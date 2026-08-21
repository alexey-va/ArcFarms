package ru.ruscrafting.farms.domain

import java.util.UUID

enum class LumberPhase {
    IDLE,
    FELLING,
    PROCESSING,
    COOLDOWN,
}
data class LumberRules(
    val fellingQuota: Int,
    val processingQuota: Int,
    val processingPerUse: Int,
    val cooldownMillis: Long,
) {
    init {
        require(fellingQuota in 1..100_000)
        require(processingQuota in 1..100_000)
        require(processingPerUse in 1..processingQuota)
        require(cooldownMillis in 0..3_600_000)
    }
}

data class LumberShiftState(
    val phase: LumberPhase = LumberPhase.IDLE,
    val sequence: Long = 0,
    val species: String? = null,
    val felled: Int = 0,
    val processed: Int = 0,
    val startedAt: Long = 0,
    val cooldownEndsAt: Long = 0,
    val outcome: ShiftOutcome = ShiftOutcome.NONE,
    val contributors: Map<UUID, Int> = emptyMap(),
)

object LumberShiftEngine {
    fun start(
        current: LumberShiftState,
        species: String,
        rules: LumberRules,
        now: Long,
    ): EngineResult<LumberShiftState> {
        if (current.phase != LumberPhase.IDLE) return EngineResult(current, false)
        require(species.matches(Regex("[A-Z0-9_]{2,32}"))) { "Invalid lumber species: $species" }
        return EngineResult(
            LumberShiftState(
                phase = LumberPhase.FELLING,
                sequence = current.sequence + 1,
                species = species,
                startedAt = now,
            ),
            true,
            events = listOf(ShiftEvent.STARTED),
        )
    }

    fun fell(
        current: LumberShiftState,
        rules: LumberRules,
        species: String,
        playerId: UUID,
        now: Long,
    ): EngineResult<LumberShiftState> {
        val advanced = tick(current, rules, now)
        var state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase != LumberPhase.FELLING || state.species != species) {
            return EngineResult(state, false, events = events)
        }
        val nextFelled = (state.felled + 1).coerceAtMost(rules.fellingQuota)
        state = state.copy(
            felled = nextFelled,
            contributors = incrementContribution(state.contributors, playerId, 1),
        )
        events += ShiftEvent.PROGRESS
        if (nextFelled >= rules.fellingQuota) {
            state = state.copy(phase = LumberPhase.PROCESSING)
            events += ShiftEvent.PHASE_CHANGED
        }
        return EngineResult(state, true, 1, events)
    }

    fun process(
        current: LumberShiftState,
        rules: LumberRules,
        playerId: UUID,
        now: Long,
    ): EngineResult<LumberShiftState> {
        val advanced = tick(current, rules, now)
        var state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase != LumberPhase.PROCESSING) return EngineResult(state, false, events = events)
        val nextProcessed = (state.processed + rules.processingPerUse).coerceAtMost(rules.processingQuota)
        val delta = nextProcessed - state.processed
        state = state.copy(
            processed = nextProcessed,
            contributors = incrementContribution(state.contributors, playerId, delta),
        )
        events += ShiftEvent.PROGRESS
        if (nextProcessed >= rules.processingQuota) {
            state = state.copy(
                phase = LumberPhase.COOLDOWN,
                cooldownEndsAt = now + rules.cooldownMillis,
                outcome = ShiftOutcome.COMPLETED,
            )
            events += ShiftEvent.COMPLETED
        }
        return EngineResult(state, true, delta, events)
    }

    fun tick(
        current: LumberShiftState,
        rules: LumberRules,
        now: Long,
    ): EngineResult<LumberShiftState> {
        if (current.phase == LumberPhase.IDLE) return EngineResult(current, false)
        if (current.phase == LumberPhase.COOLDOWN && now >= current.cooldownEndsAt) {
            return EngineResult(LumberShiftState(sequence = current.sequence), true, events = listOf(ShiftEvent.RESET))
        }
        if (current.phase == LumberPhase.FELLING && current.felled >= rules.fellingQuota) {
            return EngineResult(
                current.copy(phase = LumberPhase.PROCESSING),
                true,
                events = listOf(ShiftEvent.PHASE_CHANGED),
            )
        }
        if (current.phase == LumberPhase.PROCESSING && current.processed >= rules.processingQuota) {
            return EngineResult(
                current.copy(
                    phase = LumberPhase.COOLDOWN,
                    cooldownEndsAt = now + rules.cooldownMillis,
                    outcome = ShiftOutcome.COMPLETED,
                ),
                true,
                events = listOf(ShiftEvent.COMPLETED),
            )
        }
        return EngineResult(current, false)
    }
}

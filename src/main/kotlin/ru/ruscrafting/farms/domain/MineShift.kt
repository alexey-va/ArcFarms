package ru.ruscrafting.farms.domain

import java.util.UUID

enum class MinePhase {
    IDLE,
    MINING,
    HAZARD,
    EXTRACTION,
    COOLDOWN,
}
data class MineRules(
    val shiftMillis: Long,
    val cartQuota: Int,
    val hazardTrigger: Int,
    val supportsRequired: Int,
    val extractionMillis: Long,
    val cooldownMillis: Long,
) {
    init {
        require(shiftMillis in 30_000..7_200_000)
        require(cartQuota in 2..100_000)
        require(hazardTrigger in 1 until cartQuota)
        require(supportsRequired in 1..32)
        require(extractionMillis in 5_000..600_000)
        require(cooldownMillis in 0..3_600_000)
    }
}

data class MineShiftState(
    val phase: MinePhase = MinePhase.IDLE,
    val sequence: Long = 0,
    val cart: Int = 0,
    val supports: Int = 0,
    val hazardResolved: Boolean = false,
    val startedAt: Long = 0,
    val deadlineAt: Long = 0,
    val extractionEndsAt: Long = 0,
    val cooldownEndsAt: Long = 0,
    val outcome: ShiftOutcome = ShiftOutcome.NONE,
    val contributors: Map<UUID, Int> = emptyMap(),
)

object MineShiftEngine {
    fun start(
        current: MineShiftState,
        rules: MineRules,
        now: Long,
    ): EngineResult<MineShiftState> {
        if (current.phase != MinePhase.IDLE) return EngineResult(current, false)
        return EngineResult(
            MineShiftState(
                phase = MinePhase.MINING,
                sequence = current.sequence + 1,
                startedAt = now,
                deadlineAt = now + rules.shiftMillis,
            ),
            true,
            events = listOf(ShiftEvent.STARTED),
        )
    }

    fun mine(
        current: MineShiftState,
        rules: MineRules,
        points: Int,
        playerId: UUID,
        now: Long,
    ): EngineResult<MineShiftState> {
        require(points in 1..32)
        val advanced = tick(current, rules, now)
        var state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase != MinePhase.MINING) return EngineResult(state, false, events = events)

        val nextCart = (state.cart + points).coerceAtMost(rules.cartQuota)
        val delta = nextCart - state.cart
        state = state.copy(
            cart = nextCart,
            contributors = incrementContribution(state.contributors, playerId, delta),
        )
        events += ShiftEvent.PROGRESS
        if (!state.hazardResolved && nextCart >= rules.hazardTrigger) {
            state = state.copy(phase = MinePhase.HAZARD, supports = 0)
            events += ShiftEvent.HAZARD_STARTED
        } else if (nextCart >= rules.cartQuota) {
            state = state.copy(phase = MinePhase.EXTRACTION, extractionEndsAt = now + rules.extractionMillis)
            events += ShiftEvent.EXTRACTION_STARTED
        }
        return EngineResult(state, true, delta, events)
    }

    fun stabilize(
        current: MineShiftState,
        rules: MineRules,
        playerId: UUID,
        now: Long,
    ): EngineResult<MineShiftState> {
        val advanced = tick(current, rules, now)
        var state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase != MinePhase.HAZARD) return EngineResult(state, false, events = events)
        val nextSupports = (state.supports + 1).coerceAtMost(rules.supportsRequired)
        state = state.copy(
            supports = nextSupports,
            contributors = incrementContribution(state.contributors, playerId, 1),
        )
        events += ShiftEvent.PROGRESS
        if (nextSupports >= rules.supportsRequired) {
            val nextPhase = if (state.cart >= rules.cartQuota) MinePhase.EXTRACTION else MinePhase.MINING
            state = state.copy(
                phase = nextPhase,
                hazardResolved = true,
                extractionEndsAt = if (nextPhase == MinePhase.EXTRACTION) now + rules.extractionMillis else 0,
            )
            events += ShiftEvent.HAZARD_RESOLVED
            if (nextPhase == MinePhase.EXTRACTION) events += ShiftEvent.EXTRACTION_STARTED
        }
        return EngineResult(state, true, 1, events)
    }

    fun extract(
        current: MineShiftState,
        rules: MineRules,
        playerId: UUID,
        now: Long,
    ): EngineResult<MineShiftState> {
        val advanced = tick(current, rules, now)
        val state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase != MinePhase.EXTRACTION || playerId !in state.contributors) {
            return EngineResult(state, false, events = events)
        }
        events += ShiftEvent.COMPLETED
        return EngineResult(
            state.copy(
                phase = MinePhase.COOLDOWN,
                extractionEndsAt = 0,
                cooldownEndsAt = now + rules.cooldownMillis,
                outcome = ShiftOutcome.COMPLETED,
            ),
            true,
            events = events,
        )
    }

    fun tick(
        current: MineShiftState,
        rules: MineRules,
        now: Long,
    ): EngineResult<MineShiftState> {
        if (current.phase == MinePhase.IDLE) return EngineResult(current, false)
        if (current.phase == MinePhase.COOLDOWN && now >= current.cooldownEndsAt) {
            return EngineResult(MineShiftState(sequence = current.sequence), true, events = listOf(ShiftEvent.RESET))
        }
        val timedOut = now >= current.deadlineAt ||
            (current.phase == MinePhase.EXTRACTION && now >= current.extractionEndsAt)
        if (current.phase in setOf(MinePhase.MINING, MinePhase.HAZARD, MinePhase.EXTRACTION) && timedOut) {
            return EngineResult(
                current.copy(
                    phase = MinePhase.COOLDOWN,
                    extractionEndsAt = 0,
                    cooldownEndsAt = now + rules.cooldownMillis,
                    outcome = ShiftOutcome.TIMED_OUT,
                ),
                true,
                events = listOf(ShiftEvent.TIMED_OUT),
            )
        }
        return EngineResult(current, false)
    }
}

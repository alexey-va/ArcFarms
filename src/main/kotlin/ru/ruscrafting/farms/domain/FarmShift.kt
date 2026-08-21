package ru.ruscrafting.farms.domain

import java.util.UUID

enum class FarmPhase {
    IDLE,
    HARVESTING,
    GOLDEN_HARVEST,
    COOLDOWN,
}
data class FarmOrder(
    val id: String,
    val required: Map<String, Int>,
) {
    init {
        require(id.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid farm order id: $id" }
        require(required.isNotEmpty()) { "Farm order $id must require crops" }
        require(required.size <= 12) { "Farm order $id has too many crops" }
        require(required.values.all { it in 1..100_000 }) { "Farm order $id has an invalid crop quota" }
    }

    val totalRequired: Int = required.values.sum()
}

data class FarmRules(
    val shiftMillis: Long,
    val goldenTriggerPercent: Int,
    val goldenWindowMillis: Long,
    val cooldownMillis: Long,
) {
    init {
        require(shiftMillis in 30_000..7_200_000)
        require(goldenTriggerPercent in 1..99)
        require(goldenWindowMillis in 5_000..600_000)
        require(cooldownMillis in 0..3_600_000)
    }
}

data class FarmShiftState(
    val phase: FarmPhase = FarmPhase.IDLE,
    val sequence: Long = 0,
    val orderId: String? = null,
    val progress: Map<String, Int> = emptyMap(),
    val goldenCrop: String? = null,
    val goldenUsed: Boolean = false,
    val startedAt: Long = 0,
    val deadlineAt: Long = 0,
    val goldenEndsAt: Long = 0,
    val cooldownEndsAt: Long = 0,
    val outcome: ShiftOutcome = ShiftOutcome.NONE,
    val contributors: Map<UUID, Int> = emptyMap(),
) {
    fun completed(order: FarmOrder): Int = order.required.entries.sumOf { (crop, amount) ->
        (progress[crop] ?: 0).coerceAtMost(amount)
    }

    fun progressRatio(order: FarmOrder): Double = completed(order).toDouble() / order.totalRequired.toDouble()
}

object FarmShiftEngine {
    fun start(
        current: FarmShiftState,
        order: FarmOrder,
        rules: FarmRules,
        now: Long,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.IDLE) return EngineResult(current, false)
        val next = FarmShiftState(
            phase = FarmPhase.HARVESTING,
            sequence = current.sequence + 1,
            orderId = order.id,
            progress = order.required.keys.associateWith { 0 },
            startedAt = now,
            deadlineAt = now + rules.shiftMillis,
        )
        return EngineResult(next, true, events = listOf(ShiftEvent.STARTED))
    }

    fun harvest(
        current: FarmShiftState,
        order: FarmOrder,
        rules: FarmRules,
        crop: String,
        playerId: UUID,
        now: Long,
    ): EngineResult<FarmShiftState> {
        val advanced = tick(current, order, rules, now)
        var state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase !in setOf(FarmPhase.HARVESTING, FarmPhase.GOLDEN_HARVEST)) {
            return EngineResult(state, false, events = events)
        }
        val required = order.required[crop] ?: return EngineResult(state, false, events = events)
        val before = state.progress[crop] ?: 0
        if (before >= required) return EngineResult(state, false, events = events)

        val multiplier = if (state.phase == FarmPhase.GOLDEN_HARVEST && state.goldenCrop == crop) 2 else 1
        val after = (before + multiplier).coerceAtMost(required)
        val delta = after - before
        state = state.copy(
            progress = state.progress + (crop to after),
            contributors = incrementContribution(state.contributors, playerId, delta),
        )
        events += ShiftEvent.PROGRESS

        if (state.completed(order) >= order.totalRequired) {
            state = state.copy(
                phase = FarmPhase.COOLDOWN,
                goldenCrop = null,
                goldenEndsAt = 0,
                cooldownEndsAt = now + rules.cooldownMillis,
                outcome = ShiftOutcome.COMPLETED,
            )
            events += ShiftEvent.COMPLETED
            return EngineResult(state, true, delta, events)
        }

        val triggerReached = state.completed(order) * 100 >= order.totalRequired * rules.goldenTriggerPercent
        if (!state.goldenUsed && triggerReached) {
            val goldenCrop = order.required.entries
                .filter { (candidate, amount) -> (state.progress[candidate] ?: 0) < amount }
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value - (state.progress[it.key] ?: 0) }.thenByDescending { it.key })
                ?.key
            if (goldenCrop != null) {
                state = state.copy(
                    phase = FarmPhase.GOLDEN_HARVEST,
                    goldenCrop = goldenCrop,
                    goldenUsed = true,
                    goldenEndsAt = now + rules.goldenWindowMillis,
                )
                events += ShiftEvent.GOLDEN_STARTED
            }
        }
        return EngineResult(state, true, delta, events)
    }

    fun tick(
        current: FarmShiftState,
        order: FarmOrder?,
        rules: FarmRules,
        now: Long,
    ): EngineResult<FarmShiftState> {
        if (current.phase == FarmPhase.IDLE) return EngineResult(current, false)
        if (current.phase == FarmPhase.COOLDOWN && now >= current.cooldownEndsAt) {
            return EngineResult(FarmShiftState(sequence = current.sequence), true, events = listOf(ShiftEvent.RESET))
        }
        if (current.phase in setOf(FarmPhase.HARVESTING, FarmPhase.GOLDEN_HARVEST) && now >= current.deadlineAt) {
            return EngineResult(
                current.copy(
                    phase = FarmPhase.COOLDOWN,
                    goldenCrop = null,
                    goldenEndsAt = 0,
                    cooldownEndsAt = now + rules.cooldownMillis,
                    outcome = ShiftOutcome.TIMED_OUT,
                ),
                true,
                events = listOf(ShiftEvent.TIMED_OUT),
            )
        }
        if (current.phase == FarmPhase.GOLDEN_HARVEST && now >= current.goldenEndsAt) {
            return EngineResult(
                current.copy(phase = FarmPhase.HARVESTING, goldenCrop = null, goldenEndsAt = 0),
                true,
                events = listOf(ShiftEvent.GOLDEN_ENDED),
            )
        }
        if (order != null && current.completed(order) >= order.totalRequired) {
            return EngineResult(current, false)
        }
        return EngineResult(current, false)
    }
}

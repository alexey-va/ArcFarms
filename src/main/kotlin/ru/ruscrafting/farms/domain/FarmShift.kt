package ru.ruscrafting.farms.domain

import java.util.UUID

enum class FarmPhase {
    IDLE,
    HARVESTING,
    INCIDENT,
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
    val incidentTriggerPercent: Int,
    val incidentQuota: Int,
    val goldenWindowMillis: Long,
    val cooldownMillis: Long,
) {
    init {
        require(incidentTriggerPercent in 1..99)
        require(incidentQuota in 1..64)
        require(goldenWindowMillis in 5_000..600_000)
        require(cooldownMillis in 0..3_600_000)
    }
}

data class FarmShiftState(
    val phase: FarmPhase = FarmPhase.IDLE,
    val sequence: Long = 0,
    val orderId: String? = null,
    val progress: Map<String, Int> = emptyMap(),
    val incidentCrop: String? = null,
    val incidentProgress: Int = 0,
    val incidentRequired: Int = 0,
    val incidentResolved: Boolean = false,
    val goldenCrop: String? = null,
    val goldenUsed: Boolean = false,
    val startedAt: Long = 0,
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
        if (state.phase !in setOf(FarmPhase.HARVESTING, FarmPhase.INCIDENT, FarmPhase.GOLDEN_HARVEST)) {
            return EngineResult(state, false, events = events)
        }
        if (state.phase == FarmPhase.INCIDENT) return EngineResult(state, false, events = events)
        val required = order.required[crop] ?: return EngineResult(state, false, events = events)
        val before = state.progress[crop] ?: 0
        if (before >= required) return EngineResult(state, false, events = events)

        val multiplier = if (state.phase == FarmPhase.GOLDEN_HARVEST && state.goldenCrop == crop) 2 else 1
        val after = (before + multiplier).coerceAtMost(required)
        val orderDelta = after - before
        val contribution = orderDelta
        state = state.copy(
            progress = state.progress + (crop to after),
            contributors = incrementContribution(state.contributors, playerId, contribution),
        )
        events += ShiftEvent.PROGRESS

        if (state.completed(order) >= order.totalRequired && state.phase != FarmPhase.INCIDENT) {
            state = state.copy(
                phase = FarmPhase.COOLDOWN,
                incidentCrop = null,
                goldenCrop = null,
                goldenEndsAt = 0,
                cooldownEndsAt = now + rules.cooldownMillis,
                outcome = ShiftOutcome.COMPLETED,
            )
            events += ShiftEvent.COMPLETED
            return EngineResult(state, true, contribution, events)
        }

        val triggerReached = state.completed(order) * 100 >= order.totalRequired * rules.incidentTriggerPercent
        if (!state.incidentResolved && state.incidentCrop == null && triggerReached) {
            val incidentCrop = remainingCrop(state, order)
            if (incidentCrop != null) {
                state = state.copy(
                    phase = FarmPhase.INCIDENT,
                    incidentCrop = incidentCrop,
                    incidentProgress = 0,
                    incidentRequired = rules.incidentQuota,
                )
                events += ShiftEvent.INCIDENT_STARTED
            }
        }
        return EngineResult(state, true, contribution, events)
    }

    fun defeatPest(
        current: FarmShiftState,
        order: FarmOrder,
        rules: FarmRules,
        playerId: UUID,
        now: Long,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.INCIDENT || current.incidentProgress >= current.incidentRequired) {
            return EngineResult(current, false)
        }
        var state = current.copy(
            incidentProgress = current.incidentProgress + 1,
            contributors = incrementContribution(current.contributors, playerId, 1),
        )
        val events = mutableListOf(ShiftEvent.INCIDENT_PROGRESS)
        if (state.incidentProgress >= state.incidentRequired) {
            state = state.copy(
                phase = FarmPhase.HARVESTING,
                incidentResolved = true,
            )
            events += ShiftEvent.INCIDENT_RESOLVED
            remainingCrop(state, order)?.let { goldenCrop ->
                state = state.copy(
                    phase = FarmPhase.GOLDEN_HARVEST,
                    goldenCrop = goldenCrop,
                    goldenUsed = true,
                    goldenEndsAt = now + rules.goldenWindowMillis,
                )
                events += ShiftEvent.GOLDEN_STARTED
            }
        }
        return EngineResult(state, true, contribution = 1, events = events)
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
        if (current.phase == FarmPhase.COOLDOWN) return EngineResult(current, false)
        if (current.phase == FarmPhase.GOLDEN_HARVEST && now >= current.goldenEndsAt) {
            return EngineResult(
                current.copy(phase = FarmPhase.HARVESTING, goldenCrop = null, goldenEndsAt = 0),
                true,
                events = listOf(ShiftEvent.GOLDEN_ENDED),
            )
        }
        if (order != null && current.completed(order) >= order.totalRequired) {
            if (current.phase == FarmPhase.INCIDENT) return EngineResult(current, false)
            return EngineResult(
                current.copy(
                    phase = FarmPhase.COOLDOWN,
                    incidentCrop = null,
                    goldenCrop = null,
                    goldenEndsAt = 0,
                    cooldownEndsAt = now + rules.cooldownMillis,
                    outcome = ShiftOutcome.COMPLETED,
                ),
                true,
                events = listOf(ShiftEvent.COMPLETED),
            )
        }
        return EngineResult(current, false)
    }

    private fun remainingCrop(state: FarmShiftState, order: FarmOrder): String? = order.required.entries
        .filter { (candidate, amount) -> (state.progress[candidate] ?: 0) < amount }
        .maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value - (state.progress[it.key] ?: 0) }
                .thenByDescending { it.key },
        )
        ?.key
}

package ru.ruscrafting.farms.domain

import java.util.UUID

enum class FarmPhase {
    IDLE,
    PREPARATION,
    PLANTING,
    HARVESTING,
    INCIDENT,
    GOLDEN_HARVEST,
    DELIVERY,
    COOLDOWN,
}

data class FarmPlotPosition(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        require(world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid farm plot world: $world" }
        require(x in -30_000_000..30_000_000 && z in -30_000_000..30_000_000) {
            "Farm plot position is outside the world border"
        }
        require(y in -4_096..4_096) { "Farm plot height is invalid" }
    }
}

enum class FarmIncidentType {
    PESTS,
    DROUGHT,
}

data class FarmDeliveryPosition(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid delivery world: $world" }
        require(listOf(x, y, z).all(Double::isFinite)) { "Delivery position must be finite" }
    }
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
    val preparationPatch: List<FarmPlotPosition> = emptyList(),
    val preparationCrop: String? = null,
    val preparationReleased: Boolean = false,
    val tilledPlots: Set<FarmPlotPosition> = emptySet(),
    val plantedPlots: Set<FarmPlotPosition> = emptySet(),
    val preparationProgress: Int = 0,
    val plantingProgress: Int = 0,
    val preparationRequired: Int = 0,
    val incidentCrop: String? = null,
    val incidentType: FarmIncidentType? = null,
    val incidentProgress: Int = 0,
    val incidentRequired: Int = 0,
    val incidentResolved: Boolean = false,
    val goldenCrop: String? = null,
    val goldenUsed: Boolean = false,
    val startedAt: Long = 0,
    val goldenEndsAt: Long = 0,
    val cooldownEndsAt: Long = 0,
    val deliveryPosition: FarmDeliveryPosition? = null,
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
        patch: List<FarmPlotPosition>,
        preparationCrop: String,
        now: Long,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.IDLE) return EngineResult(current, false)
        require(patch.isNotEmpty() && patch.size <= 512) { "Farm preparation patch must contain 1..512 plots" }
        require(patch.distinct().size == patch.size) { "Farm preparation patch contains duplicate plots" }
        require(patch.map(FarmPlotPosition::world).distinct().size == 1) { "Farm preparation patch crosses worlds" }
        require(preparationCrop in order.required) { "Farm preparation crop is outside order ${order.id}" }
        val next = FarmShiftState(
            phase = FarmPhase.PREPARATION,
            sequence = current.sequence + 1,
            orderId = order.id,
            progress = order.required.keys.associateWith { 0 },
            preparationPatch = patch,
            preparationCrop = preparationCrop,
            preparationRequired = patch.size,
            startedAt = now,
        )
        return EngineResult(next, true, events = listOf(ShiftEvent.STARTED))
    }

    fun till(
        current: FarmShiftState,
        plot: FarmPlotPosition,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        if (
            current.phase != FarmPhase.PREPARATION || plot !in current.preparationPatch ||
            plot in current.tilledPlots || current.preparationProgress >= current.preparationRequired
        ) {
            return EngineResult(current, false)
        }
        val progress = current.preparationProgress + 1
        val completed = progress >= current.preparationRequired
        val state = current.copy(
            phase = if (completed) FarmPhase.PLANTING else FarmPhase.PREPARATION,
            tilledPlots = current.tilledPlots + plot,
            preparationProgress = progress,
            contributors = incrementContribution(current.contributors, playerId, 1),
        )
        return EngineResult(
            state,
            true,
            contribution = 1,
            events = buildList {
                add(ShiftEvent.PREPARATION_PROGRESS)
                if (completed) add(ShiftEvent.PLANTING_STARTED)
            },
        )
    }

    fun plant(
        current: FarmShiftState,
        plot: FarmPlotPosition,
        crop: String,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        if (
            current.phase != FarmPhase.PLANTING || crop != current.preparationCrop ||
            plot !in current.preparationPatch || plot !in current.tilledPlots || plot in current.plantedPlots ||
            current.plantingProgress >= current.preparationRequired
        ) {
            return EngineResult(current, false)
        }
        val progress = current.plantingProgress + 1
        val completed = progress >= current.preparationRequired
        val state = current.copy(
            phase = if (completed) FarmPhase.HARVESTING else FarmPhase.PLANTING,
            plantedPlots = current.plantedPlots + plot,
            plantingProgress = progress,
            contributors = incrementContribution(current.contributors, playerId, 1),
        )
        return EngineResult(
            state,
            true,
            contribution = 1,
            events = buildList {
                add(ShiftEvent.PLANTING_PROGRESS)
                if (completed) add(ShiftEvent.PREPARATION_COMPLETED)
            },
        )
    }

    fun harvest(
        current: FarmShiftState,
        order: FarmOrder,
        rules: FarmRules,
        crop: String,
        playerId: UUID,
        now: Long,
        incidentType: FarmIncidentType = FarmIncidentType.PESTS,
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
                phase = FarmPhase.DELIVERY,
                incidentCrop = null,
                incidentType = null,
                goldenCrop = null,
                goldenEndsAt = 0,
                deliveryPosition = null,
            )
            events += ShiftEvent.DELIVERY_STARTED
            return EngineResult(state, true, contribution, events)
        }

        val triggerReached = state.completed(order) * 100 >= order.totalRequired * rules.incidentTriggerPercent
        if (!state.incidentResolved && state.incidentCrop == null && triggerReached) {
            val incidentCrop = remainingCrop(state, order)
            if (incidentCrop != null) {
                state = state.copy(
                    phase = FarmPhase.INCIDENT,
                    incidentCrop = incidentCrop,
                    incidentType = incidentType,
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
    ): EngineResult<FarmShiftState> = resolveIncident(
        current,
        order,
        rules,
        FarmIncidentType.PESTS,
        playerId,
        now,
    )

    fun waterDrySoil(
        current: FarmShiftState,
        order: FarmOrder,
        rules: FarmRules,
        playerId: UUID,
        now: Long,
    ): EngineResult<FarmShiftState> = resolveIncident(
        current,
        order,
        rules,
        FarmIncidentType.DROUGHT,
        playerId,
        now,
    )

    private fun resolveIncident(
        current: FarmShiftState,
        order: FarmOrder,
        rules: FarmRules,
        expectedType: FarmIncidentType,
        playerId: UUID,
        now: Long,
    ): EngineResult<FarmShiftState> {
        val actualType = current.incidentType ?: FarmIncidentType.PESTS
        if (
            current.phase != FarmPhase.INCIDENT || actualType != expectedType ||
            current.incidentProgress >= current.incidentRequired
        ) {
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
                incidentType = null,
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

    fun deliver(
        current: FarmShiftState,
        rules: FarmRules,
        playerId: UUID,
        now: Long,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.DELIVERY) return EngineResult(current, false)
        return EngineResult(
            current.copy(
                phase = FarmPhase.COOLDOWN,
                cooldownEndsAt = now + rules.cooldownMillis,
                deliveryPosition = null,
                outcome = ShiftOutcome.COMPLETED,
                contributors = incrementContribution(current.contributors, playerId, 1),
            ),
            true,
            contribution = 1,
            events = listOf(ShiftEvent.COMPLETED),
        )
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
        if (current.phase == FarmPhase.DELIVERY) return EngineResult(current, false)
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
                    phase = FarmPhase.DELIVERY,
                    incidentCrop = null,
                    incidentType = null,
                    goldenCrop = null,
                    goldenEndsAt = 0,
                    deliveryPosition = null,
                ),
                true,
                events = listOf(ShiftEvent.DELIVERY_STARTED),
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

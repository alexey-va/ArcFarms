package ru.ruscrafting.farms.domain

import java.util.UUID

enum class MineShiftEvent {
    STARTED, PROGRESS, PHASE_CHANGED, INCIDENT_STARTED, INCIDENT_RESOLVED,
    HAZARD_STARTED, HAZARD_RESOLVED, EXTRACTION_STARTED, COMPLETED, RESET,
}

enum class MinePhase {
    IDLE, PROSPECTING, MINING, LOADING,
    /** Deserialization-only V1 phase retained for the migration release. */
    HAZARD,
    EXTRACTION, INCIDENT, COOLDOWN,
}

enum class MineIncidentType {
    CAVE_IN, GAS_LEAK, FLOODING, TRACK_DAMAGE, CRYSTAL_RESONANCE, CREATURE_NEST, POWER_FAILURE, LOST_MINER,
}

data class MineOrder(val id: String, val incidents: List<MineIncidentType>) {
    init {
        require(DomainIdentifiers.isOrder(id)) { "Invalid mine order id: $id" }
        require(incidents.size in 1..8 && incidents.distinct().size == incidents.size) {
            "Mine order must contain one to eight distinct incidents"
        }
    }
}

data class MineRules(
    val cartQuota: Int,
    val hazardTrigger: Int,
    val supportsRequired: Int,
    val cooldownMillis: Long,
    val prospectingQuota: Int = 1,
    val miningQuota: Int = cartQuota,
    val loadingQuota: Int = 1,
    val targetMultiplier: Int = 2,
    val miningOnly: Boolean = false,
    val incidentCountMin: Int = 3,
    val incidentCountMax: Int = 5,
) {
    init {
        require(cartQuota in 2..100_000)
        require(hazardTrigger in 1 until cartQuota)
        require(supportsRequired in 1..32)
        require(cooldownMillis in 0..3_600_000)
        require(prospectingQuota in 1..100_000 && miningQuota in 1..100_000 && loadingQuota in 1..100_000)
        require(targetMultiplier in 2..4)
        require(incidentCountMin in 1..5 && incidentCountMax in incidentCountMin..5)
    }
}

data class MineIncidentState(
    val type: MineIncidentType,
    val required: Int,
    val progress: Int = 0,
    val objectiveNonce: Long = 0L,
    val startedAt: Long = 0L,
    val serviceLeases: Map<String, UUID> = emptyMap(),
) {
    init {
        require(required in 1..100_000 && progress in 0..required)
        require(objectiveNonce >= 0L && startedAt >= 0L)
    }
}

data class MineShiftState(
    val engineVersion: Int = 1,
    val phase: MinePhase = MinePhase.IDLE,
    val sequence: Long = 0,
    val orderId: String? = null,
    val prospected: Int = 0,
    val mined: Int = 0,
    val loaded: Int = 0,
    val routeIndex: Int = 0,
    /** V1 cart/support fields remain readable for one migration release. */
    val cart: Int = 0,
    val supports: Int = 0,
    val hazardResolved: Boolean = false,
    val startedAt: Long = 0,
    val cooldownEndsAt: Long = 0,
    val outcome: ShiftOutcome = ShiftOutcome.NONE,
    val contributors: Map<UUID, Int> = emptyMap(),
    val incidentSchedule: List<MineIncidentType> = emptyList(),
    val incidentCursor: Int = 0,
    val resumePhase: MinePhase? = null,
    val resumeObjective: ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState? = null,
    val incident: MineIncidentState? = null,
    val objective: ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState? = null,
)

object MineStateMigration {
    fun migrate(current: MineShiftState): MineShiftState = when {
        current.phase == MinePhase.COOLDOWN -> current
        current.phase == MinePhase.IDLE -> current.copy(engineVersion = 2)
        current.engineVersion >= 2 && current.phase != MinePhase.HAZARD -> current
        else -> MineShiftState(engineVersion = 2, sequence = current.sequence)
    }
}

object MineShiftEngine {
    /** V1 entry point retained behind MineController. */
    fun start(current: MineShiftState, rules: MineRules, now: Long): EngineResult<MineShiftState, MineShiftEvent> {
        if (current.phase != MinePhase.IDLE) return EngineResult(current, false)
        return EngineResult(
            MineShiftState(phase = MinePhase.MINING, sequence = current.sequence + 1, startedAt = now),
            true,
            events = listOf(MineShiftEvent.STARTED),
        )
    }

    fun start(current: MineShiftState, order: MineOrder, rules: MineRules, now: Long): EngineResult<MineShiftState, MineShiftEvent> {
        if (current.phase != MinePhase.IDLE) return EngineResult(current, false)
        val sequence = current.sequence + 1
        val countRange = rules.incidentCountMax - rules.incidentCountMin + 1
        val count = rules.incidentCountMin + (sequence % countRange).toInt()
        require(order.incidents.size >= count) { "Mine order ${order.id} has fewer incidents than required" }
        val offset = (sequence % order.incidents.size).toInt()
        val schedule = order.incidents.indices
            .map { order.incidents[(offset + it) % order.incidents.size] }
            .take(count)
            .sortedBy(MineIncidentType::ordinal)
        return EngineResult(
            MineShiftState(
                engineVersion = 2,
                phase = if (rules.miningOnly) MinePhase.MINING else MinePhase.PROSPECTING,
                sequence = sequence,
                orderId = order.id,
                startedAt = now,
                incidentSchedule = schedule,
            ),
            true,
            events = listOf(MineShiftEvent.STARTED),
        )
    }

    fun prospect(current: MineShiftState, rules: MineRules, playerId: UUID): EngineResult<MineShiftState, MineShiftEvent> =
        progress(current, MinePhase.PROSPECTING, current.prospected, rules.prospectingQuota, MinePhase.MINING, playerId) { state, next ->
            state.copy(prospected = next)
        }

    fun mineTarget(current: MineShiftState, rules: MineRules, playerId: UUID): EngineResult<MineShiftState, MineShiftEvent> =
        progress(current, MinePhase.MINING, current.mined, rules.miningQuota, if (rules.miningOnly) MinePhase.EXTRACTION else MinePhase.LOADING, playerId) { state, next ->
            state.copy(mined = next, cart = next)
        }

    fun load(current: MineShiftState, rules: MineRules, playerId: UUID): EngineResult<MineShiftState, MineShiftEvent> =
        progress(current, MinePhase.LOADING, current.loaded, rules.loadingQuota, MinePhase.EXTRACTION, playerId) { state, next ->
            state.copy(loaded = next)
        }

    fun advanceRoute(current: MineShiftState, finalIndex: Int, playerId: UUID): EngineResult<MineShiftState, MineShiftEvent> {
        require(finalIndex >= 1)
        if (current.phase != MinePhase.EXTRACTION || current.routeIndex >= finalIndex) return EngineResult(current, false)
        val next = current.routeIndex + 1
        return EngineResult(
            current.copy(routeIndex = next, contributors = incrementContribution(current.contributors, playerId, 1)),
            true,
            contribution = 1,
            events = listOf(MineShiftEvent.PROGRESS),
        )
    }

    /** V1 mining transition retained for MineController. */
    fun mine(
        current: MineShiftState,
        rules: MineRules,
        points: Int,
        playerId: UUID,
        now: Long,
    ): EngineResult<MineShiftState, MineShiftEvent> {
        require(points in 1..32)
        val advanced = tick(current, rules, now)
        var state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase != MinePhase.MINING || state.orderId != null) return EngineResult(state, false, events = events)
        val nextCart = (state.cart + points).coerceAtMost(rules.cartQuota)
        val delta = nextCart - state.cart
        state = state.copy(cart = nextCart, contributors = incrementContribution(state.contributors, playerId, delta))
        events += MineShiftEvent.PROGRESS
        if (!state.hazardResolved && nextCart >= rules.hazardTrigger) {
            state = state.copy(phase = MinePhase.HAZARD, supports = 0)
            events += MineShiftEvent.HAZARD_STARTED
        } else if (nextCart >= rules.cartQuota) {
            state = state.copy(phase = MinePhase.EXTRACTION)
            events += MineShiftEvent.EXTRACTION_STARTED
        }
        return EngineResult(state, true, delta, events)
    }

    fun stabilize(
        current: MineShiftState,
        rules: MineRules,
        playerId: UUID,
        now: Long,
    ): EngineResult<MineShiftState, MineShiftEvent> {
        val advanced = tick(current, rules, now)
        var state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase != MinePhase.HAZARD) return EngineResult(state, false, events = events)
        val nextSupports = (state.supports + 1).coerceAtMost(rules.supportsRequired)
        state = state.copy(supports = nextSupports, contributors = incrementContribution(state.contributors, playerId, 1))
        events += MineShiftEvent.PROGRESS
        if (nextSupports >= rules.supportsRequired) {
            val nextPhase = if (state.cart >= rules.cartQuota) MinePhase.EXTRACTION else MinePhase.MINING
            state = state.copy(phase = nextPhase, hazardResolved = true)
            events += MineShiftEvent.HAZARD_RESOLVED
            if (nextPhase == MinePhase.EXTRACTION) events += MineShiftEvent.EXTRACTION_STARTED
        }
        return EngineResult(state, true, 1, events)
    }

    fun startIncident(
        current: MineShiftState,
        type: MineIncidentType,
        required: Int,
        now: Long,
    ): EngineResult<MineShiftState, MineShiftEvent> {
        if (current.phase !in V2_FOREGROUND || current.incident != null) return EngineResult(current, false)
        return EngineResult(
            current.copy(
                phase = MinePhase.INCIDENT,
                resumePhase = current.phase,
                resumeObjective = current.objective,
                incident = MineIncidentState(type, required, objectiveNonce = current.incidentCursor.toLong() + 1L, startedAt = now),
                objective = null,
            ),
            true,
            events = listOf(MineShiftEvent.INCIDENT_STARTED),
        )
    }

    fun workIncident(current: MineShiftState, playerId: UUID, amount: Int = 1): EngineResult<MineShiftState, MineShiftEvent> {
        val incident = current.incident ?: return EngineResult(current, false)
        if (current.phase != MinePhase.INCIDENT || amount <= 0 || incident.progress >= incident.required) return EngineResult(current, false)
        val next = (incident.progress.toLong() + amount).coerceAtMost(incident.required.toLong()).toInt()
        val delta = next - incident.progress
        return EngineResult(
            current.copy(
                incident = incident.copy(progress = next),
                contributors = incrementContribution(current.contributors, playerId, delta),
            ),
            true,
            contribution = delta,
            events = listOf(MineShiftEvent.PROGRESS),
        )
    }

    fun resolveIncident(current: MineShiftState): EngineResult<MineShiftState, MineShiftEvent> {
        val incident = current.incident ?: return EngineResult(current, false)
        val resume = current.resumePhase ?: return EngineResult(current, false)
        if (current.phase != MinePhase.INCIDENT || incident.progress < incident.required) return EngineResult(current, false)
        return EngineResult(
            current.copy(
                phase = resume,
                resumePhase = null,
                incident = null,
                incidentCursor = current.incidentCursor + 1,
                objective = current.resumeObjective,
                resumeObjective = null,
            ),
            true,
            events = listOf(MineShiftEvent.INCIDENT_RESOLVED),
        )
    }

    fun extract(
        current: MineShiftState,
        rules: MineRules,
        playerId: UUID,
        now: Long,
    ): EngineResult<MineShiftState, MineShiftEvent> {
        val advanced = tick(current, rules, now)
        val state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase != MinePhase.EXTRACTION) return EngineResult(state, false, events = events)
        events += MineShiftEvent.COMPLETED
        return EngineResult(
            state.copy(
                phase = MinePhase.COOLDOWN,
                cooldownEndsAt = now + rules.cooldownMillis,
                outcome = ShiftOutcome.COMPLETED,
                contributors = incrementContribution(state.contributors, playerId, 1),
                objective = null,
            ),
            true,
            contribution = 1,
            events = events,
        )
    }

    fun tick(current: MineShiftState, rules: MineRules, now: Long): EngineResult<MineShiftState, MineShiftEvent> {
        if (current.phase == MinePhase.IDLE) return EngineResult(current, false)
        if (current.phase == MinePhase.COOLDOWN && now >= current.cooldownEndsAt) {
            return EngineResult(
                MineShiftState(engineVersion = current.engineVersion, sequence = current.sequence),
                true,
                events = listOf(MineShiftEvent.RESET),
            )
        }
        if (current.orderId == null && current.phase == MinePhase.MINING && !current.hazardResolved && current.cart >= rules.hazardTrigger) {
            return EngineResult(current.copy(phase = MinePhase.HAZARD, supports = 0), true, events = listOf(MineShiftEvent.HAZARD_STARTED))
        }
        if (current.orderId == null && current.phase == MinePhase.MINING && current.hazardResolved && current.cart >= rules.cartQuota) {
            return EngineResult(current.copy(phase = MinePhase.EXTRACTION), true, events = listOf(MineShiftEvent.EXTRACTION_STARTED))
        }
        return EngineResult(current, false)
    }

    private fun progress(
        current: MineShiftState,
        requiredPhase: MinePhase,
        done: Int,
        quota: Int,
        nextPhase: MinePhase,
        playerId: UUID,
        update: (MineShiftState, Int) -> MineShiftState,
    ): EngineResult<MineShiftState, MineShiftEvent> {
        if (current.phase != requiredPhase || done >= quota) return EngineResult(current, false)
        val next = done + 1
        var state = update(current, next).copy(contributors = incrementContribution(current.contributors, playerId, 1))
        val events = mutableListOf(MineShiftEvent.PROGRESS)
        if (next >= quota) {
            state = state.copy(phase = nextPhase, objective = null)
            events += MineShiftEvent.PHASE_CHANGED
            if (nextPhase == MinePhase.EXTRACTION) events += MineShiftEvent.EXTRACTION_STARTED
        }
        return EngineResult(state, true, 1, events)
    }

    private val V2_FOREGROUND = setOf(MinePhase.PROSPECTING, MinePhase.MINING, MinePhase.LOADING, MinePhase.EXTRACTION)
}

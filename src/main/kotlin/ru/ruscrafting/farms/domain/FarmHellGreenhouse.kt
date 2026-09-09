package ru.ruscrafting.farms.domain

import java.util.UUID

/** Tunables for the finite, state-only hell greenhouse expedition. */
data class FarmHellGreenhouseRules(
    val quota: Int = 4,
    val growSeconds: Int = 6,
    val hotSeconds: Int = 6,
    val heatLimit: Int = 100,
    val heatPerHarvest: Int = 10,
    val evacuationSeconds: Int = 15,
) {
    init {
        require(quota in 1..16) { "Hell greenhouse quota must be in 1..16" }
        require(growSeconds in 0..86_400) { "Hell greenhouse growth seconds are invalid" }
        require(hotSeconds in 1..86_400) { "Hell greenhouse hot seconds are invalid" }
        require(heatLimit in 1..1_000_000) { "Hell greenhouse heat limit is invalid" }
        require(heatPerHarvest in 0..1_000_000) { "Hell greenhouse harvest heat is invalid" }
        require(evacuationSeconds in 1..86_400) { "Hell greenhouse evacuation seconds are invalid" }
    }
}

enum class FarmHellHazardPhase { WARNING, ACTIVE, REST }

enum class FarmHellHazardSide { LEFT, RIGHT, NONE }

data class FarmHellHazard(
    val phase: FarmHellHazardPhase,
    val side: FarmHellHazardSide,
    val secondsRemaining: Int,
)

data class FarmHellPepper(
    val index: Int,
    val expiresAt: Int,
) {
    init {
        require(index >= 0) { "Hell greenhouse pepper index must be non-negative" }
        require(expiresAt >= 0) { "Hell greenhouse pepper expiry must be non-negative" }
    }
}

data class FarmHellGreenhouseState(
    val points: List<FarmPointPosition>,
    val elapsedSeconds: Int = 0,
    val heat: Int = 0,
    val harvested: Set<Int> = emptySet(),
    val cooled: Int = 0,
    val carried: Map<UUID, FarmHellPepper> = emptyMap(),
    val evacuationSeconds: Int? = null,
    val finished: Boolean = false,
    val entrance: FarmPointPosition? = null,
)

data class FarmHellGreenhouseResult(
    val state: FarmHellGreenhouseState,
    val accepted: Boolean,
    val contribution: Int = 0,
    val expiredPlayerIds: Set<UUID> = emptySet(),
    val finished: Boolean = false,
    val timedOut: Boolean = false,
    val successful: Boolean = false,
)

object FarmHellGreenhouseEngine {
    private const val HAZARD_WARNING_SECONDS = 3
    private const val HAZARD_ACTIVE_SECONDS = 2
    private const val HAZARD_REST_SECONDS = 3
    private const val HAZARD_CYCLE_SECONDS = HAZARD_WARNING_SECONDS + HAZARD_ACTIVE_SECONDS + HAZARD_REST_SECONDS
    private val POINT_WORLD_ID = Regex("[A-Za-z0-9._-]{1,128}")

    fun initialize(current: FarmHellGreenhouseState, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        val normalized = normalize(current, rules)
        validate(normalized)
        require(rules.quota <= normalized.points.size) { "Hell greenhouse quota must not exceed points" }
        val next = if (normalized.cooled >= rules.quota) normalized.copy(finished = true, carried = emptyMap()) else normalized
        return FarmHellGreenhouseResult(next, accepted = true, finished = next.finished, successful = next.finished)
    }

    fun hazard(state: FarmHellGreenhouseState): FarmHellHazard {
        require(state.elapsedSeconds >= 0) { "Hell greenhouse elapsed seconds must be non-negative" }
        val offset = state.elapsedSeconds % HAZARD_CYCLE_SECONDS
        val side = if ((state.elapsedSeconds / HAZARD_CYCLE_SECONDS) % 2 == 0) {
            FarmHellHazardSide.LEFT
        } else {
            FarmHellHazardSide.RIGHT
        }
        return when {
            offset < HAZARD_WARNING_SECONDS -> FarmHellHazard(
                FarmHellHazardPhase.WARNING,
                side,
                HAZARD_WARNING_SECONDS - offset,
            )
            offset < HAZARD_WARNING_SECONDS + HAZARD_ACTIVE_SECONDS -> FarmHellHazard(
                FarmHellHazardPhase.ACTIVE,
                side,
                HAZARD_WARNING_SECONDS + HAZARD_ACTIVE_SECONDS - offset,
            )
            else -> FarmHellHazard(
                FarmHellHazardPhase.REST,
                FarmHellHazardSide.NONE,
                HAZARD_CYCLE_SECONDS - offset,
            )
        }
    }

    fun validate(state: FarmHellGreenhouseState) {
        require(state.points.size in 4..16) { "Hell greenhouse points must contain 4..16 plants" }
        require(state.points.distinct().size == state.points.size) { "Hell greenhouse points must be unique" }
        require(state.points.all(::validPoint)) { "Hell greenhouse point is invalid" }
        require(state.points.map(FarmPointPosition::world).distinct().size == 1) { "Hell greenhouse points must share a world" }
        require(state.entrance == null || (validPoint(state.entrance) && state.entrance.world == state.points.first().world)) {
            "Hell greenhouse entrance is invalid"
        }
        require(state.elapsedSeconds in 0..1_000_000 && state.heat in 0..1_000_000 && state.cooled >= 0) { "Hell greenhouse progress must be non-negative" }
        require(state.harvested.all { it in state.points.indices }) { "Hell greenhouse harvested index is invalid" }
        require(state.cooled <= state.harvested.size) { "Hell greenhouse cooled count exceeds harvested plants" }
        require(state.evacuationSeconds == null || state.evacuationSeconds in 0..86_400) { "Hell greenhouse evacuation countdown is invalid" }
        require(state.carried.values.all { it.index in state.points.indices && it.index in state.harvested && it.expiresAt >= 0 }) { "Hell greenhouse carried plant is invalid" }
        require(state.carried.values.map(FarmHellPepper::index).distinct().size == state.carried.size) { "Hell greenhouse carried plants must be unique" }
        require(state.cooled + state.carried.size <= state.harvested.size) { "Hell greenhouse cooled count exceeds harvested plants" }
        if (state.finished) require(state.carried.isEmpty()) { "Finished hell greenhouse retains carried plants" }
    }

    fun ripe(current: FarmHellGreenhouseState, index: Int, rules: FarmHellGreenhouseRules): Boolean {
        require(index in current.points.indices) { "Hell greenhouse plant index is invalid" }
        return current.elapsedSeconds >= rules.growSeconds
    }

    fun pick(current: FarmHellGreenhouseState, playerId: UUID, index: Int, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        val state = normalize(current, rules)
        if (state.finished || state.carried.containsKey(playerId) || index in state.harvested || !ripe(state, index, rules)) return FarmHellGreenhouseResult(state, accepted = false)
        val next = state.copy(heat = 0, harvested = state.harvested + index, carried = state.carried + (playerId to FarmHellPepper(index, state.elapsedSeconds + rules.hotSeconds)), evacuationSeconds = null)
        return FarmHellGreenhouseResult(next, accepted = true)
    }

    fun cool(current: FarmHellGreenhouseState, playerId: UUID, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        val state = normalize(current, rules)
        if (playerId !in state.carried) return FarmHellGreenhouseResult(state, accepted = false)
        val pepper = state.carried.getValue(playerId)
        if (pepper.expiresAt <= state.elapsedSeconds) {
            val next = state.copy(carried = state.carried - playerId, harvested = state.harvested - pepper.index)
            return FarmHellGreenhouseResult(next, accepted = false, expiredPlayerIds = setOf(playerId))
        }
        if (state.finished) return FarmHellGreenhouseResult(state, accepted = false)
        val done = state.cooled + 1 >= rules.quota
        val next = state.copy(carried = if (done) emptyMap() else state.carried - playerId, cooled = state.cooled + 1, finished = done)
        return FarmHellGreenhouseResult(next, accepted = true, contribution = 1, finished = done, successful = done)
    }

    fun second(current: FarmHellGreenhouseState, participants: Set<UUID>, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        val state = normalize(current, rules)
        if (state.finished || participants.isEmpty()) return FarmHellGreenhouseResult(state, accepted = false)
        val elapsed = (state.elapsedSeconds + 1).coerceAtMost(1_000_000)
        val expired = state.carried.filterValues { it.expiresAt <= elapsed }
        val done = state.cooled >= rules.quota
        val next = state.copy(
            elapsedSeconds = elapsed,
            heat = 0,
            evacuationSeconds = null,
            finished = done,
            harvested = state.harvested - expired.values.map(FarmHellPepper::index).toSet(),
            carried = if (done) emptyMap() else state.carried - expired.keys,
        )
        return FarmHellGreenhouseResult(next, accepted = true, expiredPlayerIds = expired.keys, finished = done, successful = done)
    }

    fun release(current: FarmHellGreenhouseState, playerId: UUID, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        val state = normalize(current, rules)
        val pepper = state.carried[playerId] ?: return FarmHellGreenhouseResult(state, accepted = false)
        val next = state.copy(carried = state.carried - playerId, harvested = state.harvested - pepper.index)
        return FarmHellGreenhouseResult(next, accepted = true)
    }

    private fun normalize(current: FarmHellGreenhouseState, rules: FarmHellGreenhouseRules): FarmHellGreenhouseState {
        // Legacy saves do not identify which delivered plants were cooled. Retain that many
        // harvested plants deterministically and regrow the rest, preserving every carried pepper.
        val carriedIndices = current.carried.values.mapTo(linkedSetOf(), FarmHellPepper::index)
        val cooledIndices = (current.harvested - carriedIndices).sorted().take(current.cooled)
        return current.copy(
            heat = 0,
            evacuationSeconds = null,
            harvested = cooledIndices.toSet() + carriedIndices,
            carried = current.carried.mapValues { (_, pepper) ->
                if (pepper.expiresAt == Int.MAX_VALUE) pepper.copy(expiresAt = current.elapsedSeconds + rules.hotSeconds) else pepper
            },
        )
    }

    private fun validPoint(point: FarmPointPosition): Boolean =
        POINT_WORLD_ID.matches(point.world) && listOf(point.x, point.y, point.z).all(Double::isFinite) &&
            point.x in -30_000_000.0..30_000_000.0 && point.z in -30_000_000.0..30_000_000.0 &&
            point.y in -2_048.0..2_048.0 && point.yaw.isFinite() && point.pitch.isFinite() && point.pitch in -90f..90f
}

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
    private val POINT_WORLD_ID = Regex("[A-Za-z0-9._-]{1,128}")

    fun initialize(current: FarmHellGreenhouseState, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        require(rules.quota <= current.points.size) { "Hell greenhouse quota must not exceed points" }
        return FarmHellGreenhouseResult(current, accepted = true)
    }

    fun validate(state: FarmHellGreenhouseState) {
        require(state.points.size in 4..16) { "Hell greenhouse points must contain 4..16 plants" }
        require(state.points.distinct().size == state.points.size) { "Hell greenhouse points must be unique" }
        require(state.points.all { point ->
            POINT_WORLD_ID.matches(point.world) &&
                listOf(point.x, point.y, point.z).all(Double::isFinite) &&
                point.x in -30_000_000.0..30_000_000.0 && point.z in -30_000_000.0..30_000_000.0 &&
                point.y in -2_048.0..2_048.0 && point.yaw.isFinite() && point.pitch.isFinite() && point.pitch in -90f..90f
        }) { "Hell greenhouse point is invalid" }
        require(state.points.map(FarmPointPosition::world).distinct().size == 1) {
            "Hell greenhouse points must share a world"
        }
        require(state.elapsedSeconds in 0..1_000_000 && state.heat in 0..1_000_000 && state.cooled >= 0) {
            "Hell greenhouse progress must be non-negative"
        }
        require(state.harvested.all { it in state.points.indices }) { "Hell greenhouse harvested index is invalid" }
        require(state.cooled <= state.harvested.size) { "Hell greenhouse cooled count exceeds harvested plants" }
        require(state.evacuationSeconds == null || state.evacuationSeconds in 0..86_400) {
            "Hell greenhouse evacuation countdown is invalid"
        }
        val peppers = state.carried.values
        require(peppers.all { it.index in state.points.indices && it.expiresAt > state.elapsedSeconds }) {
            "Hell greenhouse carried pepper is expired or invalid"
        }
        require(peppers.map(FarmHellPepper::index).distinct().size == peppers.size) {
            "Hell greenhouse carried plants must be unique"
        }
        require(peppers.all { it.index in state.harvested }) {
            "Hell greenhouse carried plant is not harvested"
        }
        require(state.cooled + peppers.size <= state.harvested.size) {
            "Hell greenhouse cooled count exceeds harvested plants"
        }
        if (state.finished) require(peppers.isEmpty()) { "Finished hell greenhouse retains carried plants" }
    }

    fun ripe(current: FarmHellGreenhouseState, index: Int, rules: FarmHellGreenhouseRules): Boolean {
        require(index in current.points.indices) { "Hell greenhouse plant index is invalid" }
        return current.elapsedSeconds >= rules.growSeconds
    }

    fun pick(
        current: FarmHellGreenhouseState,
        playerId: UUID,
        index: Int,
        rules: FarmHellGreenhouseRules,
    ): FarmHellGreenhouseResult {
        validate(current)
        if (current.finished || current.evacuationSeconds != null || current.carried.containsKey(playerId) ||
            index in current.harvested || !ripe(current, index, rules)
        ) return FarmHellGreenhouseResult(current, accepted = false)
        val next = current.copy(
            heat = (current.heat + rules.heatPerHarvest).coerceAtMost(rules.heatLimit),
            harvested = current.harvested + index,
            carried = current.carried + (playerId to FarmHellPepper(index, current.elapsedSeconds + rules.hotSeconds)),
            evacuationSeconds = if (current.heat + rules.heatPerHarvest >= rules.heatLimit) {
                rules.evacuationSeconds
            } else current.evacuationSeconds,
        )
        return FarmHellGreenhouseResult(next, accepted = true)
    }

    fun cool(
        current: FarmHellGreenhouseState,
        playerId: UUID,
        rules: FarmHellGreenhouseRules,
    ): FarmHellGreenhouseResult {
        validate(current)
        val pepper = current.carried[playerId] ?: return FarmHellGreenhouseResult(current, accepted = false)
        if (current.finished || current.elapsedSeconds >= pepper.expiresAt) return FarmHellGreenhouseResult(current, accepted = false)
        val next = current.copy(carried = current.carried - playerId, cooled = current.cooled + 1)
        return FarmHellGreenhouseResult(next, accepted = true, contribution = 1)
    }

    fun second(
        current: FarmHellGreenhouseState,
        participants: Set<UUID>,
        rules: FarmHellGreenhouseRules,
    ): FarmHellGreenhouseResult {
        validate(current)
        if (current.finished || participants.isEmpty()) return FarmHellGreenhouseResult(current, accepted = false)
        if (current.evacuationSeconds != null && current.evacuationSeconds <= 0) {
            return FarmHellGreenhouseResult(current.copy(finished = true), accepted = true, finished = true, timedOut = true)
        }
        val elapsed = current.elapsedSeconds + 1
        val expired = current.carried.filterValues { it.expiresAt <= elapsed }.keys
        val carried = current.carried - expired
        val heat = (current.heat + 1).coerceAtMost(rules.heatLimit)
        val countdown = when {
            current.evacuationSeconds != null -> current.evacuationSeconds - 1
            heat >= rules.heatLimit -> rules.evacuationSeconds
            else -> null
        }
        val timedOut = countdown != null && countdown <= 0
        val next = current.copy(
            elapsedSeconds = elapsed,
            heat = heat,
            carried = if (timedOut) emptyMap() else carried,
            evacuationSeconds = if (timedOut) 0 else countdown,
            finished = timedOut,
        )
        return FarmHellGreenhouseResult(
            next,
            accepted = true,
            expiredPlayerIds = expired,
            finished = timedOut,
            timedOut = timedOut,
        )
    }

    fun evacuate(
        current: FarmHellGreenhouseState,
        playerId: UUID,
        rules: FarmHellGreenhouseRules,
    ): FarmHellGreenhouseResult {
        validate(current)
        if (current.finished || (current.cooled < rules.quota && current.evacuationSeconds == null)) {
            return FarmHellGreenhouseResult(current, accepted = false)
        }
        val successful = current.cooled >= rules.quota
        return FarmHellGreenhouseResult(
            current.copy(finished = true, carried = emptyMap()),
            accepted = true,
            finished = true,
            successful = successful,
        )
    }

    fun release(
        current: FarmHellGreenhouseState,
        playerId: UUID,
        rules: FarmHellGreenhouseRules,
    ): FarmHellGreenhouseResult {
        validate(current)
        if (current.finished || !current.carried.containsKey(playerId)) return FarmHellGreenhouseResult(current, accepted = false)
        return FarmHellGreenhouseResult(current.copy(carried = current.carried - playerId), accepted = true)
    }
}

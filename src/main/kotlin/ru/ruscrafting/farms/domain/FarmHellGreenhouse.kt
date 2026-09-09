package ru.ruscrafting.farms.domain

import java.util.UUID

/** Quota stays compatible with existing hell-greenhouse configuration. Legacy pepper tunables are read-only compatibility. */
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
    val layoutVersion: Int = 0,
) {
    fun containsRoom(world: String, x: Double, y: Double, z: Double): Boolean =
        entrance != null && points.isNotEmpty() && world == points.first().world &&
            kotlin.math.abs(x - points.map { it.x }.average()) <= 4.8 &&
            kotlin.math.abs(z - points.map { it.z }.average()) <= 5.8 &&
            kotlin.math.abs(y - points.first().y) <= 3.0
}

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
        require(rules.quota <= current.points.size) { "Hell rift quota must not exceed rune positions" }
        val next = current.copy(heat = 0, evacuationSeconds = null, carried = emptyMap(),
            harvested = (0 until current.cooled).toSet(), finished = current.cooled >= rules.quota, layoutVersion = 1)
        validate(next)
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
        require(state.layoutVersion in 0..1) { "Unsupported hell rift layout version" }
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

    /** One shared unit per completed rune; stale or simultaneous completions cannot award twice. */
    fun seal(current: FarmHellGreenhouseState, index: Int, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        if (current.finished || index != current.cooled || index !in current.points.indices) {
            return FarmHellGreenhouseResult(current, accepted = false)
        }
        val done = current.cooled + 1 >= rules.quota
        val next = current.copy(cooled = current.cooled + 1, harvested = current.harvested + index,
            carried = emptyMap(), finished = done)
        return FarmHellGreenhouseResult(next, accepted = true, contribution = 1, finished = done, successful = done)
    }

    fun second(current: FarmHellGreenhouseState, participants: Set<UUID>, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        if (current.finished || participants.isEmpty()) return FarmHellGreenhouseResult(current, accepted = false)
        return FarmHellGreenhouseResult(current.copy(elapsedSeconds = (current.elapsedSeconds + 1).coerceAtMost(1_000_000)), accepted = true)
    }

    private fun validPoint(point: FarmPointPosition): Boolean =
        POINT_WORLD_ID.matches(point.world) && listOf(point.x, point.y, point.z).all(Double::isFinite) &&
            point.x in -30_000_000.0..30_000_000.0 && point.z in -30_000_000.0..30_000_000.0 &&
            point.y in -2_048.0..2_048.0 && point.yaw.isFinite() && point.pitch.isFinite() && point.pitch in -90f..90f
}

/** Continuous presence is sampled every gameplay tick, including leaving the pad between seconds. */
data class FarmHellRiftCharge(val playerId: UUID? = null, val ticks: Int = 0) {
    val complete: Boolean get() = ticks >= REQUIRED_TICKS
    val remainingSeconds: Int get() = ((REQUIRED_TICKS - ticks).coerceAtLeast(0) + 19) / 20

    fun tick(occupants: Set<UUID>): FarmHellRiftCharge {
        val player = playerId?.takeIf { it in occupants } ?: occupants.minByOrNull(UUID::toString)
        return when {
            player == null -> FarmHellRiftCharge()
            player != playerId -> FarmHellRiftCharge(player, 1)
            else -> copy(ticks = (ticks + 1).coerceAtMost(REQUIRED_TICKS))
        }
    }

    companion object { const val REQUIRED_TICKS = 60 }
}

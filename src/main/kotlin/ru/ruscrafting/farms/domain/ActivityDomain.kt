package ru.ruscrafting.farms.domain

import java.util.UUID

enum class ActivityKind {
    FARM,
    LUMBER,
    MINE,
}

enum class ShiftOutcome {
    NONE,
    COMPLETED,
    TIMED_OUT,
}

data class EngineResult<S, E>(
    val state: S,
    val accepted: Boolean,
    val contribution: Int = 0,
    val events: List<E> = emptyList(),
    val contributionCredits: Map<UUID, Int> = emptyMap(),
)

internal fun incrementContribution(
    current: Map<UUID, Int>,
    playerId: UUID,
    delta: Int,
): Map<UUID, Int> {
    if (delta <= 0) return current
    val updated = ((current[playerId] ?: 0).toLong() + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return current + (playerId to updated)
}

internal fun winner(contributors: Map<UUID, Int>): UUID? =
    contributors.entries
        .sortedWith(compareByDescending<Map.Entry<UUID, Int>> { it.value }.thenBy { it.key.toString() })
        .firstOrNull()
        ?.key

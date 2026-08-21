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

enum class ShiftEvent {
    STARTED,
    PROGRESS,
    PHASE_CHANGED,
    GOLDEN_STARTED,
    GOLDEN_ENDED,
    HAZARD_STARTED,
    HAZARD_RESOLVED,
    EXTRACTION_STARTED,
    COMPLETED,
    TIMED_OUT,
    RESET,
}

data class EngineResult<T>(
    val state: T,
    val accepted: Boolean,
    val contribution: Int = 0,
    val events: List<ShiftEvent> = emptyList(),
)

internal fun incrementContribution(
    current: Map<UUID, Int>,
    playerId: UUID,
    delta: Int,
): Map<UUID, Int> {
    if (delta <= 0) return current
    return current + (playerId to (current[playerId] ?: 0) + delta)
}

internal fun winner(contributors: Map<UUID, Int>): UUID? =
    contributors.entries
        .sortedWith(compareByDescending<Map.Entry<UUID, Int>> { it.value }.thenBy { it.key.toString() })
        .firstOrNull()
        ?.key

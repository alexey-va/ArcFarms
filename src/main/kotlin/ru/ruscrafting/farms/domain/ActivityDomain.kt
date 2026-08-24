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
    PREPARATION_PROGRESS,
    PLANTING_STARTED,
    PLANTING_PROGRESS,
    PREPARATION_COMPLETED,
    CARE_STARTED,
    CARE_PROGRESS,
    CARE_RESOLVED,
    HARVEST_MILESTONE,
    INCIDENT_STARTED,
    INCIDENT_PROGRESS,
    INCIDENT_RESOLVED,
    DELIVERY_STARTED,
    DELIVERY_PROGRESS,
    HAZARD_STARTED,
    HAZARD_RESOLVED,
    EXTRACTION_STARTED,
    COMPLETED,
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
    val updated = ((current[playerId] ?: 0).toLong() + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return current + (playerId to updated)
}

internal fun winner(contributors: Map<UUID, Int>): UUID? =
    contributors.entries
        .sortedWith(compareByDescending<Map.Entry<UUID, Int>> { it.value }.thenBy { it.key.toString() })
        .firstOrNull()
        ?.key

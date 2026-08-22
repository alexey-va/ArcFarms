package ru.ruscrafting.farms.domain

import java.util.UUID

data class PlayerActivityStats(
    val contributions: Map<ActivityKind, Long> = emptyMap(),
    val completedShifts: Map<ActivityKind, Int> = emptyMap(),
) {
    fun contribute(kind: ActivityKind, amount: Int): PlayerActivityStats =
        copy(
            contributions = contributions + (
                kind to saturatingAdd(contributions[kind] ?: 0L, amount.coerceAtLeast(0).toLong())
            ),
        )

    fun complete(kind: ActivityKind): PlayerActivityStats {
        val current = completedShifts[kind] ?: 0
        return copy(completedShifts = completedShifts + (kind to if (current == Int.MAX_VALUE) current else current + 1))
    }
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

data class ArcFarmsState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val farms: Map<String, FarmShiftState> = emptyMap(),
    val lumbermills: Map<String, LumberShiftState> = emptyMap(),
    val mines: Map<String, MineShiftState> = emptyMap(),
    val stats: Map<UUID, PlayerActivityStats> = emptyMap(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported ArcFarms state schema: $schemaVersion" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class PendingMineBlock(
    val id: String,
    val zoneId: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalMaterial: String,
    val temporaryMaterial: String,
    val nextMaterial: String,
    val restoreAt: Long,
) {
    init {
        require(id.matches(Regex("[a-zA-Z0-9._:-]{1,160}"))) { "Invalid mine journal id" }
        require(zoneId.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid mine zone id" }
        require(world.length in 1..128)
        require(restoreAt > 0)
    }

    val positionKey: String get() = "$world:$x:$y:$z"
}

data class MineBlockJournalState(
    val schemaVersion: Int = 1,
    val records: Map<String, PendingMineBlock> = emptyMap(),
) {
    init {
        require(schemaVersion == 1) { "Unsupported mine journal schema: $schemaVersion" }
        require(records.size <= 100_000) { "Mine journal is unbounded" }
    }
}

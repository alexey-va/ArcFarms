package ru.ruscrafting.farms.domain

import java.util.UUID

data class PlayerActivityStats(
    val contributions: Map<ActivityKind, Long> = emptyMap(),
    val completedShifts: Map<ActivityKind, Int> = emptyMap(),
    // Nullable only so Gson can safely read pre-weekly state files where the
    // field is absent. ArcFarmsStateRepository normalizes it after loading.
    val weeklyContributions: Map<ActivityKind, WeeklyActivityContribution>? = emptyMap(),
) {
    fun contribute(kind: ActivityKind, amount: Int): PlayerActivityStats =
        copy(
            contributions = contributions + (
                kind to saturatingAdd(contributions[kind] ?: 0L, amount.coerceAtLeast(0).toLong())
            ),
        )

    fun contributeWeekly(
        kind: ActivityKind,
        amount: Int,
        weekStartEpochDay: Long,
    ): PlayerActivityStats {
        val delta = amount.coerceAtLeast(0).toLong()
        val current = weeklyContributions.orEmpty()[kind]
        val weekly = if (current?.weekStartEpochDay == weekStartEpochDay) {
            current.copy(contribution = saturatingAdd(current.contribution, delta))
        } else {
            WeeklyActivityContribution(weekStartEpochDay, delta)
        }
        return contribute(kind, amount).copy(
            weeklyContributions = weeklyContributions.orEmpty() + (kind to weekly),
        )
    }

    fun complete(kind: ActivityKind): PlayerActivityStats {
        val current = completedShifts[kind] ?: 0
        return copy(completedShifts = completedShifts + (kind to if (current == Int.MAX_VALUE) current else current + 1))
    }
}

data class WeeklyActivityContribution(
    val weekStartEpochDay: Long,
    val contribution: Long,
)

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

data class ArcFarmsState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val farms: Map<String, FarmShiftState> = emptyMap(),
    // Nullable so Gson can read state files written before durable admin pauses existed.
    val pausedFarmZones: Set<String>? = emptySet(),
    val lumbermills: Map<String, LumberShiftState> = emptyMap(),
    val mines: Map<String, MineShiftState> = emptyMap(),
    val stats: Map<UUID, PlayerActivityStats> = emptyMap(),
    val pendingFarmRewards: List<PendingFarmReward> = emptyList(),
    val claimedFarmRewardSequences: Map<String, Long> = emptyMap(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported ArcFarms state schema: $schemaVersion" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class FarmRewardItem(
    val material: String,
    val amount: Int,
)

data class PendingFarmReward(
    val id: String,
    val zoneId: String,
    val sequence: Long,
    val playerId: UUID,
    val contribution: Int,
    val experience: Int = 0,
    val moneyCents: Long = 0,
    val items: List<FarmRewardItem> = emptyList(),
    val fixedItemUnits: Int = 0,
    val commands: List<String> = emptyList(),
    val bundleIds: List<String> = emptyList(),
) {
    val claimKey: String get() = "$zoneId:$playerId"
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

data class PendingFixedFarmCrop(
    val zoneId: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalBlockData: String,
    val restoreAt: Long,
) {
    init {
        require(zoneId.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid farm zone id" }
        require(world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid fixed crop world" }
        require(x in -30_000_000..30_000_000 && z in -30_000_000..30_000_000) {
            "Fixed crop is outside the world border"
        }
        require(y in -4_096..4_096) { "Fixed crop height is invalid" }
        require(originalBlockData.length in 1..512) { "Fixed crop block data is invalid" }
        require(restoreAt > 0L) { "Fixed crop restore time is invalid" }
    }

    val positionKey: String get() = "$world:$x:$y:$z"
}

data class FixedFarmCropJournalState(
    val schemaVersion: Int = 1,
    val records: Map<String, PendingFixedFarmCrop> = emptyMap(),
)

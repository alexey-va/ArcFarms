package ru.ruscrafting.farms.paper.worksite

import org.bukkit.Bukkit
import ru.ruscrafting.farms.config.FarmRewardSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmRewardLedgerSnapshot
import ru.ruscrafting.farms.domain.FarmRewardPlanner
import ru.ruscrafting.farms.domain.FarmRewardRecipient
import ru.ruscrafting.farms.domain.PendingFarmReward
import java.util.UUID

internal interface WorksiteRewardLedger {
    fun snapshot(): FarmRewardLedgerSnapshot
    fun enqueueRewards(rewards: List<PendingFarmReward>)
}

/** Activity-neutral exact-once planning over the existing byte-compatible reward ledger. */
internal class WorksiteRewardGrantService(private val ledger: WorksiteRewardLedger) {
    fun queueCompletion(
        activity: ActivityKind,
        settings: FarmRewardSettings,
        zoneId: String,
        sequence: Long,
        contributors: Map<UUID, Int>,
        quotaTotal: Int,
        incidentCount: Int,
    ) {
        val rewardZone = rewardZone(activity, zoneId)
        val snapshot = ledger.snapshot()
        val multiplier = difficultyMultiplier(quotaTotal, incidentCount)
        val planned = contributors.entries.filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<UUID, Int>> { it.value }.thenBy { it.key.toString() })
            .mapIndexedNotNull { rank, (playerId, contribution) ->
                val claimKey = "$rewardZone:$playerId"
                if ((snapshot.claimed[claimKey] ?: -1L) >= sequence) return@mapIndexedNotNull null
                FarmRewardPlanner.plan(
                    settings, rewardZone, sequence,
                    FarmRewardRecipient(
                        playerId,
                        Bukkit.getOfflinePlayer(playerId).name ?: playerId.toString(),
                        contribution,
                        rank + 1,
                    ),
                    rewardMultiplierPercent = multiplier,
                ).takeUnless { reward -> snapshot.pending.any { it.id == reward.id } }
            }
        if (planned.isNotEmpty()) ledger.enqueueRewards(planned)
    }

    internal fun difficultyMultiplier(quotaTotal: Int, incidentCount: Int): Int {
        require(quotaTotal > 0 && incidentCount in 0..8)
        return (100 + quotaTotal.coerceAtMost(100) / 2 + incidentCount * 10).coerceIn(100, 250)
    }

    private fun rewardZone(activity: ActivityKind, zoneId: String): String = when (activity) {
        ActivityKind.FARM -> zoneId
        ActivityKind.LUMBER -> "lumber_$zoneId"
        ActivityKind.MINE -> "mine_$zoneId"
    }
}

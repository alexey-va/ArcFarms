package ru.ruscrafting.farms.domain

import java.util.UUID

internal data class FarmRewardLedgerSnapshot(
    val pending: List<PendingFarmReward>,
    val claimed: Map<String, Long>,
)

internal data class FarmRewardClaimResult(
    val rewards: List<PendingFarmReward>,
    val failure: Throwable? = null,
)

/** Owns durable farm-reward deduplication and the claim-before-delivery transaction. */
internal class FarmRewardLedger {
    private val pending = mutableListOf<PendingFarmReward>()
    private val claimed = mutableMapOf<String, Long>()

    fun replace(pendingRewards: Collection<PendingFarmReward>, claimedSequences: Map<String, Long>) {
        require(pendingRewards.map(PendingFarmReward::id).distinct().size == pendingRewards.size) {
            "Duplicate pending farm reward id"
        }
        require(claimedSequences.values.all { it >= 0L }) { "Invalid claimed farm reward sequence" }
        pending.clear()
        pending += pendingRewards
        claimed.clear()
        claimed += claimedSequences
    }

    fun snapshot(): FarmRewardLedgerSnapshot = FarmRewardLedgerSnapshot(pending.toList(), claimed.toMap())

    fun contains(grantId: String): Boolean = pending.any { it.id == grantId }

    fun isClaimed(claimKey: String, sequence: Long): Boolean = (claimed[claimKey] ?: -1L) >= sequence

    fun enqueue(rewards: Collection<PendingFarmReward>): List<PendingFarmReward> {
        val knownIds = pending.mapTo(hashSetOf(), PendingFarmReward::id)
        val accepted = rewards.filter { reward ->
            !isClaimed(reward.claimKey, reward.sequence) && knownIds.add(reward.id)
        }
        pending += accepted
        return accepted
    }

    /**
     * Removes every candidate and advances claims before [persist] is called.
     * A failed persistence call restores the exact previous in-memory state.
     */
    fun claim(playerIds: Set<UUID>, persist: () -> Unit): FarmRewardClaimResult {
        if (playerIds.isEmpty()) return FarmRewardClaimResult(emptyList())
        val candidates = pending.filter { it.playerId in playerIds }
        if (candidates.isEmpty()) return FarmRewardClaimResult(emptyList())
        val deliverable = candidates.filterNot { isClaimed(it.claimKey, it.sequence) }
            .sortedWith(compareBy(PendingFarmReward::sequence, PendingFarmReward::id))
        val before = snapshot()
        pending.removeAll(candidates.toSet())
        deliverable.groupBy(PendingFarmReward::claimKey).forEach { (claimKey, rewards) ->
            claimed[claimKey] = maxOf(claimed[claimKey] ?: -1L, rewards.maxOf(PendingFarmReward::sequence))
        }
        return try {
            persist()
            FarmRewardClaimResult(deliverable)
        } catch (failure: Throwable) {
            replace(before.pending, before.claimed)
            if (failure is Error) throw failure
            FarmRewardClaimResult(deliverable, failure)
        }
    }
}

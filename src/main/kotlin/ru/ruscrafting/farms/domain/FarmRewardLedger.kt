package ru.ruscrafting.farms.domain

import java.util.UUID
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.plus

internal data class FarmRewardLedgerSnapshot(
    val pending: List<PendingFarmReward>,
    val claimed: Map<String, Long>,
    val moneyDeliveries: Map<String, FarmRewardMoneyDelivery> = emptyMap(),
)

internal data class FarmRewardClaimResult(
    val rewards: List<PendingFarmReward>,
    val failure: Throwable? = null,
)

/** Owns durable farm-reward deduplication and the claim-before-delivery transaction. */
internal class FarmRewardLedger {
    private var pending = persistentListOf<PendingFarmReward>()
    private var claimed = persistentMapOf<String, Long>()
    private var moneyDeliveries = persistentMapOf<String, FarmRewardMoneyDelivery>()

    fun replace(
        pendingRewards: Collection<PendingFarmReward>,
        claimedSequences: Map<String, Long>,
        deliveries: Map<String, FarmRewardMoneyDelivery> = emptyMap(),
    ) {
        require(pendingRewards.map(PendingFarmReward::id).distinct().size == pendingRewards.size) {
            "Duplicate pending farm reward id"
        }
        require(claimedSequences is PersistentMap || claimedSequences.values.all { it >= 0L }) { "Invalid claimed farm reward sequence" }
        pending = pendingRewards.toPersistentList()
        claimed = claimedSequences.toPersistentMap()
        moneyDeliveries = deliveries.toPersistentMap()
    }

    fun snapshot(): FarmRewardLedgerSnapshot = FarmRewardLedgerSnapshot(pending, claimed, moneyDeliveries)

    fun recoverPreparedMoney(): List<FarmRewardMoneyDelivery> {
        val recovered = moneyDeliveries.values.filter { it.state == FarmRewardMoneyState.PREPARED }
        recovered.forEach { delivery ->
            moneyDeliveries = moneyDeliveries.put(delivery.reward.id, delivery.copy(state = FarmRewardMoneyState.MANUAL_REVIEW))
        }
        return recovered
    }

    fun recordMoneyOutcome(grantId: String, outcome: FarmRewardMoneyState) {
        require(outcome != FarmRewardMoneyState.PREPARED)
        val delivery = requireNotNull(moneyDeliveries[grantId]) { "Missing farm money intent $grantId" }
        check(delivery.state == FarmRewardMoneyState.PREPARED) { "Farm money intent $grantId is already resolved" }
        moneyDeliveries = moneyDeliveries.put(grantId, delivery.copy(state = outcome))
    }

    /** Only used before any side effect, while this serialized claim still owns its watermarks. */
    fun deferUnsent(rewards: List<PendingFarmReward>, before: FarmRewardLedgerSnapshot) {
        if (rewards.isEmpty()) return
        rewards.map(PendingFarmReward::claimKey).distinct().forEach { key ->
            claimed = before.claimed[key]?.let { claimed.put(key, it) } ?: claimed.remove(key)
        }
        rewards.forEach { reward ->
            check(moneyDeliveries[reward.id]?.state.let { it == null || it == FarmRewardMoneyState.PREPARED })
            moneyDeliveries = moneyDeliveries.remove(reward.id)
        }
        enqueue(rewards)
    }

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
        val newMoney = deliverable.filter { it.moneyCents > 0 }.associate { reward ->
            reward.id to FarmRewardMoneyDelivery(reward, FarmRewardMoneyState.PREPARED)
        }
        var retainedMoney = moneyDeliveries
        val excess = retainedMoney.size + newMoney.size - MAX_MONEY_DELIVERIES
        if (excess > 0) {
            val removable = retainedMoney.values.filter { it.state == FarmRewardMoneyState.APPLIED }
                .sortedBy { it.reward.sequence }.take(excess).map { it.reward.id }
            check(removable.size == excess) { "Unresolved farm money journal is full; rewards remain pending" }
            removable.forEach { retainedMoney = retainedMoney.remove(it) }
        }
        moneyDeliveries = retainedMoney.putAll(newMoney)
        pending = pending.removeAll(candidates.toSet())
        deliverable.groupBy(PendingFarmReward::claimKey).forEach { (claimKey, rewards) ->
            claimed = claimed.put(claimKey, maxOf(claimed[claimKey] ?: -1L, rewards.maxOf(PendingFarmReward::sequence)))
        }
        return try {
            persist()
            FarmRewardClaimResult(deliverable)
        } catch (failure: Throwable) {
            replace(before.pending, before.claimed, before.moneyDeliveries)
            if (failure is Error) throw failure
            FarmRewardClaimResult(deliverable, failure)
        }
    }

    companion object {
        const val MAX_MONEY_DELIVERIES = 10_000
    }
}

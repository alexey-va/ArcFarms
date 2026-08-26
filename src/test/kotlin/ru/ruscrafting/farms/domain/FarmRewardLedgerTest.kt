package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmRewardLedgerTest : FunSpec({
    fun reward(sequence: Long, player: UUID = UUID(0, 42)) = PendingFarmReward(
        id = "farm:$sequence:$player",
        zoneId = "farm",
        sequence = sequence,
        playerId = player,
        contribution = 10,
    )

    test("enqueue rejects duplicate and already claimed grants") {
        val player = UUID(0, 42)
        val ledger = FarmRewardLedger()
        ledger.replace(listOf(reward(2, player)), mapOf("farm:$player" to 1L))

        ledger.enqueue(listOf(reward(1, player), reward(2, player), reward(3, player)))
            .shouldContainExactly(reward(3, player))
        ledger.snapshot().pending.shouldContainExactly(reward(2, player), reward(3, player))
    }

    test("claim persists the highest sequence before returning every deliverable grant") {
        val player = UUID(0, 42)
        val ledger = FarmRewardLedger()
        ledger.replace(listOf(reward(2, player), reward(3, player)), emptyMap())
        var persisted: FarmRewardLedgerSnapshot? = null

        val result = ledger.claim(setOf(player)) { persisted = ledger.snapshot() }

        result.failure shouldBe null
        result.rewards.shouldContainExactly(reward(2, player), reward(3, player))
        persisted?.pending shouldBe emptyList()
        persisted?.claimed shouldBe mapOf("farm:$player" to 3L)
    }

    test("claim rolls back exactly when persistence fails") {
        val player = UUID(0, 42)
        val initial = FarmRewardLedgerSnapshot(listOf(reward(2, player)), mapOf("farm:$player" to 1L))
        val ledger = FarmRewardLedger()
        ledger.replace(initial.pending, initial.claimed)

        val result = ledger.claim(setOf(player)) { error("disk unavailable") }

        result.failure?.message shouldBe "disk unavailable"
        result.rewards.shouldContainExactly(reward(2, player))
        ledger.snapshot() shouldBe initial
    }

    test("claim restores memory before propagating a fatal persistence failure") {
        val player = UUID(0, 42)
        val initial = FarmRewardLedgerSnapshot(listOf(reward(2, player)), mapOf("farm:$player" to 1L))
        val ledger = FarmRewardLedger()
        ledger.replace(initial.pending, initial.claimed)

        shouldThrow<AssertionError> { ledger.claim(setOf(player)) { throw AssertionError("fatal storage failure") } }

        ledger.snapshot() shouldBe initial
    }

    test("stale pending grants are retired without delivery") {
        val player = UUID(0, 42)
        val ledger = FarmRewardLedger()
        ledger.replace(listOf(reward(2, player)), mapOf("farm:$player" to 2L))
        var persistCalls = 0

        val result = ledger.claim(setOf(player)) { persistCalls++ }

        result.rewards shouldBe emptyList()
        result.failure shouldBe null
        persistCalls shouldBe 1
        ledger.snapshot().pending shouldBe emptyList()
    }

    test("snapshots do not expose mutable ledger collections") {
        val player = UUID(0, 42)
        val source = mutableListOf(reward(2, player))
        val claims = mutableMapOf("farm:$player" to 1L)
        val ledger = FarmRewardLedger()
        ledger.replace(source, claims)

        source.clear()
        claims.clear()

        ledger.snapshot() shouldBe FarmRewardLedgerSnapshot(listOf(reward(2, player)), mapOf("farm:$player" to 1L))
    }
})

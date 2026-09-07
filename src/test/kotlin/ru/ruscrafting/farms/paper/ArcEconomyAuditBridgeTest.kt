package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

class ArcEconomyAuditBridgeTest : StringSpec({
    "fails closed when ARC is absent or reflection cannot resolve it" {
        ArcEconomyAuditBridge.mark(UUID.randomUUID(), 1.25, "farm:1").shouldBeNull()
        ArcEconomyAuditBridge.cancel(UUID.randomUUID(), "unknown-token")
    }

    "fails closed when the audit gateway throws" {
        ArcEconomyAuditBridge.markWith(
            gateway = { _, _, _, _, _, _ -> error("provider unavailable") },
            playerId = UUID.randomUUID(),
            amount = 1.0,
            rewardId = "farm:exception",
        ).shouldBeNull()
    }

    "passes the exact farm reward marker to a successful gateway" {
        val playerId = UUID.randomUUID()
        val calls = mutableListOf<List<Any?>>()

        val token = ArcEconomyAuditBridge.markWith(
            gateway = { id, source, action, amount, currency, rewardId ->
                calls += listOf(id, source, action, amount, currency, rewardId)
                "audit-token"
            },
            playerId = playerId,
            amount = 12.34,
            rewardId = "zone:seq:player",
        )

        token shouldBe "audit-token"
        calls shouldContainExactly listOf(
            listOf(playerId, "arcfarms", "farm_reward", 12.34, "vault", "zone:seq:player"),
        )
    }
})

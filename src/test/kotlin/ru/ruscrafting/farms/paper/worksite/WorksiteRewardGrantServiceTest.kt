package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.defaultWorksiteRewards
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmRewardLedgerSnapshot
import ru.ruscrafting.farms.domain.PendingFarmReward
import java.util.UUID

class WorksiteRewardGrantServiceTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("the same worksite completion is queued exactly once with an activity scoped claim") {
        val player = paper.server.addPlayer("RewardedWorker")
        val ledger = RecordingWorksiteRewardLedger()
        val service = WorksiteRewardGrantService(ledger)

        repeat(2) {
            service.queueCompletion(
                ActivityKind.MINE,
                defaultWorksiteRewards(125),
                "old_shafts",
                sequence = 7,
                contributors = mapOf(player.uniqueId to 12),
                quotaTotal = 24,
                incidentCount = 4,
            )
        }

        ledger.pending.size shouldBe 1
        ledger.pending.single().zoneId shouldBe "mine_old_shafts"
        ledger.pending.single().sequence shouldBe 7
    }

    test("an already claimed sequence never returns to pending rewards") {
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000009")
        val ledger = RecordingWorksiteRewardLedger(
            claimed = mutableMapOf("lumber_communal_lumbermill:$playerId" to 3L),
        )

        WorksiteRewardGrantService(ledger).queueCompletion(
            ActivityKind.LUMBER,
            defaultWorksiteRewards(110),
            "communal_lumbermill",
            sequence = 3,
            contributors = mapOf(playerId to 1),
            quotaTotal = 10,
            incidentCount = 3,
        )

        ledger.pending shouldBe emptyList()
    }
})

private class RecordingWorksiteRewardLedger(
    val pending: MutableList<PendingFarmReward> = mutableListOf(),
    val claimed: MutableMap<String, Long> = mutableMapOf(),
) : WorksiteRewardLedger {
    override fun snapshot(): FarmRewardLedgerSnapshot = FarmRewardLedgerSnapshot(pending.toList(), claimed.toMap())

    override fun enqueueRewards(rewards: List<PendingFarmReward>) {
        val ids = pending.mapTo(hashSetOf(), PendingFarmReward::id)
        pending += rewards.filter { ids.add(it.id) }
    }
}

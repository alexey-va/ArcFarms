package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ActivityStatsIndexTest : FunSpec({
    test("concurrent placeholder reads cannot race gameplay statistic writes") {
        val index = ActivityStatsIndex()
        val player = UUID(0, 42)
        val writes = CompletableFuture.runAsync {
            repeat(5_000) { index.contribute(player, ActivityKind.FARM, 1) }
        }
        val reads = (1..4).map {
            CompletableFuture.runAsync {
                repeat(2_000) {
                    index.player(player)
                    index.leaderboard(ActivityKind.FARM, 50)
                    index.farmRank(player)
                    index.snapshot()
                }
            }
        }

        CompletableFuture.allOf(writes, *reads.toTypedArray()).join()

        index.player(player).contributions[ActivityKind.FARM] shouldBe 5_000L
        index.leaderboard(ActivityKind.FARM) shouldBe listOf(player to 5_000L)
        index.farmRank(player) shouldBe 1
    }

    test("replacing restored state invalidates the cached farm leaderboard") {
        val first = UUID(0, 1)
        val second = UUID(0, 2)
        val index = ActivityStatsIndex(
            mapOf(first to PlayerActivityStats(contributions = mapOf(ActivityKind.FARM to 10))),
        )
        index.leaderboard(ActivityKind.FARM) shouldBe listOf(first to 10L)

        index.replace(
            mapOf(second to PlayerActivityStats(contributions = mapOf(ActivityKind.FARM to 20))),
        )

        index.leaderboard(ActivityKind.FARM) shouldBe listOf(second to 20L)
    }
})

package ru.ruscrafting.farms.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.farms.domain.ActivityKind
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ArcFarmsNetworkRepositoryTest : StringSpec({
    "network events preserve strict payload and origin" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val repository = ArcFarmsNetworkRepository(redis)
        val received = mutableListOf<Pair<NetworkEvent, String>>()
        repository.registerEvents { event, origin -> received += event to origin }

        val event = NetworkEvent.create(NetworkSignal.MINE_HAZARD, ActivityKind.MINE, "Alexey23", nowMs = 10)
        repository.publish(event)

        received shouldContainExactly listOf(event to "spawn")
        redis.getPublishedMessages().single().channel shouldBe ArcFarmsNetworkRepository.EVENT_CHANNEL
    }

    "workday seals persist without a deadline and advance only after all crafts" {
        val repository = ArcFarmsNetworkRepository(InMemoryRedis())

        repository.loadWorkday().join() shouldBe WorkdayState()
        repository.markCompleted(ActivityKind.FARM).join() shouldBe WorkdayUpdate.Stamped(
            WorkdayState(cycle = 1, revision = 1, completed = setOf(ActivityKind.FARM)),
            ActivityKind.FARM,
        )
        repository.markCompleted(ActivityKind.FARM).join() shouldBe WorkdayUpdate.AlreadyStamped(
            WorkdayState(cycle = 1, revision = 1, completed = setOf(ActivityKind.FARM)),
        )
        repository.markCompleted(ActivityKind.LUMBER).join()
        repository.markCompleted(ActivityKind.MINE).join() shouldBe WorkdayUpdate.Completed(
            state = WorkdayState(cycle = 2, revision = 3),
            completedCycle = 1,
            closingActivity = ActivityKind.MINE,
        )
        repository.loadWorkday().join() shouldBe WorkdayState(cycle = 2, revision = 3)
    }

    "protocol rejects a signal carrying another activity kind" {
        shouldThrow<IllegalArgumentException> {
            NetworkEvent.create(NetworkSignal.FARM_INCIDENT, ActivityKind.MINE)
        }
        shouldThrow<IllegalArgumentException> {
            NetworkEvent.create(NetworkSignal.NODE_PROBE, activity = ActivityKind.FARM)
        }
    }

    "concurrent duplicate completion grants exactly one seal" {
        val repository = ArcFarmsNetworkRepository(InMemoryRedis())
        val attempts = (1..24).map {
            CompletableFuture.supplyAsync { repository.markCompleted(ActivityKind.LUMBER).join() }
        }
        CompletableFuture.allOf(*attempts.toTypedArray()).join()

        attempts.count { it.join() is WorkdayUpdate.Stamped } shouldBe 1
        attempts.count { it.join() is WorkdayUpdate.AlreadyStamped } shouldBe 23
        repository.loadWorkday().join().completed shouldBe setOf(ActivityKind.LUMBER)
    }

    "travel ticket is destination-bound, expires, and can be claimed only once" {
        val repository = ArcFarmsNetworkRepository(InMemoryRedis())
        val player = UUID.fromString("00000000-0000-0000-0000-000000000099")

        repository.createTravelTicket(player, ActivityKind.FARM, "spawn", 1_000, 30_000).join() shouldBe true
        repository.claimTravelTicket(player, "survival", 2_000).join() shouldBe null
        repository.claimTravelTicket(player, "spawn", 2_000).join() shouldBe TravelTicket(
            ActivityKind.FARM,
            "spawn",
            1_000,
            31_000,
        )
        repository.claimTravelTicket(player, "spawn", 2_001).join() shouldBe null

        repository.createTravelTicket(player, ActivityKind.MINE, "spawn", 5_000, 10_000).join() shouldBe true
        repository.claimTravelTicket(player, "spawn", 15_001).join() shouldBe null
    }
})

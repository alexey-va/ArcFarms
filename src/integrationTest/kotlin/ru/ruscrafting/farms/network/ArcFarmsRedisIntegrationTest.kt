package ru.ruscrafting.farms.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.arc.testing.containers.RedisTestService
import ru.ruscrafting.farms.domain.ActivityKind
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

class ArcFarmsRedisIntegrationTest : StringSpec({
    "two real Redis nodes exchange events and atomically share one workday" {
        RedisTestService.start().use { service ->
            var spawn: RedisManager? = null
            var survival: RedisManager? = null
            try {
                val connection = RedisConnection(service.endpoint.host, service.endpoint.port)
                spawn = RedisManager(connection, ServerIdentity { "spawn" })
                survival = RedisManager(connection, ServerIdentity { "survival" })
                val spawnRepository = ArcFarmsNetworkRepository(spawn)
                val survivalRepository = ArcFarmsNetworkRepository(survival)
                val latch = CountDownLatch(1)
                var received: Pair<NetworkEvent, String>? = null
                survivalRepository.registerEvents(originAllowed = { it == "spawn" }) { event, origin ->
                    received = event to origin
                    latch.countDown()
                }
                spawnRepository.registerEvents(originAllowed = { it == "survival" }) { _, _ -> }
                spawn.init()
                survival.init()
                waitUntil(10_000) { spawn.isSubscriptionActive() && survival.isSubscriptionActive() }

                val event = NetworkEvent.create(NetworkSignal.FARM_INCIDENT, ActivityKind.FARM, "SoloPlayer")
                spawnRepository.publish(event)
                latch.await(5, TimeUnit.SECONDS) shouldBe true
                received shouldBe (event to "spawn")

                val first = spawnRepository.markCompleted(ActivityKind.FARM)
                val duplicate = survivalRepository.markCompleted(ActivityKind.FARM)
                val updates = listOf(first.join(), duplicate.join())
                updates.count { it is WorkdayUpdate.Stamped } shouldBe 1
                updates.count { it is WorkdayUpdate.AlreadyStamped } shouldBe 1
                spawnRepository.loadWorkday().join().completed shouldBe setOf(ActivityKind.FARM)
                survivalRepository.loadWorkday().join().completed shouldBe setOf(ActivityKind.FARM)

                val traveler = UUID.fromString("00000000-0000-0000-0000-000000000099")
                survivalRepository.createTravelTicket(
                    traveler,
                    ActivityKind.FARM,
                    "spawn",
                    1_000,
                    30_000,
                ).join() shouldBe true
                survivalRepository.claimTravelTicket(traveler, "survival", 2_000).join() shouldBe null
                spawnRepository.claimTravelTicket(traveler, "spawn", 2_000).join() shouldBe TravelTicket(
                    ActivityKind.FARM,
                    "spawn",
                    1_000,
                    31_000,
                )
                spawnRepository.claimTravelTicket(traveler, "spawn", 2_001).join() shouldBe null
            } finally {
                spawn?.close()
                survival?.close()
            }
        }
    }
}) {
    companion object {
        private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
            val deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos()
            while (System.nanoTime() < deadline) {
                if (condition()) return
                Thread.sleep(25)
            }
            require(condition()) { "Timed out waiting for Redis state" }
        }
    }
}

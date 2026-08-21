package ru.ruscrafting.farms.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.farms.domain.ActivityKind
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ArcFarmsRedisIntegrationTest : StringSpec({
    "two real Redis nodes exchange events and atomically share one workday" {
        val port = ServerSocket(0).use { it.localPort }
        val directory = Files.createTempDirectory("arcfarms-redis-")
        val process = ProcessBuilder(
            System.getenv("REDIS_SERVER_BIN") ?: "redis-server",
            "--bind", "127.0.0.1",
            "--port", port.toString(),
            "--save", "",
            "--appendonly", "no",
            "--dir", directory.toString(),
        ).redirectErrorStream(true).start()
        val outputDrain = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { _ -> } } }.apply {
            isDaemon = true
            start()
        }
        var spawn: RedisManager? = null
        var survival: RedisManager? = null
        try {
            waitUntil(10_000) { runCatching { Socket("127.0.0.1", port).use { } }.isSuccess }
            val connection = RedisConnection("127.0.0.1", port)
            spawn = RedisManager(connection, ServerIdentity { "spawn" })
            survival = RedisManager(connection, ServerIdentity { "survival" })
            val spawnRepository = ArcFarmsNetworkRepository(spawn)
            val survivalRepository = ArcFarmsNetworkRepository(survival)
            val latch = CountDownLatch(1)
            var received: Pair<NetworkEvent, String>? = null
            survivalRepository.registerEvents { event, origin ->
                received = event to origin
                latch.countDown()
            }
            spawnRepository.registerEvents { _, _ -> }
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
        } finally {
            spawn?.close()
            survival?.close()
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            outputDrain.join(1_000)
            directory.toFile().deleteRecursively()
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

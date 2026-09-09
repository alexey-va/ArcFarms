package ru.ruscrafting.farms.paper.mine.lift

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.createTempDirectory

class MineLiftRecoveryTest : FreeSpec({
    "boarding intent survives a new runtime and failed player save cannot acknowledge it" {
        val root = createTempDirectory("mine-lift-recovery")
        try {
            val id = UUID.randomUUID()
            val player = mockk<Player>()
            every { player.uniqueId } returns id
            every { player.saveData() } throws IllegalStateException("simulated player save failure")
            val world = mockk<World>()
            every { world.name } returns "mine"
            val journal = MineLiftRecovery(root)
            journal.capture(player, Location(world, 47.5, 123.0, 78.5))
            MineLiftRecovery(root).contains(id) shouldBe true
            shouldThrow<IllegalStateException> { journal.acknowledge(player) }
            MineLiftRecovery(root).contains(id) shouldBe true
            every { player.saveData() } returns Unit
            journal.acknowledge(player)
            MineLiftRecovery(root).pendingCount shouldBe 0
        } finally { root.toFile().deleteRecursively() }
    }

    "corrupt recovery file is retained instead of silently resetting passengers" {
        val root = createTempDirectory("mine-lift-corrupt")
        try {
            val file = root.resolve("data/mine-lift-passengers.json")
            Files.createDirectories(file.parent)
            Files.writeString(file, "not json")
            shouldThrow<Exception> { MineLiftRecovery(root) }
            Files.readString(file) shouldBe "not json"
        } finally { root.toFile().deleteRecursively() }
    }
})

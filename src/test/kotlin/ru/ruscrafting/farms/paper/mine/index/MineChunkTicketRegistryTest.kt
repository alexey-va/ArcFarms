package ru.ruscrafting.farms.paper.mine.index

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.UUID

class MineChunkTicketRegistryTest : FunSpec({
    test("one owner cannot release another owner's retained chunk") {
        val plugin = mockk<Plugin>()
        val world = mockk<World> { every { uid } returns UUID.randomUUID() }
        val chunk = mockk<Chunk> {
            every { this@mockk.world } returns world
            every { x } returns 4
            every { z } returns 7
        }
        var additions = 0
        var removals = 0
        val tickets = MineChunkTicketRegistry(
            plugin,
            addTicket = { additions++; true },
            removeTicket = { removals++ },
        )

        tickets.retain(chunk) shouldBe true
        tickets.retain(chunk) shouldBe true
        additions shouldBe 1

        tickets.release(chunk)
        removals shouldBe 0
        tickets.release(chunk)
        removals shouldBe 1
    }
})

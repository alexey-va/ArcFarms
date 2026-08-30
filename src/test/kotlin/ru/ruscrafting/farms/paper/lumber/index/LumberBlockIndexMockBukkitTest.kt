package ru.ruscrafting.farms.paper.lumber.index

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion

class LumberBlockIndexMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("reindex applies one chunk only after bounded validation") {
        val world = paper.server.addSimpleWorld("world")
        world.getBlockAt(0, 64, 0).type = Material.OAK_LOG
        world.getBlockAt(1, 64, 0).type = Material.STONE
        world.getBlockAt(2, 64, 0).type = Material.OAK_LOG
        world.getBlockAt(3, 64, 0).type = Material.BIRCH_LOG
        val definition = LumberIndexDefinition(
            "sawmill",
            CuboidActivityRegion(world, "test", CuboidBounds(0, 64, 0, 3, 64, 0)),
            setOf("OAK"),
        )
        val plugin = paper.createSimplePlugin("LumberIndexTest")
        val index = LumberBlockIndex(plugin)
        val tickets = RecordingChunkTickets()
        val job = LumberReindexJob(definition, index, tickets)

        job.tick(blockBudget = 2).finished shouldBe false
        job.tick(blockBudget = 2).finished shouldBe true

        index.logs("sawmill", "OAK") shouldContainExactly setOf(
            WorksitePosition("world", 0, 64, 0),
            WorksitePosition("world", 2, 64, 0),
        )
        tickets.retained shouldContainExactly listOf(0 to 0)
        tickets.released shouldContainExactly listOf(0 to 0)

        val rebuilt = LumberBlockIndex(plugin)
        rebuilt.reconcileChunk(definition, world.getChunkAt(0, 0))
        rebuilt.logs("sawmill", "OAK") shouldBe index.logs("sawmill", "OAK")
    }
})

private class RecordingChunkTickets : LumberChunkTicket {
    val retained = mutableListOf<Pair<Int, Int>>()
    val released = mutableListOf<Pair<Int, Int>>()

    override fun retain(chunk: org.bukkit.Chunk): Boolean {
        retained += chunk.x to chunk.z
        return true
    }

    override fun release(chunk: org.bukkit.Chunk) {
        released += chunk.x to chunk.z
    }
}

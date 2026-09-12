package ru.ruscrafting.farms.paper.mine.index

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.bukkit.Chunk
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import java.util.concurrent.CompletableFuture

class MineReindexJobBudgetMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("polls one unloaded chunk request without waiting and scans it next tick") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val deferredChunk = world.getChunkAt(1, 0).also { it.load() }
        world.getBlockAt(16, 64, 0).type = Material.IRON_ORE
        world.unloadChunk(1, 0)
        val definition = MineIndexDefinition(
            "async_reindex",
            CuboidActivityRegion(world, "test", CuboidBounds(0, 64, 0, 16, 64, 0)),
            setOf(Material.IRON_ORE),
        )
        val plugin = paper.createSimplePlugin("MineAsyncReindexTest")
        val index = MineBlockIndex(plugin)
        val tickets = RecordingTickets()
        val pending = CompletableFuture<Chunk?>()
        val requests = mutableListOf<Pair<Int, Int>>()
        val loader = MineChunkLoader { _, chunkX, chunkZ ->
            requests += chunkX to chunkZ
            pending
        }
        val job = MineReindexJob(definition, index, tickets, loader)

        job.tick(128).apply {
            finished shouldBe false
            scannedBlocks shouldBe 0L
        }
        requests shouldBe listOf(1 to 0)
        job.tick(128).scannedBlocks shouldBe 0L
        requests shouldBe listOf(1 to 0)

        deferredChunk.load()
        pending.complete(deferredChunk)
        job.tick(128).apply {
            finished shouldBe true
            scannedBlocks shouldBe 17L
        }
        index.targets("async_reindex", MineAnchorRole.MINEABLE) shouldContain
            WorksitePosition("world", 16, 64, 0)
        tickets.retained shouldBe listOf(0 to 0, 1 to 0, 1 to 0, 0 to 0)
        tickets.released shouldBe listOf(1 to 0, 0 to 0, 0 to 0, 1 to 0)
    }
    test("neighbor failure releases tickets without publishing a partial index") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val definition = MineIndexDefinition("halo_failure", CuboidActivityRegion(world, "test",
            CuboidBounds(0, 64, 0, 16, 64, 0)), setOf(Material.IRON_ORE))
        val index = MineBlockIndex(paper.createSimplePlugin("MineHaloFailureTest"))
        val tickets = RecordingTickets()
        val pending = CompletableFuture<Chunk?>()
        val job = MineReindexJob(definition, index, tickets, MineChunkLoader { _, _, _ -> pending })
        job.tick(64).scannedBlocks shouldBe 0L
        pending.completeExceptionally(IllegalStateException("load failed"))
        shouldThrow<IllegalStateException> { job.tick(64) }
        tickets.released shouldBe listOf(0 to 0)
        index.targets("halo_failure", MineAnchorRole.MINEABLE) shouldBe emptySet()
    }

})

private class RecordingTickets : MineChunkTicket {
    val retained = mutableListOf<Pair<Int, Int>>()
    val released = mutableListOf<Pair<Int, Int>>()

    override fun retain(chunk: Chunk): Boolean {
        retained += chunk.x to chunk.z
        return true
    }

    override fun release(chunk: Chunk) {
        released += chunk.x to chunk.z
    }
}

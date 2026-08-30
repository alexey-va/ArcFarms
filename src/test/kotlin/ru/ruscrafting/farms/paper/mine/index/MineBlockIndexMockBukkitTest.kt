package ru.ruscrafting.farms.paper.mine.index

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion

class MineBlockIndexMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("bounded reindex classifies reachable roles and loaded readers never reload a chunk") {
        val world = paper.server.addSimpleWorld("world")
        val ore = world.getBlockAt(1, 64, 1).also { it.type = Material.IRON_ORE }
        world.getBlockAt(1, 65, 1).type = Material.AIR
        val railFloor = world.getBlockAt(2, 63, 1).also { it.type = Material.STONE }
        world.getBlockAt(2, 64, 1).type = Material.AIR
        world.getBlockAt(2, 65, 1).type = Material.AIR
        val vent = world.getBlockAt(3, 64, 1).also { it.type = Material.COPPER_GRATE }
        val definition = MineIndexDefinition(
            "old_shafts",
            CuboidActivityRegion(world, "test", CuboidBounds(0, 63, 0, 4, 65, 2)),
            setOf(Material.STONE, Material.IRON_ORE),
        )
        val index = MineBlockIndex(paper.createSimplePlugin("MineIndexTest"))
        val tickets = RecordingMineTickets()
        val job = MineReindexJob(definition, index, tickets)

        while (!job.tick(blockBudget = 8).finished) Unit

        index.targets("old_shafts", MineAnchorRole.MINEABLE) shouldContain WorksitePosition("world", ore.x, ore.y, ore.z)
        index.targets("old_shafts", MineAnchorRole.RAIL) shouldContain WorksitePosition(
            "world", railFloor.x, railFloor.y, railFloor.z,
        )
        index.targets("old_shafts", MineAnchorRole.VENT) shouldContain WorksitePosition("world", vent.x, vent.y, vent.z)
        tickets.retained shouldContainExactly listOf(0 to 0)
        tickets.released shouldContainExactly listOf(0 to 0)

        val rebuilt = MineBlockIndex(paper.createSimplePlugin("MineIndexReadTest"))
        // Different plugin names intentionally prove that an unrelated PDC namespace remains empty.
        rebuilt.reconcileChunk(definition, world.getChunkAt(0, 0))
        rebuilt.targets("old_shafts", MineAnchorRole.MINEABLE) shouldBe emptySet()

        world.unloadChunk(0, 0)
        index.loadedTargets("old_shafts", MineAnchorRole.MINEABLE) shouldBe emptySet()
        world.isChunkLoaded(0, 0) shouldBe false
    }

    test("same namespace rebuild validates durable chunk data atomically") {
        val world = paper.server.addSimpleWorld("world")
        val ore = world.getBlockAt(1, 64, 1).also { it.type = Material.IRON_ORE }
        val plugin = paper.createSimplePlugin("MineDurableIndexTest")
        val definition = MineIndexDefinition(
            "old_shafts",
            CuboidActivityRegion(world, "test", CuboidBounds(0, 63, 0, 3, 65, 3)),
            setOf(Material.STONE, Material.IRON_ORE),
        )
        val source = MineBlockIndex(plugin)
        val job = MineReindexJob(definition, source, RecordingMineTickets())
        while (!job.tick(16).finished) Unit

        val rebuilt = MineBlockIndex(plugin)
        rebuilt.reconcileChunk(definition, world.getChunkAt(0, 0))

        rebuilt.targets("old_shafts", MineAnchorRole.MINEABLE) shouldContain WorksitePosition("world", ore.x, ore.y, ore.z)
    }
})

private class RecordingMineTickets : MineChunkTicket {
    val retained = mutableListOf<Pair<Int, Int>>()
    val released = mutableListOf<Pair<Int, Int>>()

    override fun retain(chunk: org.bukkit.Chunk): Boolean = true.also { retained += chunk.x to chunk.z }
    override fun release(chunk: org.bukkit.Chunk) { released += chunk.x to chunk.z }
}

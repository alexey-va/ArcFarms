package ru.ruscrafting.farms.paper.mine.index

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion

class MineBlockIndexMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("lift shaft anchors are excluded on cached reads and final validation at every height") {
        val world=paper.server.addSimpleWorld("world")
        val plugin=paper.createSimplePlugin("ShaftIndex")
        val lift=io.mockk.mockk<ru.ruscrafting.farms.paper.mine.lift.MineLiftAccess>()
        io.mockk.every { lift.excludesEvent(any()) } answers { firstArg<org.bukkit.Location>().x < 6.0 }
        val definition=MineIndexDefinition("old_shafts",CuboidActivityRegion(world,"test",CuboidBounds(0,50,0,15,120,15)),setOf(Material.STONE))
        val index=MineBlockIndex(plugin,lift)
        val points=listOf(WorksitePosition("world",3,60,3),WorksitePosition("world",3,100,3),WorksitePosition("world",10,100,3))
        points.forEach { world.getBlockAt(it.x,it.y,it.z).type=Material.STONE }
        index.replaceZone(definition,listOf(world.getChunkAt(0,0)),points.map { MineIndexedTarget(it,setOf(MineAnchorRole.MINER,MineAnchorRole.NEST,MineAnchorRole.MINEABLE)) })
        for(role in listOf(MineAnchorRole.MINER,MineAnchorRole.NEST)) {
            index.targets("old_shafts",role) shouldBe setOf(points.last())
            index.loadedTargets("old_shafts",role) shouldBe setOf(points.last())
            index.isLiveTarget("old_shafts",points.first(),role) shouldBe false
        }
        index.targets("old_shafts",MineAnchorRole.MINEABLE).size shouldBe 3
    }

    test("rail material filter keeps the legacy default and only gates walkable floors") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        fun floor(x: Int, material: Material): org.bukkit.block.Block {
            val block = world.getBlockAt(x, 63, 1).also { it.type = material }
            world.getBlockAt(x, 64, 1).type = Material.AIR
            world.getBlockAt(x, 65, 1).type = Material.AIR
            return block
        }

        MineAnchorClassifier.classify(floor(1, Material.STONE), emptySet()) shouldContain MineAnchorRole.RAIL
        MineAnchorClassifier.classify(floor(2, Material.POLISHED_ANDESITE), emptySet(), setOf(Material.POLISHED_ANDESITE))
            .apply {
                this shouldContain MineAnchorRole.RAIL
                this shouldContain MineAnchorRole.NEST
                this shouldContain MineAnchorRole.MINER
            }
        MineAnchorClassifier.classify(floor(3, Material.STONE), emptySet(), setOf(Material.POLISHED_ANDESITE))
            .apply {
                this shouldNotContain MineAnchorRole.RAIL
                this shouldContain MineAnchorRole.NEST
                this shouldContain MineAnchorRole.MINER
            }

        val blocked = world.getBlockAt(4, 63, 1).also { it.type = Material.POLISHED_ANDESITE }
        world.getBlockAt(4, 64, 1).type = Material.STONE
        world.getBlockAt(4, 65, 1).type = Material.AIR
        MineAnchorClassifier.classify(blocked, emptySet(), setOf(Material.POLISHED_ANDESITE)).apply {
            this shouldNotContain MineAnchorRole.RAIL
            this shouldNotContain MineAnchorRole.NEST
            this shouldNotContain MineAnchorRole.MINER
        }
    }

    test("bounded reindex classifies reachable roles and loaded readers never reload a chunk") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
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
        index.flushDirty(Int.MAX_VALUE)

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
        world.getChunkAt(0, 0).load()
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
        source.flushDirty(Int.MAX_VALUE)

        val rebuilt = MineBlockIndex(plugin)
        rebuilt.reconcileChunk(definition, world.getChunkAt(0, 0))

        rebuilt.targets("old_shafts", MineAnchorRole.MINEABLE) shouldContain WorksitePosition("world", ore.x, ore.y, ore.z)
    }

    test("large indexes round trip through a byte array without the NBT string limit") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val definition = MineIndexDefinition(
            "large_index",
            CuboidActivityRegion(world, "test", CuboidBounds(0, 0, 0, 15, 255, 15)),
            setOf(Material.IRON_ORE),
        )
        val targets = (0 until 4096).map { index ->
            val x = index and 15
            val z = (index shr 4) and 15
            val y = 64 + (index shr 8)
            world.getBlockAt(x, y, z).type = Material.IRON_ORE
            MineIndexedTarget(WorksitePosition("world", x, y, z), setOf(MineAnchorRole.MINEABLE))
        }
        val plugin = paper.createSimplePlugin("MineLargeIndexTest")
        val chunk = world.getChunkAt(0, 0)
        MineBlockIndex(plugin).apply {
            replaceZone(definition, listOf(chunk), targets)
            flushDirty(Int.MAX_VALUE)
        }

        val key = NamespacedKey(plugin, "mine_index_large_index")
        val container = chunk.persistentDataContainer
        container.has(key, PersistentDataType.BYTE_ARRAY) shouldBe true
        container.has(key, PersistentDataType.STRING) shouldBe false
        val encoded = requireNotNull(container.get(key, PersistentDataType.BYTE_ARRAY))
        encoded.size shouldBe 8 + targets.size * 4
        val legacy = targets.joinToString(";") { "${it.position.x},${it.position.y},${it.position.z},MINEABLE" }
        (legacy.toByteArray().size > 65_535) shouldBe true

        val rebuilt = MineBlockIndex(plugin)
        rebuilt.reconcileChunk(definition, chunk)
        rebuilt.targets("large_index", MineAnchorRole.MINEABLE).size shouldBe targets.size
    }

    test("valid legacy string indexes migrate in place during chunk reconciliation") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val ore = world.getBlockAt(1, 64, 1).also { it.type = Material.IRON_ORE }
        val definition = MineIndexDefinition(
            "legacy_index",
            CuboidActivityRegion(world, "test", CuboidBounds(0, 63, 0, 3, 65, 3)),
            setOf(Material.IRON_ORE),
        )
        val plugin = paper.createSimplePlugin("MineLegacyIndexTest")
        val chunk = world.getChunkAt(0, 0)
        val key = NamespacedKey(plugin, "mine_index_legacy_index")
        chunk.persistentDataContainer.set(key, PersistentDataType.STRING, "1,64,1,MINEABLE")

        val rebuilt = MineBlockIndex(plugin)
        rebuilt.reconcileChunk(definition, chunk)
        rebuilt.flushDirty()

        chunk.persistentDataContainer.has(key, PersistentDataType.BYTE_ARRAY) shouldBe true
        chunk.persistentDataContainer.has(key, PersistentDataType.STRING) shouldBe false
        rebuilt.targets("legacy_index", MineAnchorRole.MINEABLE) shouldContain WorksitePosition("world", ore.x, ore.y, ore.z)
    }
    test("updates coalesce per chunk and unchanged blocks do not rewrite the index") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        world.getChunkAt(1, 0).load()
        val plugin = paper.createSimplePlugin("MineCoalescedIndexTest")
        val definition = MineIndexDefinition("coalesced", CuboidActivityRegion(world, "test",
            CuboidBounds(0, 63, 0, 31, 80, 15)), setOf(Material.IRON_ORE))
        val index = MineBlockIndex(plugin)
        val first = world.getBlockAt(2, 64, 2).also { it.type = Material.IRON_ORE }
        val second = world.getBlockAt(3, 64, 2).also { it.type = Material.IRON_ORE }
        val otherChunk = world.getBlockAt(18, 64, 2).also { it.type = Material.IRON_ORE }
        repeat(32) { index.refreshBlock(definition, first) }
        index.refreshBlock(definition, second)
        index.refreshBlock(definition, otherChunk)
        index.contains("coalesced", first, MineAnchorRole.MINEABLE) shouldBe true
        index.flushDirty() shouldBe 1
        index.flushDirty() shouldBe 1
        index.flushDirty() shouldBe 0
        index.refreshBlock(definition, first)
        index.flushDirty() shouldBe 0
        first.type = Material.AIR
        index.refreshBlock(definition, first)
        index.contains("coalesced", first, MineAnchorRole.MINEABLE) shouldBe false
        index.contains("coalesced", second, MineAnchorRole.MINEABLE) shouldBe true
        index.flushChunk(first.chunk)
        index.flushDirty() shouldBe 0
        val rebuilt = MineBlockIndex(plugin)
        rebuilt.reconcileChunk(definition, first.chunk)
        rebuilt.contains("coalesced", first, MineAnchorRole.MINEABLE) shouldBe false
        rebuilt.contains("coalesced", second, MineAnchorRole.MINEABLE) shouldBe true
    }

    test("classification at build and chunk boundaries never loads a neighboring chunk") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val edge = world.getBlockAt(15, world.minHeight, 15).also { it.type = Material.IRON_ORE }
        world.unloadChunk(1, 0)
        world.unloadChunk(0, 1)
        MineAnchorClassifier.classify(edge, setOf(Material.IRON_ORE)) shouldContain MineAnchorRole.MINEABLE
        world.isChunkLoaded(1, 0) shouldBe false
        world.isChunkLoaded(0, 1) shouldBe false
    }

    test("corrupt persisted payload removes the old cached target without overwriting evidence") {
        val world = paper.server.addSimpleWorld("world")
        val chunk = world.getChunkAt(0, 0).also { it.load() }
        val plugin = paper.createSimplePlugin("MineCorruptIndexTest")
        val definition = MineIndexDefinition("corrupt", CuboidActivityRegion(world, "test",
            CuboidBounds(0, 63, 0, 15, 80, 15)), setOf(Material.IRON_ORE))
        val ore = world.getBlockAt(3, 64, 3).also { it.type = Material.IRON_ORE }
        val index = MineBlockIndex(plugin)
        index.refreshBlock(definition, ore)
        index.flushDirty()
        val key = NamespacedKey(plugin, "mine_index_corrupt")
        val corrupt = byteArrayOf(0x4d, 0x49, 99)
        chunk.persistentDataContainer.set(key, PersistentDataType.BYTE_ARRAY, corrupt)
        index.reconcileChunk(definition, chunk)
        index.contains("corrupt", ore, MineAnchorRole.MINEABLE) shouldBe false
        chunk.persistentDataContainer.get(key, PersistentDataType.BYTE_ARRAY)?.contentEquals(corrupt) shouldBe true
    }

    test("verified boundary anchors survive a missing neighbor and become live when it loads") {
        val world = paper.server.addSimpleWorld("world")
        val chunk = world.getChunkAt(0, 0).also { it.load() }
        world.getChunkAt(1, 0).load()
        val plugin = paper.createSimplePlugin("MineBoundaryIndexTest")
        val definition = MineIndexDefinition("boundary", CuboidActivityRegion(world, "test",
            CuboidBounds(0, 63, 0, 31, 80, 15)), setOf(Material.IRON_ORE))
        val ore = world.getBlockAt(15, 64, 7).also { it.type = Material.IRON_ORE }
        listOf(org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
            org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.WEST).forEach { ore.getRelative(it).type = Material.STONE }
        world.getBlockAt(16, 64, 7).type = Material.AIR
        val index = MineBlockIndex(plugin)
        index.refreshBlock(definition, ore)
        index.flushDirty()
        world.unloadChunk(1, 0)
        index.reconcileChunk(definition, chunk)
        index.contains("boundary", ore, MineAnchorRole.SUPPORT) shouldBe true
        val position = WorksitePosition(world.name, ore.x, ore.y, ore.z)
        index.isLiveTarget("boundary", position, MineAnchorRole.SUPPORT) shouldBe false
        world.isChunkLoaded(1, 0) shouldBe false
        world.getChunkAt(1, 0).load()
        index.isLiveTarget("boundary", position, MineAnchorRole.SUPPORT) shouldBe true
    }

})

private class RecordingMineTickets : MineChunkTicket {
    val retained = mutableListOf<Pair<Int, Int>>()
    val released = mutableListOf<Pair<Int, Int>>()

    override fun retain(chunk: org.bukkit.Chunk): Boolean = true.also { retained += chunk.x to chunk.z }
    override fun release(chunk: org.bukkit.Chunk) { released += chunk.x to chunk.z }
}

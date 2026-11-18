package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmCropLayoutSettings
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.paper.fixtures.FarmAsyncHarness
import java.util.concurrent.CompletableFuture

class FarmBlockRegistryAsyncTest : FunSpec({
    test("a late legacy journal is reconciled and retained topology is removed after the journal clears") {
        FarmAsyncHarness("FarmEmptyChunkLedger").use { h ->
            val world = h.paper.server.addSimpleWorld("farm_empty_chunk")
            val leaf = world.getBlockAt(4, 66, 4).also { it.type = Material.OAK_LEAVES }
            world.getBlockAt(4, 65, 4).type = Material.AIR
            val chunk = leaf.chunk.also { it.load() }
            val definition = definition(world, 15)
            val ledger = FarmBlockLedger(h.plugin)
            FarmBlockRegistry(h.plugin, ledger, tasks = h.tasks).use { registry ->
                registry.reconcileChunkAsync(definition, chunk).isDone shouldBe true

                // A later write to the same chunk must be considered even if only a legacy key exists.
                val orchardKey = NamespacedKey(h.plugin, "farm_orchard_leaves_v1")
                val container = chunk.persistentDataContainer
                container.set(orchardKey, PersistentDataType.STRING, """[{"zoneId":"farm","x":4,"y":66,"z":4}]""")
                val indexed = registry.reconcileChunkAsync(definition, chunk)
                (registry.reconcileChunkAsync(definition, chunk) === indexed) shouldBe true
                h.until { indexed.isDone }
                indexed.join()
                registry.orchardLeaves("farm") shouldBe setOf(FarmPlotPosition(world.name, 4, 66, 4))

                ledger.replaceZoneIndex(chunk, "farm", emptyList(), emptyList(), emptyList())
                val cleared = registry.reconcileChunkAsync(definition, chunk)
                registry.isReindexing("farm") shouldBe true
                h.until { cleared.isDone }
                cleared.join()

                registry.orchardLeaves("farm") shouldBe emptySet()
                registry.isReindexing("farm") shouldBe false
            }
        }
    }

    listOf(false, true).forEach { async ->
        test("${if (async) "async" else "sync"} chunk reconciliation recovers unindexed raid journals outside the home region only") {
            FarmAsyncHarness("FarmRaidOrphanRecovery").use { h ->
                val world = h.paper.server.addSimpleWorld("raid_orphans")
                val home = world.getBlockAt(8, 64, 8).also { it.type = Material.DIRT_PATH }
                val enemy = world.getBlockAt(42, 64, 42).also { it.type = Material.FARMLAND }
                home.getRelative(org.bukkit.block.BlockFace.UP).type = Material.AIR
                enemy.getRelative(org.bukkit.block.BlockFace.UP).type = Material.CARROTS
                val originals = listOf(home, enemy).associateWith {
                    it.blockData.asString to it.getRelative(org.bukkit.block.BlockFace.UP).blockData.asString
                }
                val saved = FarmBlockLedger(h.plugin)
                saved.beginTemporaryRemoval(listOf(home, enemy), "farm", "raid:farm:7", 100L)
                val foreign = world.getBlockAt(9, 64, 8).also { it.type = Material.FARMLAND }
                val otherZone = world.getBlockAt(10, 64, 8).also { it.type = Material.FARMLAND }
                saved.beginTemporaryRemoval(listOf(foreign), "farm", "tornado:farm:7", 100L)
                saved.beginTemporaryRemoval(listOf(otherZone), "other", "raid:other:7", 100L)
                listOf(home, enemy, foreign, otherZone).forEach {
                    it.chunk.load()
                    it.type = Material.COARSE_DIRT
                    it.getRelative(org.bukkit.block.BlockFace.UP).type = Material.FIRE
                }
                val ledger = FarmBlockLedger(h.plugin)
                val registry = FarmBlockRegistry(h.plugin, ledger, tasks = h.tasks.takeIf { async })

                if (async) {
                    val futures = world.loadedChunks.map { registry.reconcileChunkAsync(definition(world, 15), it) }
                    h.until { futures.all { it.isDone } }
                    futures.forEach { it.join() }
                } else world.loadedChunks.forEach { registry.reconcileChunk(definition(world, 15), it) }

                originals.forEach { (soil, original) ->
                    (soil.blockData.asString to soil.getRelative(org.bukkit.block.BlockFace.UP).blockData.asString) shouldBe original
                    ledger.record(soil) shouldBe null
                }
                registry.beds("farm") shouldBe emptySet()
                foreign.type shouldBe Material.COARSE_DIRT
                ledger.record(foreign)?.temporaryMutation shouldBe "tornado:farm:7"
                otherZone.type shouldBe Material.COARSE_DIRT
                ledger.record(otherZone)?.temporaryMutation shouldBe "raid:other:7"
                registry.close()
            }
        }
    }

    test("chunk reconciliation accepts a distinct wrapper of the same loaded chunk") {
        FarmAsyncHarness("FarmChunkWrapper").use { h ->
            val world = h.paper.server.addSimpleWorld("farm_chunk_wrapper")
            val soil = world.getBlockAt(2, 64, 2).also { it.type = Material.FARMLAND }
            soil.getRelative(org.bukkit.block.BlockFace.UP).type = Material.WHEAT
            val fixed = world.getBlockAt(3, 65, 3).also { it.type = Material.PUMPKIN }
            val leaf = world.getBlockAt(4, 66, 4).also { it.type = Material.OAK_LEAVES }
            val chunk = soil.chunk.also { it.load() }
            FarmBlockLedger(h.plugin).replaceZoneIndex(chunk, "farm", listOf(soil), listOf(fixed), listOf(leaf))
            val wrapper: Chunk = object : Chunk by chunk {}
            (wrapper === world.getChunkAt(chunk.x, chunk.z)) shouldBe false
            wrapper.isLoaded shouldBe true
            val registry = FarmBlockRegistry(h.plugin, FarmBlockLedger(h.plugin), tasks = h.tasks)

            val result = registry.reconcileChunkAsync(definition(world, 15), wrapper)
            h.until { result.isDone }
            result.join()

            registry.beds("farm") shouldBe setOf(FarmPlotPosition(world.name, 2, 64, 2))
            registry.fixedCrops("farm") shouldBe setOf(FarmPlotPosition(world.name, 3, 65, 3))
            registry.orchardLeaves("farm") shouldBe setOf(FarmPlotPosition(world.name, 4, 66, 4))
            registry.isReindexing("farm") shouldBe false
            registry.close()
        }
    }

    test("unloading a chunk during worker decode prevents reconciliation from publishing topology") {
        FarmAsyncHarness("FarmChunkUnload").use { h ->
            val world = h.paper.server.addSimpleWorld("farm_chunk_unload")
            val soil = world.getBlockAt(2, 64, 2).also { it.type = Material.FARMLAND }
            soil.getRelative(org.bukkit.block.BlockFace.UP).type = Material.WHEAT
            val chunk = soil.chunk.also { it.load() }
            FarmBlockLedger(h.plugin).replaceZoneIndex(chunk, "farm", listOf(soil), emptyList(), emptyList())
            val registry = FarmBlockRegistry(h.plugin, FarmBlockLedger(h.plugin), tasks = h.tasks)
            val accepted = FarmPlotPosition(world.name, 1, 64, 1)
            registry.addBeds("farm", listOf(accepted))
            h.gate = CompletableFuture()
            val result = registry.reconcileChunkAsync(definition(world, 15), chunk)
            h.until { h.totalNativeSteps >= 3 }

            world.unloadChunk(chunk.x, chunk.z, true) shouldBe true
            h.gate!!.complete(Unit)
            h.until { result.isDone }

            result.isCompletedExceptionally shouldBe true
            world.isChunkLoaded(chunk.x, chunk.z) shouldBe false
            registry.beds("farm") shouldBe setOf(accepted)
            registry.isReindexing("farm") shouldBe false
            registry.close()
        }
    }

    test("fresh chunk wrappers preserve the exact legacy topology and chunk journal during async reindex") {
        FarmAsyncHarness("FarmIndexAsync").use { h ->
            val world = h.paper.server.addSimpleWorld("farm_index")
            for (x in 0..1) for (z in 0..1) world.getChunkAt(x, z).load()
            fun resetBlocks() {
                for (x in 0..31) for (z in 0..31) {
                    world.getBlockAt(x, 64, z).type = Material.FARMLAND
                    world.getBlockAt(x, 65, z).type = Material.AIR
                }
                world.getBlockAt(3, 65, 3).type = Material.PUMPKIN
                world.getBlockAt(9, 66, 9).type = Material.OAK_LEAVES
            }
            resetBlocks()
            Tasks.install(TestTaskScheduler())
            val legacyLedger = FarmBlockLedger(h.plugin)
            val legacy = FarmBlockRegistry(h.plugin, legacyLedger, nanoTime = { 0L },
                addChunkTicket = { true }, removeChunkTicket = {})
            var legacyResult: FarmBlockReindexResult? = null
            val definition = definition(world, 31)
            legacy.startReindex(definition, {}, { legacyResult = it }, { throw it })
            requireNotNull(legacyResult)
            val expectedBeds = legacy.beds("farm").toSet()
            val expectedFixed = legacy.fixedCrops("farm").toSet()
            val expectedLeaves = legacy.orchardLeaves("farm").toSet()
            val expectedRecords = world.loadedChunks.associate { chunk ->
                (chunk.x to chunk.z) to FarmLedgerContents(legacyLedger.blockRecords(chunk),
                    legacyLedger.fixedCropRecords(chunk), legacyLedger.orchardLeafRecords(chunk))
            }
            resetBlocks()
            world.loadedChunks.forEach { chunk ->
                listOf("farm_managed_blocks_v1", "farm_fixed_crops_v1", "farm_orchard_leaves_v1").forEach {
                    chunk.persistentDataContainer.remove(NamespacedKey(h.plugin, it))
                }
            }
            val ledger = FarmBlockLedger(h.plugin)
            var releases = 0
            val registry = FarmBlockRegistry(h.plugin, ledger, addChunkTicket = { true },
                removeChunkTicket = { releases++ }, tasks = h.tasks)
            // Preserve MockBukkit storage while reproducing CraftWorld's fresh Chunk wrappers.
            val freshChunkWorld = object : World by world {
                override fun getChunkAt(x: Int, z: Int): Chunk {
                    val chunk = world.getChunkAt(x, z)
                    return object : Chunk by chunk {
                        override fun getChunkSnapshot(includeMaxblocky: Boolean, includeBiome: Boolean, includeBiomeTempRain: Boolean) =
                            chunk.getChunkSnapshot(includeMaxblocky, includeBiome, includeBiomeTempRain)
                    }
                }
            }
            val asyncDefinition = definition.copy(region = object : ActivityRegion by definition.region {
                override val world: World = freshChunkWorld
            })
            var result: FarmBlockReindexResult? = null
            var failure: Throwable? = null
            registry.startReindex(asyncDefinition, {}, { result = it }, { failure = it })
            result shouldBe null
            h.until { result != null || failure != null }
            failure?.let { throw it }
            result!!.status.scannedBlocks shouldBe legacyResult!!.status.scannedBlocks
            registry.beds("farm") shouldBe expectedBeds
            registry.fixedCrops("farm") shouldBe expectedFixed
            registry.orchardLeaves("farm") shouldBe expectedLeaves
            world.loadedChunks.forEach { chunk ->
                FarmLedgerContents(ledger.blockRecords(chunk), ledger.fixedCropRecords(chunk), ledger.orchardLeafRecords(chunk)) shouldBe
                    expectedRecords[chunk.x to chunk.z]
            }
            (h.ticks > 1) shouldBe true
            (h.workerCalls.get() > 1) shouldBe true
            (h.maximumTickSteps <= 512) shouldBe true
            releases shouldBe 8 // Four scans and four applies; no borrowed lease is removed.
            registry.close()
            Tasks.reset()
        }
    }

    test("cancelling while worker decode is delayed releases only the retired job ticket") {
        FarmAsyncHarness("FarmIndexCancel").use { h ->
            val world = h.paper.server.addSimpleWorld("farm_index_cancel")
            world.getChunkAt(0, 0).load()
            var releases = 0
            val registry = FarmBlockRegistry(h.plugin, FarmBlockLedger(h.plugin), addChunkTicket = { true },
                removeChunkTicket = { releases++ }, tasks = h.tasks)
            val accepted = FarmPlotPosition(world.name, 1, 64, 1)
            registry.addBeds("farm", listOf(accepted))
            h.gate = CompletableFuture()
            registry.startReindex(definition(world, 15), {}, { error("Cancelled job completed") }, { throw it })
            repeat(5) { h.tick() }
            registry.cancelReindexes()
            releases shouldBe 1
            h.gate!!.complete(Unit)
            repeat(20) { h.tick() }
            registry.beds("farm") shouldBe setOf(accepted)
            registry.status("farm") shouldBe null
            releases shouldBe 1
            registry.close()
        }
    }

    test("cold 300-record temporary recovery is decoded on worker and restored before publishing topology") {
        FarmAsyncHarness("FarmRecoveryAsync").use { h ->
            val world = h.paper.server.addSimpleWorld("farm_recovery")
            for (x in 0..1) world.getChunkAt(x, 0).load()
            val soils = (0 until 300).map { i -> world.getBlockAt(i / 16, 64, i % 16).also {
                it.type = Material.FARMLAND
                it.getRelative(org.bukkit.block.BlockFace.UP).type = Material.WHEAT
            } }
            val fixtureLedger = FarmBlockLedger(h.plugin)
            soils.groupBy { it.chunk }.forEach { (chunk, group) ->
                fixtureLedger.replaceZoneIndex(chunk, "farm", group, emptyList(), emptyList())
            }
            fixtureLedger.beginTemporaryRemoval(soils, "farm", "raid:farm:7", 100)
            soils.forEach { it.type = Material.AIR; it.getRelative(org.bukkit.block.BlockFace.UP).type = Material.AIR }
            val ledger = FarmBlockLedger(h.plugin)
            val registry = FarmBlockRegistry(h.plugin, ledger, tasks = h.tasks)
            val futures = world.loadedChunks.map { registry.reconcileChunkAsync(definition(world, 31), it) }
            registry.isReindexing("farm") shouldBe true
            h.until { futures.all { it.isDone } }
            futures.forEach { it.join() }
            soils.all { it.type == Material.FARMLAND && it.getRelative(org.bukkit.block.BlockFace.UP).type == Material.WHEAT } shouldBe true
            registry.beds("farm").size shouldBe 300
            world.loadedChunks.flatMap(ledger::blockRecords).all { it.temporaryMutation == null } shouldBe true
            (h.maximumTickSteps <= 512) shouldBe true
            (h.ticks > 1) shouldBe true
            registry.isReindexing("farm") shouldBe false
            registry.close()
        }
    }
})

private fun definition(world: World, maximum: Int) = FarmBlockIndexDefinition("farm",
    CuboidActivityRegion(world, "farm", CuboidBounds(0, 64, 0, maximum, 66, maximum)),
    setOf("WHEAT", "CARROTS", "PUMPKIN"), 10_000, 20_000, 1_024,
    FarmCropLayoutSettings(true, linkedMapOf("WHEAT" to 3, "CARROTS" to 2), 0, 0))

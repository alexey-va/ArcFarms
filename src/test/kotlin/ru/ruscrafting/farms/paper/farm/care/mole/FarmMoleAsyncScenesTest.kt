package ru.ruscrafting.farms.paper.farm.care.mole

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import ru.ruscrafting.farms.config.*
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.paper.*
import ru.ruscrafting.farms.paper.fixtures.FarmAsyncHarness
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder
import ru.ruscrafting.farms.paper.fixtures.MockBukkitMoleBurrowChunkRetention
import java.util.concurrent.CompletableFuture
import java.util.logging.Handler
import java.util.logging.LogRecord

class FarmMoleAsyncScenesTest : FunSpec({
    listOf(false to Material.STONE, false to Material.AIR, true to Material.STONE).forEach { (greenhouse, surrounding) ->
        test("${if (greenhouse) "greenhouse" else "maze"} in $surrounding keeps exact geometry and journals before bounded build and restore") {
            FarmAsyncHarness("MoleAsyncTest").use { h ->
                val runtime = runtime(h, surrounding)
                val surface = FarmPointPosition(runtime.region.world.name, 0.5, 65.05, 0.5)
                val legacy = FarmMoleBurrowWorld(h.plugin, ArcFarmsDebug({ false }) {},
                    MockBukkitMoleBurrowChunkRetention(), MockBukkitFarmBlockDataDecoder)
                val expected = requireNotNull(if (greenhouse) legacy.previewGreenhouseChamber(runtime, surface)
                    else legacy.preview(runtime, surface))
                val world = FarmMoleBurrowWorld(h.plugin, ArcFarmsDebug({ false }) {},
                    MockBukkitMoleBurrowChunkRetention(), MockBukkitFarmBlockDataDecoder,
                    if (greenhouse) "farm_greenhouse" else "farm_mole_burrow", h.tasks)
                val preview = if (greenhouse) world.previewGreenhouseChamberDetailedAsync(runtime, surface)
                    else world.previewDetailedAsync(runtime, surface)
                preview.isDone shouldBe false
                h.until { preview.isDone }
                val actual = requireNotNull(preview.join().scene)
                actual.records shouldBe expected.records
                actual.start shouldBe expected.start
                actual.lair shouldBe expected.lair
                actual.index shouldBe expected.index
                (h.ticks > 1) shouldBe true
                (h.workerCalls.get() > 1) shouldBe true
                (h.maximumTickSteps <= 512) shouldBe true
                actual.records.all { runtime.region.world.getBlockAt(it.x, it.y, it.z).blockData.asString == it.originalData } shouldBe true

                val committed = world.prepareScenesAsync(listOf(actual))
                committed.isDone shouldBe false
                h.until(before = { world.process(512) { true } }) { committed.isDone }
                committed.join() shouldBe true
                h.until(before = { world.process(512) { true } }) { actual.ready }
                actual.records.all { runtime.region.world.getBlockAt(it.x, it.y, it.z).blockData.asString == it.burrowData } shouldBe true
                world.beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)
                h.until(before = { world.process(512) { true } }) { !world.restoring(runtime.settings.id) }
                actual.records.all { runtime.region.world.getBlockAt(it.x, it.y, it.z).blockData.asString == it.originalData } shouldBe true
                world.clearQueues()
            }
        }
    }

    test("candidate previews share only in-flight work and preparation restores a fresh original") {
        FarmAsyncHarness("MoleFreshTest").use { h ->
            val runtime = runtime(h)
            val world = FarmMoleBurrowWorld(h.plugin, ArcFarmsDebug({ false }) {},
                MockBukkitMoleBurrowChunkRetention(), MockBukkitFarmBlockDataDecoder, tasks = h.tasks)
            val surface = FarmPointPosition(runtime.region.world.name, .5, 65.05, .5)
            val target = FarmCareTarget(0, FarmCareRole.MOLE_MOUND, surface)
            runtime.state = runtime.state.copy(careTargets = listOf(target))
            val candidate = world.previewDetailedAsync(runtime, surface)
            (world.previewDetailedAsync(runtime, surface) === candidate) shouldBe true
            h.until { candidate.isDone }
            val stale = requireNotNull(candidate.join().scene)
            val changed = stale.records.first()
            val block = stale.world.getBlockAt(changed.x, changed.y, changed.z)
            block.type = Material.DIRT
            val freshOriginal = block.blockData.asString

            val prepared = world.prepareAsync(runtime, listOf(target), runtime.state.placementSequence)
            h.until(before = { world.process(512) { true } }) { prepared.isDone }
            prepared.join() shouldBe true
            val actual = requireNotNull(world.scene(runtime, target.id))
            actual.records.first { it.x == changed.x && it.y == changed.y && it.z == changed.z }.originalData shouldBe freshOriginal
            h.until(before = { world.process(512) { true } }) { actual.ready }
            world.beginRestore(actual.world, actual.zoneId, actual.sequence)
            h.until(before = { world.process(512) { true } }) { !world.restoring(actual.zoneId) }
            block.blockData.asString shouldBe freshOriginal
            actual.records.all { actual.world.getBlockAt(it.x, it.y, it.z).blockData.asString == it.originalData } shouldBe true
            world.clearQueues()
        }
    }

    test("a block changed after the final snapshot is rejected with exact conflict diagnostics") {
        FarmAsyncHarness("MoleConflictTest").use { h ->
            val runtime = runtime(h)
            val world = FarmMoleBurrowWorld(h.plugin, ArcFarmsDebug({ false }) {},
                MockBukkitMoleBurrowChunkRetention(), MockBukkitFarmBlockDataDecoder, tasks = h.tasks)
            val preview = world.previewDetailedAsync(runtime, FarmPointPosition(runtime.region.world.name, .5, 65.05, .5))
            h.until { preview.isDone }
            val scene = requireNotNull(preview.join().scene)
            val changed = scene.records.first()
            val block = scene.world.getBlockAt(changed.x, changed.y, changed.z)
            block.type = Material.DIRT
            val actual = block.blockData.asString
            val logs = mutableListOf<LogRecord>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { logs += record }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.plugin.logger.addHandler(handler)
            try {
                val prepared = world.prepareScenesAsync(listOf(scene))
                h.until(before = { world.process(512) { true } }) { prepared.isDone }
                prepared.join() shouldBe false
                h.until(before = { world.process(512) { true } }) { !world.restoring(scene.zoneId) }
                block.blockData.asString shouldBe actual
                val journalKey = NamespacedKey(h.plugin, "farm_mole_burrow_v1")
                scene.world.loadedChunks.all { it.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY) == null } shouldBe true
                val diagnostic = logs.joinToString("\n") { entry ->
                    entry.message + generateSequence(entry.thrown) { it.cause }.joinToString("\n") { it.message.orEmpty() }
                }
                listOf("world=${changed.world}", "x=${changed.x}", "y=${changed.y}", "z=${changed.z}",
                    "expected=${changed.originalData}", "actual=$actual").forEach {
                    withClue("Missing $it in $diagnostic") { (it in diagnostic) shouldBe true }
                }
            } finally {
                h.plugin.logger.removeHandler(handler)
                world.clearQueues()
            }
        }
    }

    test("a source change during worker geometry cannot publish or mutate the old maze") {
        FarmAsyncHarness("MoleFenceTest").use { h ->
            val runtime = runtime(h)
            val world = FarmMoleBurrowWorld(h.plugin, ArcFarmsDebug({ false }) {},
                MockBukkitMoleBurrowChunkRetention(), MockBukkitFarmBlockDataDecoder, tasks = h.tasks)
            h.gate = CompletableFuture()
            val preview = world.previewDetailedAsync(runtime, FarmPointPosition(runtime.region.world.name, .5, 65.05, .5))
            repeat(4) { h.tick() }
            runtime.state = runtime.state.copy(sequence = runtime.state.sequence + 1)
            h.gate!!.complete(Unit)
            h.until { preview.isDone }
            preview.isCompletedExceptionally shouldBe true
            runtime.region.world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            world.clearQueues()
        }
    }

    test("live-sized eight-cell maze builds a sealed floor walls and ceiling across void and restores exactly") {
        FarmAsyncHarness("MoleVoidShellTest").use { h ->
            val runtime = runtime(h, Material.AIR)
            val nativeWorld = runtime.region.world
            for (x in -3..3) for (z in -3..3) nativeWorld.getChunkAt(x, z).load()
            runtime.region = CuboidActivityRegion(nativeWorld, "farm", CuboidBounds(-40, 63, -40, 40, 67, 40))
            val liveSize = runtime.settings.moleBurrow.copy(cells = 8, tunnelWidth = 2, tunnelHeight = 3,
                minDepth = 10, maxDepth = 56)
            every { runtime.settings.moleBurrow } returns liveSize
            val world = FarmMoleBurrowWorld(h.plugin, ArcFarmsDebug({ false }) {},
                MockBukkitMoleBurrowChunkRetention(), MockBukkitFarmBlockDataDecoder, tasks = h.tasks)
            val preview = world.previewDetailedAsync(runtime, FarmPointPosition(nativeWorld.name, .5, 65.05, .5))
            h.until { preview.isDone }
            val scene = requireNotNull(preview.join().scene)
            scene.records.any { it.originalData == "minecraft:air" && it.burrowData != "minecraft:air" } shouldBe true
            val prepared = world.prepareScenesAsync(listOf(scene))
            h.until(before = { world.process(512) { true } }) { prepared.isDone && scene.ready }
            prepared.join() shouldBe true
            reachesOutsideBuiltBurrow(scene) shouldBe false
            (h.maximumTickSteps <= 512) shouldBe true
            world.beginRestore(nativeWorld, scene.zoneId, scene.sequence)
            h.until(before = { world.process(512) { true } }) { !world.restoring(scene.zoneId) }
            scene.records.all { nativeWorld.getBlockAt(it.x, it.y, it.z).blockData.asString == it.originalData } shouldBe true
            world.clearQueues()
        }
    }
})

private fun reachesOutsideBuiltBurrow(scene: FarmMoleBurrowScene): Boolean {
    val notFull = setOf(Material.AIR, Material.LIGHT, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
        Material.POINTED_DRIPSTONE, Material.MOSS_CARPET, Material.HANGING_ROOTS, Material.SPORE_BLOSSOM)
    val solid = scene.records.filter { scene.world.getBlockAt(it.x, it.y, it.z).type !in notFull }
        .mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
    val xBounds = solid.minOf { it.first }..solid.maxOf { it.first }
    val yBounds = solid.minOf { it.second }..solid.maxOf { it.second }
    val zBounds = solid.minOf { it.third }..solid.maxOf { it.third }
    val origin = Triple(scene.start.blockX, scene.start.blockY, scene.start.blockZ)
    val visited = hashSetOf(origin)
    val queue = ArrayDeque<Triple<Int, Int, Int>>().apply { add(origin) }
    while (queue.isNotEmpty()) {
        val (x, y, z) = queue.removeFirst()
        if (x !in xBounds || y !in yBounds || z !in zBounds) return true
        listOf(Triple(x + 1, y, z), Triple(x - 1, y, z), Triple(x, y + 1, z),
            Triple(x, y - 1, z), Triple(x, y, z + 1), Triple(x, y, z - 1)).forEach { next ->
            if (next !in solid && visited.add(next)) queue.addLast(next)
        }
    }
    return false
}

private fun runtime(h: FarmAsyncHarness, surrounding: Material = Material.STONE): FarmRuntime {
    val world = h.paper.server.addSimpleWorld("farm_async_maze")
    for (x in -2..2) for (z in -2..2) world.getChunkAt(x, z).load()
    for (x in -24..24) for (z in -24..24) for (y in 43..64) {
        world.getBlockAt(x, y, z).type = if (y == 64) Material.STONE else surrounding
    }
    val burrow = FarmMoleBurrowSettings(cells = 6, minDepth = 10, maxDepth = 12, tunnelHeight = 3,
        blocksPerTick = 48, candidateAttempts = 4, lightSpacing = 5, lightLevel = 11,
        lairVisual = FarmCareVisualSettings("RABBIT_HIDE", 0, FarmItemDisplayTransform.FIXED, 1.6f, .6))
    val settings = mockk<FarmZoneSettings> {
        every { id } returns "communal_farm"
        every { crops } returns setOf("WHEAT")
        every { moleBurrow } returns burrow
    }
    return FarmRuntime(settings, CuboidActivityRegion(world, "farm", CuboidBounds(-24, 0, -24, 24, 128, 24)),
        emptyMap(), emptyList(), FarmRules(listOf(50), 1, 1000), FarmShiftState(phase = FarmPhase.CARE, sequence = 5))
}

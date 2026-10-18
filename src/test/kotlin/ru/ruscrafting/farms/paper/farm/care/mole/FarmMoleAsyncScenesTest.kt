package ru.ruscrafting.farms.paper.farm.care.mole

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import ru.ruscrafting.farms.config.*
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.paper.*
import ru.ruscrafting.farms.paper.fixtures.FarmAsyncHarness
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder
import ru.ruscrafting.farms.paper.fixtures.MockBukkitMoleBurrowChunkRetention
import java.util.concurrent.CompletableFuture

class FarmMoleAsyncScenesTest : FunSpec({
    listOf(false, true).forEach { greenhouse ->
        test("${if (greenhouse) "greenhouse" else "maze"} keeps exact geometry and journals before bounded build and restore") {
            FarmAsyncHarness("MoleAsyncTest").use { h ->
                val runtime = runtime(h)
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
})

private fun runtime(h: FarmAsyncHarness): FarmRuntime {
    val world = h.paper.server.addSimpleWorld("farm_async_maze")
    for (x in -2..2) for (z in -2..2) world.getChunkAt(x, z).load()
    for (x in -24..24) for (z in -24..24) for (y in 43..64) world.getBlockAt(x, y, z).type = Material.STONE
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

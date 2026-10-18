package ru.ruscrafting.farms.paper.farm.field

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.fixtures.FarmAsyncHarness
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class FarmFieldRecoveryAsyncTest : FunSpec({
    for (plotCount in listOf(3_840, 6_144)) {
        test("mechanized $plotCount bed release uses the shared budget and records production cadence") {
            FarmAsyncHarness("SeederRelease-$plotCount").use { h ->
                val runtime = recoveryFixture(h, plotCount)
                val ledger = FarmBlockLedger(h.plugin)
                val pending = FarmFieldRecovery(ledger, h.tasks).process(runtime, plotCount, restoring = false)
                val deadline = System.nanoTime() + 30_000_000_000L
                while (!pending.isDone) {
                    check(System.nanoTime() < deadline) { "Seeder release exceeded 30 seconds" }
                    val started = System.nanoTime()
                    h.tick()
                    val waitMillis = (50 - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
                    if (waitMillis > 0) Thread.sleep(waitMillis)
                }
                pending.join() shouldBe FarmPatchReleaseResult(plotCount, true)
                runtime.state.preparationPatch.all { position ->
                    val soil = runtime.region.world.getBlockAt(position.x, position.y, position.z)
                    soil.type == Material.DIRT && ledger.record(soil)?.activeCropData != null
                } shouldBe true
                (h.maximumTickSteps <= 512) shouldBe true
                println("SEEDER_RELEASE beds=$plotCount ticks=${h.ticks} maxNativeSteps=${h.maximumTickSteps} " +
                    "nativeSteps=${h.totalNativeSteps} nativeStepMs=${h.nativeStepNanos / 1_000_000} " +
                    "idleTicks=${h.ticks - h.ticksWithNativeWork} workerCalls=${h.workerCalls} tickCadenceMs=50")
            }
        }
    }

    test("301 mixed crops including an odd chunk tail are journaled before release and restored exactly before journal retirement") {
        FarmAsyncHarness("FieldRecovery").use { h ->
            val runtime = recoveryFixture(h, 301)
            val world = runtime.region.world
            val patch = runtime.state.preparationPatch
            val original = patch.associateWith { position ->
                val soil = world.getBlockAt(position.x, position.y, position.z)
                soil.blockData.asString to soil.getRelative(BlockFace.UP).blockData.asString
            }
            val ledger = FarmBlockLedger(h.plugin)
            val recovery = FarmFieldRecovery(ledger, h.tasks)
            val release = recovery.process(runtime, 1_000, restoring = false)
            recovery.process(runtime, 1_000, restoring = false) shouldBe release
            h.until(before = {
                for (position in patch) {
                    val soil = world.getBlockAt(position.x, position.y, position.z)
                    if (soil.type == Material.DIRT) {
                        val record = requireNotNull(ledger.record(soil)) { "Mutation preceded its journal" }
                        record.originalSoilData shouldBe original.getValue(position).first
                        record.activeCropData shouldBe original.getValue(position).second
                    }
                }
            }) { release.isDone }
            release.join() shouldBe FarmPatchReleaseResult(301, true)
            patch.all { world.getBlockAt(it.x, it.y, it.z).type == Material.DIRT } shouldBe true
            runtime.state = runtime.state.copy(phase = FarmPhase.COOLDOWN, preparationReleased = true)
            val restore = recovery.process(runtime, 1_000, restoring = true)
            h.until { restore.isDone }
            restore.join() shouldBe FarmPatchReleaseResult(301, true)
            for ((position, expected) in original) {
                val soil = world.getBlockAt(position.x, position.y, position.z)
                (soil.blockData.asString to soil.getRelative(BlockFace.UP).blockData.asString) shouldBe expected
                requireNotNull(ledger.record(soil)).activeCropData shouldBe expected.second
            }
            // Restoration alone deliberately leaves the journal for the state writer's ACK.
            world.loadedChunks.sumOf { ledger.blockRecords(it).size } shouldBe 301
            val retire = recovery.clearTransient(runtime, patch, runtime.state.sequence)
            h.until { retire.isDone }
            retire.join()
            world.loadedChunks.sumOf { ledger.blockRecords(it).size } shouldBe 0
            (h.ticks > 1) shouldBe true
            (h.workerCalls.get() > 1) shouldBe true
            (h.maximumTickSteps <= 512) shouldBe true
        }
    }

    test("retired generation rejects delayed preparation without mutating the world") {
        FarmAsyncHarness("FieldRecoveryCancel").use { h ->
            val runtime = recoveryFixture(h)
            val ledger = FarmBlockLedger(h.plugin)
            val recovery = FarmFieldRecovery(ledger, h.tasks)
            h.gate = CompletableFuture()
            val pending = recovery.process(runtime, 1_000, restoring = false)
            repeat(10) { h.tick() }
            recovery.clear()
            pending.isCompletedExceptionally shouldBe true
            h.gate!!.complete(Unit)
            repeat(30) { h.tick() }
            runtime.state.preparationPatch.all {
                runtime.region.world.getBlockAt(it.x, it.y, it.z).type == Material.FARMLAND
            } shouldBe true
            runtime.region.world.loadedChunks.sumOf { ledger.blockRecords(it).size } shouldBe 0
        }
    }

    test("restarting a partly released mechanized field preserves its journaled active crops") {
        FarmAsyncHarness("FieldRecoveryRestart").use { h ->
            val runtime = recoveryFixture(h)
            val ledger = FarmBlockLedger(h.plugin)
            val soils = runtime.state.preparationPatch.map { position ->
                runtime.region.world.getBlockAt(position.x, position.y, position.z)
            }
            ledger.captureActiveCrops(soils, runtime.settings.id)
            val cropsBefore = soils.map { it.getRelative(BlockFace.UP).blockData.asString }
            // A restart retains the journal and world mutations, but retires in-memory release progress.
            soils.take(150).forEach { soil ->
                soil.getRelative(BlockFace.UP).setType(Material.AIR, false)
                soil.setType(Material.DIRT, false)
            }

            val retry = FarmFieldRecovery(ledger, h.tasks).process(runtime, 300, restoring = false)
            h.until { retry.isDone }

            retry.join() shouldBe FarmPatchReleaseResult(300, true)
            soils.map { ledger.record(it)?.activeCropData } shouldBe cropsBefore
            soils.all { it.type == Material.DIRT && it.getRelative(BlockFace.UP).type.isAir } shouldBe true
        }
    }
})

private fun recoveryFixture(h: FarmAsyncHarness, plotCount: Int = 300): FarmRuntime {
    val world = h.paper.server.addSimpleWorld("field")
    for (x in 0..3) for (z in 0..5) world.getChunkAt(x, z).load()
    val patch = (0 until plotCount).map { index ->
        val soil = if (plotCount == 300) world.getBlockAt(index / 16, 64, index % 16)
            else world.getBlockAt(index % 64, 64, index / 64)
        soil.type = Material.FARMLAND
        soil.getRelative(BlockFace.UP).type = if (index % 2 == 0) Material.WHEAT else Material.CARROTS
        FarmPlotPosition(world.name, soil.x, soil.y, soil.z)
    }
    val configRoot = h.plugin.dataFolder.toPath()
    Files.createDirectories(configRoot)
    requireNotNull(FarmFieldRecoveryAsyncTest::class.java.getResourceAsStream("/config.yml"))
        .use { Files.copy(it, configRoot.resolve("config.yml")) }
    val zone = ArcFarmsConfig.inspect(configRoot).farms.first().copy(id = "farm", crops = setOf("WHEAT", "CARROTS"))
    return FarmRuntime(zone, CuboidActivityRegion(world, "farm", CuboidBounds(0, 60, 0, 63, 70, 95)),
        emptyMap(), emptyList(), mockk(relaxed = true),
        FarmShiftState(phase = FarmPhase.PREPARATION, sequence = 4, preparationPatch = patch, mechanizedPreparation = true))
}

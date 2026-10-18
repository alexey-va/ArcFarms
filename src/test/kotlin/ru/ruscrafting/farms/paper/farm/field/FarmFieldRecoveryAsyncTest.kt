package ru.ruscrafting.farms.paper.farm.field

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.fixtures.FarmAsyncHarness
import java.util.concurrent.CompletableFuture

class FarmFieldRecoveryAsyncTest : FunSpec({
    test("300 mixed crops are journaled before release and restored exactly before journal retirement") {
        FarmAsyncHarness("FieldRecovery").use { h ->
            val runtime = recoveryFixture(h)
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
            release.join() shouldBe FarmPatchReleaseResult(300, true)
            patch.all { world.getBlockAt(it.x, it.y, it.z).type == Material.DIRT } shouldBe true
            runtime.state = runtime.state.copy(phase = FarmPhase.COOLDOWN, preparationReleased = true)
            val restore = recovery.process(runtime, 1_000, restoring = true)
            h.until { restore.isDone }
            restore.join() shouldBe FarmPatchReleaseResult(300, true)
            for ((position, expected) in original) {
                val soil = world.getBlockAt(position.x, position.y, position.z)
                (soil.blockData.asString to soil.getRelative(BlockFace.UP).blockData.asString) shouldBe expected
                requireNotNull(ledger.record(soil)).activeCropData shouldBe expected.second
            }
            // Restoration alone deliberately leaves the journal for the state writer's ACK.
            world.loadedChunks.sumOf { ledger.blockRecords(it).size } shouldBe 300
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
})

private fun recoveryFixture(h: FarmAsyncHarness): FarmRuntime {
    val world = h.paper.server.addSimpleWorld("field")
    for (x in 0..1) world.getChunkAt(x, 0).load()
    val patch = (0 until 300).map { index ->
        val soil = world.getBlockAt(index / 16, 64, index % 16)
        soil.type = Material.FARMLAND
        soil.getRelative(BlockFace.UP).type = if (index % 2 == 0) Material.WHEAT else Material.CARROTS
        FarmPlotPosition(world.name, soil.x, soil.y, soil.z)
    }
    val zone = mockk<FarmZoneSettings>(relaxed = true) {
        every { id } returns "farm"
        every { crops } returns setOf("WHEAT", "CARROTS")
    }
    return FarmRuntime(zone, CuboidActivityRegion(world, "farm", CuboidBounds(0, 60, 0, 31, 70, 15)),
        emptyMap(), emptyList(), mockk(relaxed = true),
        FarmShiftState(phase = FarmPhase.PREPARATION, sequence = 4, preparationPatch = patch, mechanizedPreparation = true))
}

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

class FarmPatchAutoFinisherAsyncTest : FunSpec({
    for (action in listOf("till", "plant", "recorded")) {
        test("$action auto finish preserves all 300 native and journal results across ticks") {
            FarmAsyncHarness("AutoFinish-$action").use { h ->
                val expected = autoFinishFixture(h, "expected", action)
                val actual = autoFinishFixture(h, "actual", action)
                val legacy = FarmPatchAutoFinisher(expected.ledger, mockk(relaxed = true), h.tasks)
                val expectedCount = legacy.process(expected.runtime, 500)
                val owner = FarmPatchAutoFinisher(actual.ledger, mockk(relaxed = true), h.tasks)
                val pending = owner.processAsync(actual.runtime, 500)
                owner.processAsync(actual.runtime, 500) shouldBe pending
                h.until(before = {
                    for (position in actual.runtime.state.preparationPatch) {
                        val soil = actual.runtime.region.world.getBlockAt(position.x, position.y, position.z)
                        if (soil.type == Material.FARMLAND) {
                            requireNotNull(actual.ledger.record(soil)) { "Native auto-finish preceded its recovery journal" }
                        }
                    }
                }) { pending.isDone }
                pending.join() shouldBe expectedCount
                snapshotAutoFinish(actual) shouldBe snapshotAutoFinish(expected)
                owner.hasPending(actual.runtime) shouldBe false
                (h.ticks > 1) shouldBe true
                (h.maximumTickSteps <= 128) shouldBe true
                (h.workerCalls.get() > 1) shouldBe true
            }
        }
    }

    test("a quota keeps original patch order when beds alternate between chunks") {
        FarmAsyncHarness("AutoFinishOrder").use { h ->
            val expected = autoFinishFixture(h, "expected", "till", interleaved = true)
            val actual = autoFinishFixture(h, "actual", "till", interleaved = true)
            FarmPatchAutoFinisher(expected.ledger, mockk(relaxed = true), h.tasks).process(expected.runtime, 17) shouldBe 17
            val pending = FarmPatchAutoFinisher(actual.ledger, mockk(relaxed = true), h.tasks).processAsync(actual.runtime, 17)
            h.until { pending.isDone }
            pending.join() shouldBe 17
            snapshotAutoFinish(actual) shouldBe snapshotAutoFinish(expected)
        }
    }

    test("a changed shift cancels delayed auto finish before native mutation") {
        FarmAsyncHarness("AutoFinishCancel").use { h ->
            val fixture = autoFinishFixture(h, "actual", "till")
            val before = snapshotAutoFinish(fixture)
            h.gate = CompletableFuture()
            val owner = FarmPatchAutoFinisher(fixture.ledger, mockk(relaxed = true), h.tasks)
            val pending = owner.processAsync(fixture.runtime, 500)
            fixture.runtime.state = fixture.runtime.state.copy(sequence = 5)
            h.gate!!.complete(Unit)
            h.until { pending.isDone }
            pending.isCompletedExceptionally shouldBe true
            snapshotAutoFinish(fixture) shouldBe before
            owner.isPending("farm") shouldBe false
        }
    }
})

private data class AutoFinishFixture(val runtime: FarmRuntime, val ledger: FarmBlockLedger)

private fun autoFinishFixture(h: FarmAsyncHarness, name: String, action: String, interleaved: Boolean = false): AutoFinishFixture {
    val world = h.paper.server.addSimpleWorld(name)
    for (x in 0..1) world.getChunkAt(x, 0).load()
    val positions = (0 until 300).map { index ->
        val x = if (interleaved) (index % 2) * 16 + (index / 2) / 16 else index / 16
        val z = if (interleaved) (index / 2) % 16 else index % 16
        FarmPlotPosition(world.name, x, 64, z)
    }
    val ledger = FarmBlockLedger(h.plugin)
    val soils = positions.mapIndexed { index, position -> world.getBlockAt(position.x, position.y, position.z).also {
        it.type = Material.FARMLAND
        it.getRelative(BlockFace.UP).type = if (index % 2 == 0) Material.WHEAT else Material.CARROTS
    } }
    if (action == "recorded") ledger.captureAll(soils, "farm")
    soils.forEach { soil -> soil.type = Material.DIRT; soil.getRelative(BlockFace.UP).type = Material.AIR }
    val settings = mockk<FarmZoneSettings>(relaxed = true) {
        every { id } returns "farm"
        every { crops } returns setOf("WHEAT", "CARROTS")
    }
    val state = FarmShiftState(phase = if (action == "till") FarmPhase.PLANTING else FarmPhase.HARVESTING,
        sequence = 4, preparationReleased = true, preparationPatch = positions, preparationCrop = "WHEAT",
        plantedPlots = if (action == "till") emptySet() else positions.toSet(), mechanizedPreparation = action == "recorded")
    return AutoFinishFixture(FarmRuntime(settings,
        CuboidActivityRegion(world, "farm", CuboidBounds(0, 60, 0, 31, 70, 15)),
        emptyMap(), emptyList(), mockk(relaxed = true), state), ledger)
}

private fun snapshotAutoFinish(fixture: AutoFinishFixture) = fixture.runtime.state.preparationPatch.map { position ->
    val soil = fixture.runtime.region.world.getBlockAt(position.x, position.y, position.z)
    Triple(soil.blockData.asString, soil.getRelative(BlockFace.UP).blockData.asString, fixture.ledger.record(soil))
}

package ru.ruscrafting.farms.paper.farm.field

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.fixtures.FarmAsyncHarness
import java.util.concurrent.CompletableFuture

class FarmBedDiscoveryAsyncTest : FunSpec({
    test("indexed native validation and worker components select the exact legacy patch over multiple ticks") {
        FarmAsyncHarness("FarmPatchAsync").use { h ->
            val fixture = patchFixture(h)
            val legacy = FarmBedDiscovery(fixture.debug, fixture.registry, fixture.points)
            val async = FarmBedDiscovery(fixture.debug, fixture.registry, fixture.points, h.tasks)
            for (mechanized in listOf(false, true)) {
                val expected = legacy.selectPatch(fixture.runtime, fixture.anchor, mechanized, 3)
                val startedAt = h.ticks
                val result = async.selectPatchAsync(fixture.runtime, fixture.anchor, mechanized, 3)
                result.isDone shouldBe false
                h.until { result.isDone }
                result.join() shouldBe expected
                (h.ticks - startedAt > 1) shouldBe true
            }
            (h.workerCalls.get() >= 4) shouldBe true
            (h.maximumTickSteps <= 512) shouldBe true
            fixture.registry.close()
        }
    }

    test("fallback keeps the same spiral scan and supplements the existing index") {
        FarmAsyncHarness("FarmPatchLocal").use { h ->
            val fixture = patchFixture(h, indexed = false)
            val oldRegistry = FarmBlockRegistry(h.plugin, FarmBlockLedger(h.plugin))
            val distant = FarmPlotPosition(fixture.anchor.world.name, 31, 64, 31)
            oldRegistry.addBeds("farm", listOf(distant))
            fixture.registry.addBeds("farm", listOf(distant))
            val expected = FarmBedDiscovery(fixture.debug, oldRegistry, fixture.points)
                .selectPatch(fixture.runtime, fixture.anchor, false, 2)
            val result = FarmBedDiscovery(fixture.debug, fixture.registry, fixture.points, h.tasks)
                .selectPatchAsync(fixture.runtime, fixture.anchor, false, 2)
            h.until { result.isDone }
            result.join() shouldBe expected
            fixture.registry.beds("farm") shouldBe oldRegistry.beds("farm")
            (distant in fixture.registry.beds("farm")) shouldBe true
            (h.maximumTickSteps <= 512) shouldBe true
            fixture.registry.close()
            oldRegistry.close()
        }
    }

    test("a changed shift cancels delayed worker planning before returning a patch") {
        FarmAsyncHarness("FarmPatchCancel").use { h ->
            val fixture = patchFixture(h)
            h.gate = CompletableFuture()
            val result = FarmBedDiscovery(fixture.debug, fixture.registry, fixture.points, h.tasks)
                .selectPatchAsync(fixture.runtime, fixture.anchor, false, 0)
            repeat(10) { h.tick() }
            result.isDone shouldBe false
            fixture.runtime.state = fixture.runtime.state.copy(sequence = 7)
            h.gate!!.complete(Unit)
            h.until { result.isDone }
            result.isCompletedExceptionally shouldBe true
            fixture.registry.beds("farm").size shouldBe 1024
            fixture.registry.close()
        }
    }
})

private data class PatchFixture(
    val runtime: FarmRuntime, val anchor: Location, val registry: FarmBlockRegistry,
    val points: FarmPointProvider, val debug: ArcFarmsDebug,
)

private fun patchFixture(h: FarmAsyncHarness, indexed: Boolean = true): PatchFixture {
    val world = h.paper.server.addSimpleWorld("patch")
    for (x in 0..1) for (z in 0..1) world.getChunkAt(x, z).load()
    val plots = (0..31).flatMap { x -> (0..31).map { z ->
        world.getBlockAt(x, 64, z).type = Material.FARMLAND
        world.getBlockAt(x, 65, z).type = Material.WHEAT
        FarmPlotPosition(world.name, x, 64, z)
    } }
    val zone = mockk<FarmZoneSettings>(relaxed = true) {
        every { id } returns "farm"
        every { crops } returns setOf("WHEAT")
        every { preparationPatchSize } returns 256
        every { preparationPatchMaxSize } returns 384
        every { seederPatchSize } returns 512
        every { seederPatchMaxSize } returns 768
        every { seederComponentGap } returns 1
        every { seederComponentLimit } returns 4
        every { preparationSearchRadius } returns 24
    }
    val runtime = FarmRuntime(zone, CuboidActivityRegion(world, "farm", CuboidBounds(0, 60, 0, 31, 70, 31)),
        emptyMap(), emptyList(), mockk(relaxed = true), FarmShiftState())
    val registry = FarmBlockRegistry(h.plugin, FarmBlockLedger(h.plugin), tasks = h.tasks)
    if (indexed) registry.addBeds("farm", plots)
    val points = FarmPointProvider { _, _ -> FarmPointPosition(world.name, 100.0, 64.0, 100.0) }
    return PatchFixture(runtime, Location(world, 16.0, 65.0, 16.0), registry, points, ArcFarmsDebug({ false }) {})
}

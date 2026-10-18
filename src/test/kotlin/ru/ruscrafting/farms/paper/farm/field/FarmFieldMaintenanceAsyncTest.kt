package ru.ruscrafting.farms.paper.farm.field

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.fixtures.FarmAsyncHarness
import java.util.concurrent.CompletableFuture

class FarmFieldMaintenanceAsyncTest : FunSpec({
    test("400 indexed beds retain exact native hydration crop repair and invalid bed removal") {
        FarmAsyncHarness("FieldMaintenance").use { h ->
            val fixture = maintenanceFixture(h)
            val world = fixture.runtime.region.world
            val legacyIndex = FarmBlockRegistry(h.plugin, fixture.ledger)
            legacyIndex.addBeds("farm", fixture.positions)
            fun snapshot() = fixture.positions.associateWith { position -> world.getBlockAt(position.x, position.y, position.z).let {
                it.blockData.asString to it.getRelative(BlockFace.UP).blockData.asString
            } }
            val before = snapshot()
            FarmFieldMaintenance(fixture.ledger, legacyIndex, h.tasks, mockk(relaxed = true), mockk(relaxed = true))
                .maintain(fixture.runtime, false, emptySet())
            val expected = snapshot()
            val expectedIndex = legacyIndex.beds("farm")
            for ((position, data) in before) world.getBlockAt(position.x, position.y, position.z).let {
                it.setBlockData(Bukkit.createBlockData(data.first), false)
                it.getRelative(BlockFace.UP).setBlockData(Bukkit.createBlockData(data.second), false)
            }
            val ledger = FarmBlockLedger(h.plugin)
            val index = FarmBlockRegistry(h.plugin, ledger, tasks = h.tasks)
            index.addBeds("farm", fixture.positions)
            val owner = FarmFieldMaintenance(ledger, index, h.tasks, mockk(relaxed = true), mockk(relaxed = true))
            val result = owner.maintainAsync(fixture.runtime, false, emptySet()) { true }
            owner.maintainAsync(fixture.runtime, false, emptySet()) { true } shouldBe result
            h.until { result.isDone }
            result.join()
            snapshot() shouldBe expected
            index.beds("farm") shouldBe expectedIndex
            (h.ticks > 1) shouldBe true
            (h.workerCalls.get() > 1) shouldBe true
            (h.maximumTickSteps <= 512) shouldBe true
            index.close(); legacyIndex.close()
        }
    }

    test("a new shift cancels delayed maintenance before it can repair stale positions") {
        FarmAsyncHarness("MaintenanceCancel").use { h ->
            val fixture = maintenanceFixture(h)
            val index = FarmBlockRegistry(h.plugin, fixture.ledger, tasks = h.tasks)
            index.addBeds("farm", fixture.positions)
            val owner = FarmFieldMaintenance(fixture.ledger, index, h.tasks, mockk(relaxed = true), mockk(relaxed = true))
            h.gate = CompletableFuture()
            val result = owner.maintainAsync(fixture.runtime, false, emptySet()) { true }
            fixture.runtime.state = fixture.runtime.state.copy(sequence = 9)
            h.gate!!.complete(Unit)
            h.until { result.isDone }
            result.isCompletedExceptionally shouldBe true
            fixture.runtime.region.world.getBlockAt(0, 65, 0).type shouldBe Material.AIR
            index.close()
        }
    }

    test("300 expired terrain effects restore their exact active crops without an extra hydration pass") {
        FarmAsyncHarness("MaintenanceExpiry").use { h ->
            val fixture = maintenanceFixture(h)
            val world = fixture.runtime.region.world
            val affected = fixture.positions.take(300).map { world.getBlockAt(it.x, it.y, it.z) }
            fixture.ledger.restoreOriginals(affected, clear = false)
            val expected = affected.map { it.blockData.asString to it.getRelative(BlockFace.UP).blockData.asString }
            fixture.ledger.beginTemporaryRemoval(affected, "farm", "test:terrain", 0L)
            affected.forEach { it.type = Material.AIR; it.getRelative(BlockFace.UP).type = Material.AIR }
            val registry = FarmBlockRegistry(h.plugin, fixture.ledger, tasks = h.tasks)
            registry.addBeds("farm", fixture.positions)
            val owner = FarmFieldMaintenance(fixture.ledger, registry, h.tasks, mockk(relaxed = true), mockk(relaxed = true))
            val pending = owner.maintainAsync(fixture.runtime, false, emptySet()) { true }
            h.until { pending.isDone }
            pending.join()
            affected.map { it.blockData.asString to it.getRelative(BlockFace.UP).blockData.asString } shouldBe expected
            affected.all { fixture.ledger.record(it)?.temporaryMutation == null } shouldBe true
            affected.all { fixture.ledger.record(it)?.indexed == true } shouldBe true
            (h.maximumTickSteps <= 512) shouldBe true
            registry.close()
        }
    }
})

private data class MaintenanceFixture(val runtime: FarmRuntime, val positions: List<FarmPlotPosition>, val ledger: FarmBlockLedger)

private fun maintenanceFixture(h: FarmAsyncHarness): MaintenanceFixture {
    val world = h.paper.server.addSimpleWorld("maintenance")
    for (x in 0..1) world.getChunkAt(x, 0).load()
    val soils = (0 until 400).map { i -> world.getBlockAt(i / 16, 64, i % 16).also {
        it.type = Material.FARMLAND
        it.getRelative(BlockFace.UP).type = if (i % 2 == 0) Material.WHEAT else Material.CARROTS
    } }
    val ledger = FarmBlockLedger(h.plugin)
    soils.groupBy { it.chunk }.forEach { (chunk, group) -> ledger.replaceZoneIndex(chunk, "farm", group, emptyList(), emptyList()) }
    soils.forEachIndexed { index, soil ->
        soil.type = Material.DIRT
        soil.getRelative(BlockFace.UP).type = if (index > 0 && index % 101 == 0) Material.STONE else Material.AIR
    }
    val zone = mockk<FarmZoneSettings>(relaxed = true) {
        every { id } returns "farm"
        every { crops } returns setOf("WHEAT", "CARROTS")
    }
    val runtime = FarmRuntime(zone, CuboidActivityRegion(world, "farm", CuboidBounds(0, 60, 0, 31, 70, 15)),
        emptyMap(), emptyList(), mockk(relaxed = true), FarmShiftState(phase = FarmPhase.HARVESTING, sequence = 3))
    return MaintenanceFixture(runtime, soils.map { FarmPlotPosition(world.name, it.x, it.y, it.z) }, ledger)
}

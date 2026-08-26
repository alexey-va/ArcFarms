package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.FarmPlotPosition

class FarmMachineBlockProcessorMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("machine swaths capture and update a bounded block batch") {
        val plots = (0 until 8).map { x ->
            world.getBlockAt(x, 64, 0).type = Material.DIRT
            FarmPlotPosition(world.name, x, 64, 0)
        }
        val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmMachineBatchTest"))
        val processor = FarmMachineBlockProcessor(ledger)

        processor.till("farm", plots, 5).processed.size shouldBe 5
        plots.count { world.getBlockAt(it.x, it.y, it.z).type == Material.FARMLAND } shouldBe 5
        ledger.blockRecords(world.getChunkAt(0, 0)).size shouldBe 5

        processor.plant("farm", Material.WHEAT, plots.take(5), 5).processed.size shouldBe 5
        plots.take(5).all { world.getBlockAt(it.x, it.y + 1, it.z).type == Material.WHEAT } shouldBe true
        ledger.blockRecords(world.getChunkAt(0, 0)).all {
            it.activeCropData?.startsWith("minecraft:wheat") == true
        } shouldBe true
    }
})

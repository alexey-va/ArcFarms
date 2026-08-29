package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.data.Ageable
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

        plots.take(5).forEachIndexed { index, plot ->
            val soil = world.getBlockAt(plot.x, plot.y, plot.z)
            soil.getRelative(org.bukkit.block.BlockFace.UP).type = if (index % 2 == 0) Material.WHEAT else Material.CARROTS
            ledger.captureActiveCrop(soil, "farm")
            soil.getRelative(org.bukkit.block.BlockFace.UP).type = Material.AIR
        }

        processor.restoreRecordedCrops("farm", plots.take(5), 5).processed.size shouldBe 5
        plots.take(5).map { world.getBlockAt(it.x, it.y + 1, it.z).type } shouldBe
            listOf(Material.WHEAT, Material.CARROTS, Material.WHEAT, Material.CARROTS, Material.WHEAT)
    }

    test("active crop snapshots are captured as a multi-chunk batch") {
        world.getChunkAt(1, 0).load()
        val soils = listOf(0, 1, 16, 17).map { x ->
            world.getBlockAt(x, 64, 0).also { soil ->
                soil.type = Material.FARMLAND
                soil.getRelative(org.bukkit.block.BlockFace.UP).type = Material.WHEAT
            }
        }
        val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmLedgerBatchTest"))

        ledger.captureActiveCrops(soils + soils, "farm")

        soils.all { soil ->
            ledger.record(soil)?.activeCropData?.startsWith("minecraft:wheat") == true
        } shouldBe true
        ledger.blockRecords(world.getChunkAt(0, 0)).size shouldBe 2
        ledger.blockRecords(world.getChunkAt(1, 0)).size shouldBe 2
    }

    test("seeder plants recorded crop types at age zero and remains retryable") {
        val crops = listOf(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.SWEET_BERRY_BUSH,
        )
        val plots = crops.mapIndexed { x, material ->
            val soil = world.getBlockAt(x, 64, 3).also { it.type = Material.FARMLAND }
            val mature = material.createBlockData() as Ageable
            mature.age = mature.maximumAge
            soil.getRelative(org.bukkit.block.BlockFace.UP).setBlockData(mature, false)
            FarmPlotPosition(world.name, x, 64, 3)
        }
        val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmSeededGrowthStageTest"))
        val processor = FarmMachineBlockProcessor(ledger)
        plots.forEach { plot ->
            val soil = world.getBlockAt(plot.x, plot.y, plot.z)
            ledger.captureActiveCrop(soil, "farm")
            soil.getRelative(org.bukkit.block.BlockFace.UP).type = Material.AIR
        }

        processor.restoreRecordedCrops("farm", plots, plots.size).processed shouldBe plots.toSet()
        plots.map { plot ->
            val planted = world.getBlockAt(plot.x, plot.y + 1, plot.z).blockData as Ageable
            planted.material to planted.age
        } shouldBe crops.map { it to 0 }

        processor.restoreRecordedCrops("farm", plots, plots.size).processed shouldBe plots.toSet()
        plots.map { plot ->
            (world.getBlockAt(plot.x, plot.y + 1, plot.z).blockData as Ageable).age
        } shouldBe List(crops.size) { 0 }
    }

    test("restoring an indexed bed also resets the maintained crop snapshot") {
        val soil = world.getBlockAt(4, 64, 4).also { block ->
            block.type = Material.FARMLAND
            block.getRelative(org.bukkit.block.BlockFace.UP).type = Material.BEETROOTS
        }
        val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmIndexedCropRestoreTest"))
        ledger.replaceZoneIndex(
            chunk = soil.chunk,
            zoneId = "farm",
            beds = listOf(soil),
            fixedCrops = emptyList(),
            orchardLeaves = emptyList(),
        )

        soil.getRelative(org.bukkit.block.BlockFace.UP).type = Material.WHEAT
        ledger.captureActiveCrop(soil, "farm")
        ledger.restoreOriginal(soil, clear = false) shouldBe true

        soil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.BEETROOTS
        ledger.record(soil)?.activeCropData?.startsWith("minecraft:beetroots") shouldBe true

        soil.getRelative(org.bukkit.block.BlockFace.UP).type = Material.AIR
        ledger.restoreActiveCrop(soil) shouldBe true
        soil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.BEETROOTS
    }

    test("indexed field restoration batches mixed crops per chunk") {
        val soils = (0 until 6).map { x ->
            world.getBlockAt(x, 64, 6).also { soil ->
                soil.type = Material.FARMLAND
                soil.getRelative(org.bukkit.block.BlockFace.UP).type =
                    if (x % 2 == 0) Material.WHEAT else Material.CARROTS
            }
        }
        val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmIndexedBatchRestoreTest"))
        ledger.replaceZoneIndex(
            chunk = world.getChunkAt(0, 0),
            zoneId = "farm",
            beds = soils,
            fixedCrops = emptyList(),
            orchardLeaves = emptyList(),
        )
        soils.forEach { soil ->
            soil.getRelative(org.bukkit.block.BlockFace.UP).type = Material.POTATOES
        }
        ledger.updateActiveCrops(soils)

        ledger.restoreOriginals(soils, clear = false).size shouldBe soils.size
        soils.map { it.getRelative(org.bukkit.block.BlockFace.UP).type } shouldBe
            listOf(Material.WHEAT, Material.CARROTS, Material.WHEAT, Material.CARROTS, Material.WHEAT, Material.CARROTS)
        soils.map { ledger.record(it)?.activeCropData?.substringBefore('[') } shouldBe
            listOf(
                "minecraft:wheat", "minecraft:carrots", "minecraft:wheat",
                "minecraft:carrots", "minecraft:wheat", "minecraft:carrots",
            )
    }
})

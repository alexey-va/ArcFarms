package ru.ruscrafting.farms.paper.farm.field

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.data.type.Farmland
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink

class FarmFieldControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("large field release respects its per-tick mutation budget") {
        val plots = (0 until 5).map { x ->
            world.getBlockAt(x, 64, 0).apply { type = Material.FARMLAND }
            world.getBlockAt(x, 65, 0).apply { type = Material.WHEAT }
            FarmPlotPosition(world.name, x, 64, 0)
        }
        val ledger = FarmBlockLedger(paper.createSimplePlugin("FarmFieldReleaseTest"))
        val runtime = FarmRuntime(
            settings = mockk<FarmZoneSettings>(relaxed = true) {
                every { id } returns "farm"
                every { crops } returns setOf("WHEAT")
            },
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(
                phase = FarmPhase.PREPARATION,
                preparationPatch = plots,
                preparationCrop = "WHEAT",
                preparationRequired = plots.size,
            ),
        )
        val controller = FarmFieldController(
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            debug = ArcFarmsDebug({ false }) {},
            port = mockk<WorksiteRuntimePort>(relaxed = true),
            ledger = ledger,
            registry = mockk<FarmBlockRegistry>(relaxed = true),
            points = FarmPointProvider { _, _ -> error("release does not resolve operation points") },
            transitions = FarmTransitionSink { _, _, _ -> },
            persistBlocking = {},
        )

        controller.release(runtime, 2) shouldBe FarmPatchReleaseResult(processed = 2, complete = false)
        plots.count { world.getBlockAt(it.x, it.y, it.z).type == Material.DIRT } shouldBe 2

        controller.release(runtime, 3) shouldBe FarmPatchReleaseResult(processed = 3, complete = true)
        ledger.blockRecords(world.getChunkAt(0, 0)).size shouldBe plots.size
        plots.all { world.getBlockAt(it.x, it.y, it.z).type == Material.DIRT } shouldBe true
        plots.all { world.getBlockAt(it.x, it.y + 1, it.z).type.isAir } shouldBe true

        runtime.state = runtime.state.copy(
            phase = FarmPhase.PLANTING,
            preparationReleased = true,
            tilledPlots = plots.toSet(),
            preparationProgress = plots.size,
        )
        controller.finishAutomaticQuota(runtime, 2) shouldBe 2
        plots.count { world.getBlockAt(it.x, it.y, it.z).type == Material.FARMLAND } shouldBe 2
        controller.finishAutomaticQuota(runtime, 3) shouldBe 3
        plots.all { world.getBlockAt(it.x, it.y, it.z).type == Material.FARMLAND } shouldBe true

        runtime.state = runtime.state.copy(
            phase = FarmPhase.CARE,
            careType = FarmCareType.DISEASE,
            plantedPlots = plots.toSet(),
            plantingProgress = plots.size,
        )
        controller.finishAutomaticQuota(runtime, 2) shouldBe 2
        plots.count { world.getBlockAt(it.x, it.y + 1, it.z).type == Material.WHEAT } shouldBe 2
        controller.finishAutomaticQuota(runtime, 3) shouldBe 3
        plots.all { world.getBlockAt(it.x, it.y + 1, it.z).type == Material.WHEAT } shouldBe true
        ledger.blockRecords(world.getChunkAt(0, 0)).all { it.activeCropData?.startsWith("minecraft:wheat") == true } shouldBe true
    }

    test("ordinary maintenance does not hydrate soil owned by an irrigation wave") {
        val position = FarmPlotPosition(world.name, 2, 64, 2)
        val soil = world.getBlockAt(position.x, position.y, position.z).apply {
            type = Material.FARMLAND
            blockData = (blockData as Farmland).also { it.moisture = 0 }
        }
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "farm"
            every { crops } returns setOf("WHEAT")
        }
        val runtime = FarmRuntime(
            settings = zone,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(
                phase = FarmPhase.CARE,
                careType = FarmCareType.IRRIGATION,
                preparationPatch = listOf(position),
                preparationReleased = true,
            ),
        )
        val registry = mockk<FarmBlockRegistry>(relaxed = true) {
            every { beds("farm") } returns setOf(position)
        }
        val controller = FarmFieldController(
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            debug = ArcFarmsDebug({ false }) {},
            port = mockk<WorksiteRuntimePort>(relaxed = true),
            ledger = FarmBlockLedger(paper.createSimplePlugin("FarmIrrigationMaintenanceTest")),
            registry = registry,
            points = FarmPointProvider { _, _ -> error("maintenance does not resolve operation points") },
            transitions = FarmTransitionSink { _, _, _ -> },
            persistBlocking = {},
        )

        controller.maintain(runtime, activeWater = false, irrigationDryPlots = setOf(position))
        (soil.blockData as Farmland).moisture shouldBe 0

        controller.maintain(runtime, activeWater = false)
        (soil.blockData as Farmland).moisture shouldBe (soil.blockData as Farmland).maximumMoisture
    }

    test("ordinary maintenance does not close a journalled mole entrance in a crop bed") {
        val position = FarmPlotPosition(world.name, 2, 64, 2)
        val soil = world.getBlockAt(position.x, position.y, position.z).apply { type = Material.BARRIER }
        val crop = world.getBlockAt(position.x, position.y + 1, position.z)
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "farm"
            every { crops } returns setOf("WHEAT")
        }
        val runtime = FarmRuntime(
            settings = zone,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(
                phase = FarmPhase.CARE,
                careType = FarmCareType.MOLES,
                careTargets = listOf(FarmCareTarget(
                    id = 0,
                    role = FarmCareRole.MOLE_MOUND,
                    position = FarmPointPosition(world.name, 2.5, 65.05, 2.5),
                )),
                preparationPatch = listOf(position),
                preparationReleased = true,
                tilledPlots = setOf(position),
                plantedPlots = setOf(position),
                preparationCrop = "WHEAT",
            ),
        )
        val registry = mockk<FarmBlockRegistry>(relaxed = true) {
            every { beds("farm") } returns setOf(position)
        }
        val controller = FarmFieldController(
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            debug = ArcFarmsDebug({ false }) {},
            port = mockk<WorksiteRuntimePort>(relaxed = true),
            ledger = FarmBlockLedger(paper.createSimplePlugin("FarmMoleEntranceMaintenanceTest")),
            registry = registry,
            points = FarmPointProvider { _, _ -> error("maintenance does not resolve operation points") },
            transitions = FarmTransitionSink { _, _, _ -> },
            persistBlocking = {},
        )

        controller.maintain(runtime, activeWater = false)

        soil.type shouldBe Material.BARRIER
        crop.type shouldBe Material.AIR
    }

    test("patch selection uses a sufficient durable index without a local world scan") {
        val indexed = (0 until 5).map { x ->
            world.getBlockAt(x, 64, 0).apply { type = Material.FARMLAND }
            world.getBlockAt(x, 65, 0).apply { type = Material.WHEAT }
            FarmPlotPosition(world.name, x, 64, 0)
        }.toSet()
        val settings = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "farm"
            every { crops } returns setOf("WHEAT")
            every { preparationPatchSize } returns 5
            every { preparationPatchMaxSize } returns 5
            every { preparationSearchRadius } returns 64
        }
        val runtime = FarmRuntime(
            settings = settings,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(),
        )
        val registry = mockk<FarmBlockRegistry>(relaxed = true) {
            every { beds("farm") } returns indexed
        }
        var pointResolutions = 0
        val discovery = FarmBedDiscovery(
            debug = ArcFarmsDebug({ false }) {},
            registry = registry,
            points = FarmPointProvider { _, _ ->
                pointResolutions++
                FarmPointPosition(world.name, 100.0, 64.0, 100.0)
            },
        )

        discovery.selectPatch(runtime, world.getBlockAt(0, 64, 0).location, false, 0) shouldBe indexed.toList()
        pointResolutions shouldBe 7
    }

    test("fallback bed discovery scans outward from the player and stops after enough nearby candidates") {
        val nearby = listOf(
            FarmPlotPosition(world.name, 4, 64, 4),
            FarmPlotPosition(world.name, 5, 64, 4),
        )
        (nearby + FarmPlotPosition(world.name, 30, 64, 30)).forEach { plot ->
            world.getBlockAt(plot.x, plot.y, plot.z).type = Material.FARMLAND
            world.getBlockAt(plot.x, plot.y + 1, plot.z).type = Material.WHEAT
        }
        val settings = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "farm"
            every { crops } returns setOf("WHEAT")
            every { preparationPatchSize } returns 2
            every { preparationPatchMaxSize } returns 2
            every { preparationSearchRadius } returns 64
        }
        val runtime = FarmRuntime(
            settings = settings,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 63, 128, 63)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(),
        )
        val debugLines = mutableListOf<String>()
        val registry = mockk<FarmBlockRegistry>(relaxed = true) {
            every { beds("farm") } returns emptySet()
        }
        val discovery = FarmBedDiscovery(
            debug = ArcFarmsDebug({ true }, debugLines::add),
            registry = registry,
            points = FarmPointProvider { _, _ -> FarmPointPosition(world.name, 100.0, 64.0, 100.0) },
        )

        discovery.selectPatch(runtime, world.getBlockAt(4, 64, 4).location, false, 0) shouldBe nearby
        debugLines.single { "event=farm_beds_discovered" in it }.contains("scanned_blocks=32768") shouldBe false
    }

    test("patch selection excludes indexed farmland hidden below terrain") {
        val open = FarmPlotPosition(world.name, 1, 64, 1)
        val covered = FarmPlotPosition(world.name, 3, 64, 1)
        listOf(open, covered).forEach { plot ->
            world.getBlockAt(plot.x, plot.y, plot.z).type = Material.FARMLAND
            world.getBlockAt(plot.x, plot.y + 1, plot.z).type = Material.WHEAT
        }
        world.getBlockAt(covered.x, 70, covered.z).type = Material.STONE
        val settings = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "farm"
            every { crops } returns setOf("WHEAT")
            every { preparationPatchSize } returns 1
            every { preparationPatchMaxSize } returns 1
            every { preparationSearchRadius } returns 8
        }
        val runtime = FarmRuntime(
            settings = settings,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(),
        )
        val discovery = FarmBedDiscovery(
            debug = ArcFarmsDebug({ false }) {},
            registry = mockk<FarmBlockRegistry>(relaxed = true) {
                every { beds("farm") } returns setOf(covered, open)
            },
            points = FarmPointProvider { _, _ -> FarmPointPosition(world.name, 100.0, 64.0, 100.0) },
        )

        discovery.selectPatch(runtime, world.getBlockAt(3, 64, 1).location, false, 0) shouldBe listOf(open)
    }
})

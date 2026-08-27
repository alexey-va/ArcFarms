package ru.ruscrafting.farms.paper.farm.care.irrigation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.data.type.Farmland
import org.bukkit.event.block.MoistureChangeEvent
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmIrrigationSettings
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
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink

class FarmIrrigationControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("dries with a budget and completes each valve only after its radial wave") {
        val plots = (0 until 6).map { x ->
            world.getBlockAt(x, 64, 0).apply {
                type = Material.FARMLAND
                blockData = (blockData as Farmland).also { it.moisture = it.maximumMoisture }
            }
            FarmPlotPosition(world.name, x, 64, 0)
        }
        val targets = listOf(
            FarmCareTarget(0, FarmCareRole.VALVE, FarmPointPosition(world.name, 0.5, 65.0, 0.5)),
            FarmCareTarget(1, FarmCareRole.VALVE, FarmPointPosition(world.name, 5.5, 65.0, 0.5)),
        )
        val irrigationSettings = FarmIrrigationSettings(
            dryBlocksPerTick = 2,
            waveBlocksPerTick = 1,
            waveStartDelayTicks = 0,
            ringIntervalTicks = 1,
            ringWidth = 1.0,
            particleSpacing = 1.0,
            particleHeight = 2.0,
            particleSpread = 0.4,
            particleCount = 3,
        )
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "farm"
            every { irrigation } returns irrigationSettings
        }
        val runtime = FarmRuntime(
            settings = zone,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(
                sequence = 7,
                phase = FarmPhase.CARE,
                careType = FarmCareType.IRRIGATION,
                preparationPatch = plots,
                careTargets = targets,
                careGoal = targets.size,
            ),
        )
        val controller = FarmIrrigationController(
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            debug = ArcFarmsDebug({ false }) {},
            port = mockk<WorksiteRuntimePort>(relaxed = true),
            transitions = FarmTransitionSink { targetRuntime, result, _ -> targetRuntime.state = result.state },
        )
        val player = paper.server.addPlayer()

        controller.process(listOf(runtime))
        plots.count { moisture(world, it) == 0 } shouldBe 2
        controller.process(listOf(runtime))
        controller.process(listOf(runtime))
        plots.all { moisture(world, it) == 0 } shouldBe true

        controller.start(runtime, targets[0], player) shouldBe true
        repeat(12) { controller.process(listOf(runtime)) }
        runtime.state.careTargets.first { it.id == 0 }.complete shouldBe true
        plots.take(3).all { moisture(world, it) == 7 } shouldBe true
        plots.takeLast(3).all { moisture(world, it) == 0 } shouldBe true

        controller.start(runtime, runtime.state.careTargets.first { it.id == 1 }, player) shouldBe true
        repeat(12) { controller.process(listOf(runtime)) }
        runtime.state.phase shouldBe FarmPhase.HARVESTING
        plots.all { moisture(world, it) == 7 } shouldBe true
    }

    test("valves can be activated in any order") {
        val plots = (0 until 6).map { x ->
            world.getBlockAt(x, 64, 0).type = Material.FARMLAND
            FarmPlotPosition(world.name, x, 64, 0)
        }
        val targets = listOf(
            FarmCareTarget(0, FarmCareRole.VALVE, FarmPointPosition(world.name, 0.5, 65.0, 0.5)),
            FarmCareTarget(1, FarmCareRole.VALVE, FarmPointPosition(world.name, 5.5, 65.0, 0.5)),
        )
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "farm"
            every { irrigation } returns FarmIrrigationSettings(24, 24, 0, 1, 1.0, 1.0, 2.0, 0.4, 3)
        }
        val runtime = FarmRuntime(
            zone,
            CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
            emptyMap(), emptyList(), mockk(relaxed = true),
            FarmShiftState(
                sequence = 8,
                phase = FarmPhase.CARE,
                careType = FarmCareType.IRRIGATION,
                preparationPatch = plots,
                careTargets = targets,
                careGoal = targets.size,
            ),
        )
        val controller = FarmIrrigationController(
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            debug = ArcFarmsDebug({ false }) {},
            port = mockk(relaxed = true),
            transitions = FarmTransitionSink { targetRuntime, result, _ -> targetRuntime.state = result.state },
        )
        val player = paper.server.addPlayer()

        controller.start(runtime, targets[1], player) shouldBe true
        controller.start(runtime, targets[0], player) shouldBe true
        repeat(12) { controller.process(listOf(runtime)) }

        runtime.state.phase shouldBe FarmPhase.HARVESTING
    }

    test("moisture events use the cached plot assignment and stop being cancelled after watering") {
        val plot = FarmPlotPosition(world.name, 2, 64, 2)
        val block = world.getBlockAt(plot.x, plot.y, plot.z).apply {
            type = Material.FARMLAND
            blockData = (blockData as Farmland).also { it.moisture = 0 }
        }
        val target = FarmCareTarget(
            4,
            FarmCareRole.VALVE,
            FarmPointPosition(world.name, 2.5, 65.0, 2.5),
        )
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "farm"
            every { irrigation } returns FarmIrrigationSettings(24, 24, 0, 1, 1.0, 1.0, 2.0, 0.4, 3)
        }
        val runtime = FarmRuntime(
            zone,
            CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
            emptyMap(), emptyList(), mockk(relaxed = true),
            FarmShiftState(
                sequence = 9,
                phase = FarmPhase.CARE,
                careType = FarmCareType.IRRIGATION,
                preparationPatch = listOf(plot),
                careTargets = listOf(target),
                careGoal = 1,
            ),
        )
        val controller = FarmIrrigationController(
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            debug = ArcFarmsDebug({ false }) {},
            port = mockk<WorksiteRuntimePort>(relaxed = true),
            transitions = FarmTransitionSink { targetRuntime, result, _ -> targetRuntime.state = result.state },
        )
        val drying = MoistureChangeEvent(
            block,
            block.state.apply {
                blockData = (block.blockData.clone() as Farmland).also { it.moisture = 0 }
            },
        )

        controller.onMoistureChange(drying, runtime) shouldBe true
        drying.isCancelled shouldBe true

        controller.start(runtime, target, paper.server.addPlayer()) shouldBe true
        repeat(4) { controller.process(listOf(runtime)) }
        val afterWave = MoistureChangeEvent(
            block,
            block.state.apply {
                blockData = (block.blockData.clone() as Farmland).also { it.moisture = 0 }
            },
        )

        controller.onMoistureChange(afterWave, runtime) shouldBe false
        afterWave.isCancelled shouldBe false
    }
})

private fun moisture(world: WorldMock, plot: FarmPlotPosition): Int =
    (world.getBlockAt(plot.x, plot.y, plot.z).blockData as Farmland).moisture

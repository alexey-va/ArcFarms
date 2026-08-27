package ru.ruscrafting.farms.paper.farm.care.irrigation

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
})

private fun moisture(world: WorldMock, plot: FarmPlotPosition): Int =
    (world.getBlockAt(plot.x, plot.y, plot.z).blockData as Farmland).moisture

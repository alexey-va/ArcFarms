package ru.ruscrafting.farms.paper.farm.care

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmDamageSafetySettings
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
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink

class FarmDiseaseControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("dead disease spots disappear and immediately jump to living neighbouring crops") {
        val player = paper.server.addPlayer("Farmer")
        val plots = (0 until 6).map { x ->
            world.getBlockAt(x, 64, 0).type = Material.FARMLAND
            world.getBlockAt(x, 65, 0).type = Material.WHEAT
            FarmPlotPosition(world.name, x, 64, 0)
        }
        val targets = plots.take(2).mapIndexed { id, plot ->
            FarmCareTarget(id, FarmCareRole.DISEASED_CROP, FarmPointPosition(world.name, plot.x + 0.5, 65.05, 0.5))
        }
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "farm"
            every { crops } returns setOf("WHEAT")
            every { diseaseSpreadSeconds } returns 3
            every { diseaseMaxSpots } returns 10
            every { diseaseSpreadRadius } returns 4.0
            every { diseaseKillSeconds } returns 1
            every { damageSafety } returns FarmDamageSafetySettings(
                maximumPercent = 100,
                minimumRemaining = 0,
                birdMaximum = 10,
                pestMaximum = 10,
                diseaseMaximum = 10,
            )
        }
        val runtime = FarmRuntime(
            zone,
            CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
            emptyMap(), emptyList(), mockk(relaxed = true),
            FarmShiftState(
                phase = FarmPhase.CARE,
                sequence = 9,
                careType = FarmCareType.DISEASE,
                preparationPatch = plots,
                careTargets = targets,
                careGoal = targets.size,
            ),
        )
        val config = mockk<ArcFarmsConfig>(relaxed = true)
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { players(runtime.region) } returns listOf(player)
        }
        val spawned = mutableListOf<Int>()
        val controller = FarmDiseaseController(
            settings = { config },
            debug = ArcFarmsDebug({ false }) {},
            audience = port,
            state = port,
            ledger = FarmBlockLedger(paper.createSimplePlugin("DiseaseLedgerTest")),
            transitions = FarmTransitionSink { targetRuntime, result, _ -> targetRuntime.state = result.state },
            targets = FarmCareTargetSpawner { _, target -> spawned += target.id },
        )

        controller.start(runtime, 0L)
        controller.update(runtime, 1_001L)

        runtime.state.phase shouldBe FarmPhase.CARE
        runtime.state.careTargets.none { it.id in setOf(0, 1) } shouldBe true
        runtime.state.careTargets.size shouldBe 2
        runtime.state.careGoal shouldBe 2
        runtime.state.diseaseDamagedCrops.orEmpty().size shouldBe 2
        spawned.size shouldBe 2
    }
})

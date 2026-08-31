package ru.ruscrafting.farms.paper.farm.incident.pest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmDamageSafetySettings
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPestNest
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import java.util.Random

class FarmPestCropDestructionMockBukkitIntegrationTest : FunSpec({
    lateinit var server: ServerMock
    lateinit var world: WorldMock
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: Plugin

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        plugin = paper.createSimplePlugin("FarmPestCropTest")
        world = server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach {
        paper.close()
    }

    test("pest pulse captures crops before AIR and respects cap, minimum, and duplicate gate") {
        val fixture = pestCropFixture(world, plugin, paper)
        fixture.controller.ensure(fixture.runtime)
        world.entities.count(fixture.controller::ownsPest) shouldBe 1

        fixture.controller.eatCrops(fixture.runtime)
        fixture.controller.eatCrops(fixture.runtime)
        fixture.pending shouldHaveSize 1

        fixture.pending.removeFirst().invoke()

        fixture.runtime.state.pestDamagedCrops shouldHaveSize 2
        fixture.cropBeds.take(2).forEach { soilPosition ->
            val soil = soilPosition.block()!!
            soil.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.AIR
            fixture.ledger.record(soil)?.activeCropData shouldNotBe null
            fixture.ledger.record(soil)?.activeCropData shouldContain "minecraft:wheat"
        }
        fixture.cropBeds.drop(2).forEach { soilPosition ->
            soilPosition.block()!!.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.WHEAT
        }
    }

    test("stale async pest crop plan is rejected without world or state mutation") {
        val fixture = pestCropFixture(world, plugin, paper)
        fixture.controller.ensure(fixture.runtime)
        fixture.controller.eatCrops(fixture.runtime)
        fixture.pending shouldHaveSize 1

        fixture.runtime.state = fixture.runtime.state.copy(sequence = fixture.runtime.state.sequence + 1)
        fixture.pending.removeFirst().invoke()

        fixture.runtime.state.pestDamagedCrops shouldHaveSize 0
        fixture.cropBeds.forEach { soilPosition ->
            soilPosition.block()!!.getRelative(org.bukkit.block.BlockFace.UP).type shouldBe Material.WHEAT
        }
    }
})

private data class PestCropFixture(
    val runtime: FarmRuntime,
    val controller: FarmPestIncident,
    val ledger: FarmBlockLedger,
    val cropBeds: List<FarmPlotPosition>,
    val pending: MutableList<() -> Unit>,
)

private fun pestCropFixture(world: WorldMock, plugin: Plugin, paper: MockBukkitTestRuntime): PestCropFixture {
    val cropBeds = (4..8).map { x -> FarmPlotPosition(world.name, x, 64, 2) }
    val nest = FarmPlotPosition(world.name, 2, 64, 2)
    (0..15).forEach { x ->
        (0..15).forEach { z -> world.getBlockAt(x, 64, z).type = Material.STONE }
    }
    world.getBlockAt(nest.x, nest.y, nest.z).type = Material.FARMLAND
    cropBeds.forEach { position ->
        world.getBlockAt(position.x, position.y, position.z).type = Material.FARMLAND
        world.getBlockAt(position.x, position.y + 1, position.z).type = Material.WHEAT
    }

    val player = paper.addPlayer("PestWorker")
    player.teleport(Location(world, 2.5, 65.0, 2.5))
    val config = mockk<ArcFarmsConfig>(relaxed = true) {
        every { particles } returns false
        every { sounds } returns false
    }
    val safety = FarmDamageSafetySettings(
        maximumPercent = 100,
        minimumRemaining = 3,
        birdMaximum = 10,
        pestMaximum = 10,
        diseaseMaximum = 10,
    )
    val settings = mockk<FarmZoneSettings>(relaxed = true) {
        every { id } returns "farm"
        every { permission } returns "arcfarms.farm"
        every { crops } returns setOf("WHEAT")
        every { pestEntity } returns "SILVERFISH"
        every { pestSpawnRadius } returns 0
        every { pestNestCount } returns 1
        every { pestNestHealth } returns 1
        every { pestSpawnsPerNest } returns 1
        every { pestMaxAlive } returns 1
        every { pestSpawnIntervalSeconds } returns 1
        every { pestSpawnChancePercent } returns 0
        every { pestEatRadius } returns 12
        every { pestEatPerPulse } returns 8
        every { damageSafety } returns safety
        every { displayViewRange } returns 1.0f
    }
    val runtime = FarmRuntime(
        settings = settings,
        region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
        orders = emptyMap(),
        orderList = emptyList(),
        rules = mockk(relaxed = true),
        state = FarmShiftState(
            phase = FarmPhase.INCIDENT,
            sequence = 7L,
            incidentType = FarmIncidentType.PESTS,
            pestNestsInitialized = true,
            pestNests = listOf(FarmPestNest(nest, health = 1)),
            pestAlive = 1,
        ),
    )
    val supervisor = RuntimeTaskSupervisor(TestTaskScheduler()).apply(RuntimeTaskSupervisor::activate)
    val token = supervisor.token()
    val pending = mutableListOf<() -> Unit>()
    val port = mockk<WorksiteRuntimePort>(relaxed = true) {
        every { isOperational() } returns true
        every { hasAccess(any(), any()) } returns true
        every { allowInteraction(any(), any()) } returns true
        every { lifecycleToken() } returns token
        every { players(any()) } returns listOf(player)
        every { runAsync(token, any()) } answers {
            pending += secondArg<() -> Unit>()
            true
        }
        every { runSync(token, any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
    }
    val locale = mockk<ArcFarmsLocale>(relaxed = true) {
        every { render(any(), any(), any()) } answers { Component.text(firstArg<MessageKey>().path) }
    }
    val ledger = FarmBlockLedger(plugin)
    val registry = FarmBlockRegistry(plugin, ledger)
    registry.addBeds(settings.id, cropBeds)
    val transitions = FarmTransitionSink { target, result, _ ->
        if (result.accepted) target.state = result.state
    }
    val controller = FarmPestIncident(
        plugin = plugin,
        settings = { config },
        locale = locale,
        debug = ArcFarmsDebug({ false }) {},
        access = port,
        audience = port,
        state = port,
        tasks = port,
        blockLedger = ledger,
        blockRegistry = registry,
        beds = FarmIncidentBedProvider { emptySet() },
        transitions = transitions,
        random = Random(1L),
    )
    return PestCropFixture(runtime, controller, ledger, cropBeds, pending)
}

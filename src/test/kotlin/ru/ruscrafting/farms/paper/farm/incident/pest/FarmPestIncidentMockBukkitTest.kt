package ru.ruscrafting.farms.paper.farm.incident.pest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.entity.ItemDisplay
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPestNest
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.CountingFarmEntityLookup
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.Random

class FarmPestIncidentMockBukkitTest : FunSpec({
    lateinit var server: ServerMock
    lateinit var world: WorldMock
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: Plugin

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        plugin = paper.createSimplePlugin("FarmPestTest")
        world = server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach {
        paper.close()
    }

    test("nest scene converges after controller restart without duplication") {
        val fixture = pestFixture(world, plugin)
        fixture.controller.ensure(fixture.runtime)
        world.entities.filter(fixture.controller::ownsNest) shouldHaveSize 4

        val restarted = fixture.newController()
        restarted.ensure(fixture.runtime)

        world.entities.filter(restarted::ownsNest) shouldHaveSize 4
        world.entities.filter(restarted::ownsNest).map { it.uniqueId }.distinct() shouldHaveSize 4
    }

    test("pest nests are twice normal size and visible across the full field") {
        val fixture = pestFixture(world, plugin)

        fixture.controller.ensure(fixture.runtime)

        val displays = world.entities.filterIsInstance<ItemDisplay>().filter(fixture.controller::ownsNest)
        displays shouldHaveSize 2
        displays.all { it.viewRange == 3.0f } shouldBe true
        displays.all { it.transformation.scale.x == 2.0f && it.transformation.scale.y == 2.0f && it.transformation.scale.z == 2.0f } shouldBe true
    }

    test("sequence change replaces stale nest entities instead of retaining orphans") {
        val fixture = pestFixture(world, plugin)
        fixture.controller.ensure(fixture.runtime)
        val originalIds = world.entities.filter(fixture.controller::ownsNest).mapTo(mutableSetOf()) { it.uniqueId }
        fixture.runtime.state = fixture.runtime.state.copy(sequence = fixture.runtime.state.sequence + 1)

        fixture.controller.ensure(fixture.runtime)

        val reconciled = world.entities.filter(fixture.controller::ownsNest)
        reconciled shouldHaveSize 4
        reconciled.none { it.uniqueId in originalIds } shouldBe true
    }

    test("cleanup removes every loaded pest-owned entity") {
        val fixture = pestFixture(world, plugin)
        fixture.controller.ensure(fixture.runtime)
        world.entities.count(fixture.controller::ownsNest) shouldBe 4

        fixture.controller.cleanup("reload")

        world.entities.count(fixture.controller::ownsNest) shouldBe 0
    }

    test("active pest lifecycle scans the world once instead of every ensure tick") {
        val fixture = pestFixture(world, plugin)

        repeat(20) { fixture.controller.ensure(fixture.runtime) }

        fixture.entityLookup.worldScans shouldBe 1
        fixture.entityLookup.globalScans shouldBe 0
    }
})

private data class PestFixture(
    val runtime: FarmRuntime,
    val controller: FarmPestIncident,
    val newController: () -> FarmPestIncident,
    val entityLookup: CountingFarmEntityLookup,
)

private fun pestFixture(world: WorldMock, plugin: Plugin): PestFixture {
    val entityLookup = CountingFarmEntityLookup()
    val config = mockk<ArcFarmsConfig> {
        every { sounds } returns false
        every { particles } returns false
    }
    val locale = mockk<ArcFarmsLocale>(relaxed = true) {
        every { render(any(), any(), any()) } answers { Component.text(firstArg<MessageKey>().path) }
    }
    val settings = mockk<FarmZoneSettings> {
        every { id } returns "communal_farm"
        every { displayViewRange } returns 1.0f
        every { pestNestDisplayScale } returns 2.0f
    }
    val nests = listOf(
        FarmPestNest(FarmPlotPosition(world.name, 4, 64, 4), health = 3),
        FarmPestNest(FarmPlotPosition(world.name, 12, 64, 12), health = 3),
    )
    val runtime = FarmRuntime(
        settings = settings,
        region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
        orders = emptyMap(),
        orderList = emptyList(),
        rules = mockk(relaxed = true),
        state = FarmShiftState(
            phase = FarmPhase.INCIDENT,
            sequence = 9L,
            incidentType = FarmIncidentType.PESTS,
            pestNestsInitialized = true,
            pestNests = nests,
        ),
    )
    val port = mockk<WorksiteRuntimePort>(relaxed = true) {
        every { players(any()) } returns emptyList()
    }
    val sink = FarmTransitionSink { target, result, _ -> if (result.accepted) target.state = result.state }
    fun create() = FarmPestIncident(
        plugin = plugin,
        settings = { config },
        locale = locale,
        debug = ArcFarmsDebug({ false }) {},
        access = port,
        audience = port,
        state = port,
        tasks = port,
        blockLedger = mockk<FarmBlockLedger>(relaxed = true),
        blockRegistry = mockk<FarmBlockRegistry>(relaxed = true),
        beds = FarmIncidentBedProvider { emptySet() },
        transitions = sink,
        random = Random(1L),
        entityLookup = entityLookup,
    )
    return PestFixture(runtime, create(), ::create, entityLookup)
}

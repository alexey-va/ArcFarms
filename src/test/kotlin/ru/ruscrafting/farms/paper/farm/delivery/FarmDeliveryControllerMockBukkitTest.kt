package ru.ruscrafting.farms.paper.farm.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Interaction
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmDeliverySettings
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.config.FarmSupplyPointSettings
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmDeliveryPosition
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.arc.paper.testing.MockBukkitTestRuntime

class FarmDeliveryControllerMockBukkitTest : FunSpec({
    lateinit var server: ServerMock
    lateinit var world: WorldMock
    lateinit var player: PlayerMock
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: Plugin

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        plugin = paper.createSimplePlugin("FarmDeliveryTest")
        world = server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
        player = server.addPlayer("Courier")
        player.teleport(Location(world, 2.5, 65.0, 2.5))
    }

    afterEach {
        paper.close()
    }

    test("ground crates reconcile after controller restart without duplication") {
        val fixture = deliveryFixture(world, plugin, crates = 2)
        fixture.controller.ensure(fixture.runtime)
        world.entities.filter(fixture.controller::owns) shouldHaveSize 4

        val restarted = fixture.newController()
        restarted.ensure(fixture.runtime)

        world.entities.filter(restarted::owns) shouldHaveSize 4
        world.entities.filterIsInstance<Interaction>().filter(restarted::isGroundInteraction) shouldHaveSize 2
    }

    test("a crate carried during a hard restart returns to the ground") {
        val fixture = deliveryFixture(world, plugin, crates = 2)
        fixture.controller.ensure(fixture.runtime)
        val crate = world.entities.filterIsInstance<Interaction>()
            .first { fixture.controller.identity(it)?.index == 0 }
        fixture.controller.pickup(fixture.runtime, requireNotNull(fixture.controller.identity(crate)), player) shouldBe true
        world.entities.filter(fixture.controller::owns) shouldHaveSize 3

        val restarted = fixture.newController()
        restarted.ensure(fixture.runtime)

        world.entities.filter(restarted::owns) shouldHaveSize 4
        restarted.carrierCount(fixture.runtime.settings.id) shouldBe 0
    }

    test("delivery movement applies the domain transition once") {
        val fixture = deliveryFixture(world, plugin, crates = 1)
        fixture.controller.ensure(fixture.runtime)
        val crate = world.entities.filterIsInstance<Interaction>()
            .single { fixture.controller.isGroundInteraction(it) }
        fixture.controller.pickup(fixture.runtime, requireNotNull(fixture.controller.identity(crate)), player) shouldBe true

        val receiving = Location(world, 12.5, 65.0, 12.5)
        fixture.controller.moveCarried(listOf(fixture.runtime), player, receiving)

        fixture.runtime.state.phase shouldBe FarmPhase.COOLDOWN
        fixture.runtime.state.deliveredCrates shouldBe setOf(0)
        fixture.controller.carrierCount(fixture.runtime.settings.id) shouldBe 0
    }
})

private data class DeliveryFixture(
    val runtime: FarmRuntime,
    val controller: FarmDeliveryController,
    val newController: () -> FarmDeliveryController,
)

private fun deliveryFixture(world: WorldMock, plugin: Plugin, crates: Int): DeliveryFixture {
    val delivery = FarmDeliverySettings(
        world = world.name,
        x = 12.5,
        y = 65.0,
        z = 12.5,
        radius = 2.0,
        crates = crates,
        spawnRadius = 12,
        minCrateSpacing = 3.0,
        pickup = FarmSupplyPointSettings(world.name, 4.5, 65.0, 4.5),
        itemMaterial = "CHEST",
        itemCustomModelData = 0,
        displayTransform = FarmItemDisplayTransform.FIXED,
        displayScale = 1.4f,
        displayYOffset = 0.2,
        carriedScale = 1.2f,
        carriedYOffset = 0.8,
        displayViewRange = 1.0f,
    )
    val zone = mockk<FarmZoneSettings> {
        every { id } returns "communal_farm"
        every { this@mockk.delivery } returns delivery
    }
    val runtime = FarmRuntime(
        settings = zone,
        region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
        orders = emptyMap(),
        orderList = emptyList(),
        rules = FarmRules(listOf(50), 1, 1_000L),
        state = FarmShiftState(
            phase = FarmPhase.DELIVERY,
            sequence = 9L,
            deliveryPosition = FarmDeliveryPosition(world.name, 4.5, 65.0, 4.5),
        ),
    )
    val config = mockk<ArcFarmsConfig> {
        every { sounds } returns false
        every { particles } returns false
    }
    val port = mockk<WorksiteRuntimePort>(relaxed = true)
    val points = FarmPointProvider { _, kind ->
        when (kind) {
            FarmPointKind.RECEIVING -> FarmPointPosition(world.name, 12.5, 65.0, 12.5)
            else -> FarmPointPosition(world.name, 4.5, 65.0, 4.5)
        }
    }
    val placement = mockk<FarmPlacementService> {
        every { deliveryCrateLocation(any(), any(), any()) } answers {
            Location(world, 4.5 + thirdArg<Int>() * 4.0, 65.0, 4.5)
        }
        every { selectDeliveryAnchor(any(), any()) } returns FarmDeliveryPosition(world.name, 4.5, 65.0, 4.5)
    }
    val sink = FarmTransitionSink { target, result, _ -> if (result.accepted) target.state = result.state }
    fun create() = FarmDeliveryController(
        plugin = plugin,
        settings = { config },
        debug = ArcFarmsDebug({ false }) {},
        port = port,
        points = points,
        placement = placement,
        transitions = sink,
        clock = { 10_000L },
    )
    return DeliveryFixture(runtime, create(), ::create)
}

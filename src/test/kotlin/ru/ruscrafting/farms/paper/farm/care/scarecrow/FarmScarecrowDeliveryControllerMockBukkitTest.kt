package ru.ruscrafting.farms.paper.farm.care.scarecrow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.player.PlayerMoveEvent
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmCareVisualSettings
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink

class FarmScarecrowDeliveryControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var server: ServerMock
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        world = server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("workers carry one physical scarecrow from receiving to each marked bed") {
        val player = server.addPlayer("Farmer")
        val visual = FarmCareVisualSettings("CARVED_PUMPKIN", 0, FarmItemDisplayTransform.GROUND, 1.0f, 0.0)
        val settings = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "communal_farm"
            every { permission } returns "arcfarms.farm"
            every { careVisuals } returns mapOf(FarmCareRole.SCARECROW to visual)
            every { displayViewRange } returns 2.0f
            every { scarecrowDeliveryRadius } returns 2.5
            every { scarecrowPickupRadius } returns 1.75
            every { scarecrowCarriedYOffset } returns 1.15
            every { scarecrowCarriedForwardOffset } returns 0.7
        }
        val targets = listOf(
            FarmCareTarget(0, FarmCareRole.SCARECROW, FarmPointPosition(world.name, 10.5, 65.0, 10.5)),
            FarmCareTarget(1, FarmCareRole.SCARECROW, FarmPointPosition(world.name, 14.5, 65.0, 10.5)),
        )
        val runtime = FarmRuntime(
            settings,
            CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
            emptyMap(),
            emptyList(),
            mockk(relaxed = true),
            FarmShiftState(
                phase = FarmPhase.CARE,
                sequence = 7,
                careType = FarmCareType.SCARECROWS,
                careTargets = targets,
                careGoal = 2,
            ),
        )
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { hasAccess(player, "arcfarms.farm") } returns true
        }
        val locale = mockk<ru.ruscrafting.farms.config.ArcFarmsLocale>(relaxed = true) {
            every { render(any(), any(), any()) } returns Component.text("Пугало")
        }
        val controller = FarmScarecrowDeliveryController(
            plugin = paper.createSimplePlugin("ScarecrowDeliveryTest"),
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            locale = locale,
            debug = ArcFarmsDebug({ false }) {},
            access = port,
            audience = port,
            points = FarmPointProvider { _, _ -> FarmPointPosition(world.name, 2.5, 65.0, 2.5) },
            transitions = FarmTransitionSink { target, result, _ -> target.state = result.state },
            runtimes = { listOf(runtime) },
        )

        controller.ensure(runtime)
        val supply = world.entities.filterIsInstance<Interaction>().single { controller.owns(it) }
        player.teleport(supply.location.clone().apply { yaw = -90f })
        controller.onMove(
            PlayerMoveEvent(player, player.location, supply.location.clone().apply { yaw = -90f }),
        )
        world.entities.filterIsInstance<ItemDisplay>().count { controller.owns(it) } shouldBe 2
        world.entities.filterIsInstance<ItemDisplay>().filter(controller::owns)
            .maxBy { it.location.x }.location.x.let { carriedX -> (carriedX > player.location.x) shouldBe true }

        // Pickup reserves target 0 internally, but any free nearby marker must accept the carried scarecrow.
        controller.onMove(
            PlayerMoveEvent(player, Location(world, 2.5, 65.0, 2.5), Location(world, 14.5, 65.0, 10.5)),
        )
        runtime.state.phase shouldBe FarmPhase.CARE
        runtime.state.careTargets.single { it.id == 1 }.complete shouldBe true

        controller.ensure(runtime)
        world.entities.filterIsInstance<ItemDisplay>().any { display ->
            controller.owns(display) && display.location.distanceSquared(Location(world, 14.5, 65.0, 10.5)) < 0.1
        } shouldBe true

        val replenished = world.entities.filterIsInstance<Interaction>().single { controller.owns(it) }
        controller.interact(player, replenished) shouldBe true
        controller.onMove(
            PlayerMoveEvent(player, Location(world, 14.5, 65.0, 10.5), Location(world, 10.5, 65.0, 10.5)),
        )
        runtime.state.phase shouldBe FarmPhase.HARVESTING

        controller.ensure(runtime)
        world.entities.none(controller::owns) shouldBe true
    }
})

package ru.ruscrafting.farms.paper.farm.incident.tornado

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import ru.ruscrafting.farms.config.FarmTornadoSettings
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture

class FarmTornadoIncidentMockBukkitTest : FunSpec({
    test("warning is harmless, debris stays bounded, empty farm pauses and resumed survival finishes with cleanup") {
        FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.TORNADO,
                sequence = 7, placementSequence = 2, orderId = "bakery_supply",
            ))
            runtime.settings = runtime.settings.copy(specialIncidents = runtime.settings.specialIncidents.copy(
                tornado = FarmTornadoSettings(warningSeconds = 3, durationSeconds = 10, debrisCount = 8),
            ))
            val plot = FarmPlotPosition(fixture.world.name, 24, 64, 24)
            fixture.world.getBlockAt(24, 64, 24).type = Material.FARMLAND
            val controller = FarmTornadoIncident(
                fixture.plugin, { fixture.settings }, fixture.port, fixture.port, fixture.port,
                FarmIncidentBedProvider { setOf(plot) },
                FarmTransitionSink { target, result, _ -> target.state = result.state },
            )
            controller.initialize(runtime) shouldBe true
            val player = fixture.paper.addPlayer("StormRunner")
            player.gameMode = GameMode.SURVIVAL
            player.teleport(Location(fixture.world, 24.5, 65.0, 24.5))
            val initialHealth = player.health
            repeat(60) { controller.update(runtime) }
            player.health shouldBe initialHealth
            runtime.state.incidentProgress shouldBe 0
            fixture.world.entities.count(controller::owns) shouldBe 8
            fixture.world.entities.filter(controller::owns).all { it is BlockDisplay && !it.isPersistent } shouldBe true
            controller.update(runtime)
            (player.health < initialHealth) shouldBe true
            (player.velocity.lengthSquared() > 0.0) shouldBe true
            player.teleport(Location(fixture.world, 50.5, 65.0, 50.5))
            repeat(39) { controller.update(runtime) }
            runtime.state.incidentProgress shouldBe 2
            every { fixture.port.players(any()) } returns emptyList()
            repeat(100) { controller.update(runtime) }
            runtime.state.incidentProgress shouldBe 2
            fixture.world.entities.count(controller::owns) shouldBe 0

            every { fixture.port.players(any()) } returns listOf(player)
            val saved = runtime.state.copy()
            controller.cleanup()
            runtime.state = saved
            repeat(60 + 8 * 20) { controller.update(runtime) }
            runtime.state.phase shouldBe FarmPhase.HARVESTING
            controller.update(runtime)
            fixture.world.entities.count(controller::owns) shouldBe 0
            fixture.world.getBlockAt(24, 64, 24).type shouldBe Material.FARMLAND
            controller.cleanup()
            controller.cleanup()
        }
    }

    test("roofed or missing beds reject placement and creative spectators cannot run the clock") {
        FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.TORNADO,
                sequence = 8, placementSequence = 3,
            ))
            val plot = FarmPlotPosition(fixture.world.name, 24, 64, 24)
            fixture.world.getBlockAt(24, 64, 24).type = Material.FARMLAND
            fixture.world.getBlockAt(24, 70, 24).type = Material.STONE
            val controller = FarmTornadoIncident(
                fixture.plugin, { fixture.settings }, fixture.port, fixture.port, fixture.port,
                FarmIncidentBedProvider { setOf(plot) },
                FarmTransitionSink { target, result, _ -> target.state = result.state },
            )
            controller.initialize(runtime) shouldBe false
            fixture.world.getBlockAt(24, 70, 24).type = Material.AIR
            controller.initialize(runtime) shouldBe true
            val player = fixture.paper.addPlayer("Observer")
            player.gameMode = GameMode.CREATIVE
            player.teleport(Location(fixture.world, 24.5, 65.0, 24.5))
            repeat(200) { controller.update(runtime) }
            runtime.state.incidentProgress shouldBe 0
            fixture.world.entities.count(controller::owns) shouldBe 0
            // A persisted incident that was interrupted before planning heals on the next visual tick.
            runtime.state = runtime.state.copy(specialIncident = null)
            player.gameMode = GameMode.SURVIVAL
            repeat(6) { controller.update(runtime) }
            (fixture.world.entities.count(controller::owns) > 0) shouldBe true
            every { fixture.port.isAdminEditing(player) } returns true
            controller.update(runtime)
            fixture.world.entities.count(controller::owns) shouldBe 0
            controller.cleanup()
            val scene = FarmTornadoScene(fixture.plugin)
            val center = Location(fixture.world, 24.5, 65.0, 24.5)
            scene.render("test", center, FarmTornadoSettings(debrisCount = 8), 3, 1.0, listOf(player), false)
            fixture.world.entities.count(scene::owns) shouldBe 8
            scene.render("test", center, FarmTornadoSettings(debrisCount = 8), 6, 1.0, emptyList(), false)
            fixture.world.entities.count(scene::owns) shouldBe 0
            scene.cleanup()
        }
    }
})

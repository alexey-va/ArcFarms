package ru.ruscrafting.farms.paper.farm.incident.tornado

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.WeatherType
import ru.ruscrafting.farms.config.FarmTornadoSettings
import ru.ruscrafting.farms.domain.FarmTornadoState
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.FarmBlockLedger

class FarmTornadoIncidentMockBukkitTest : FunSpec({
    test("warning is harmless, debris stays bounded, empty farm pauses and resumed survival finishes with cleanup") {
        FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.HARVESTING, tornado = FarmTornadoState(),
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
                FarmBlockLedger(fixture.plugin), fixture.night,
            )
            controller.initialize(runtime) shouldBe true
            val player = fixture.paper.addPlayer("StormRunner")
            player.gameMode = GameMode.SURVIVAL
            player.teleport(Location(fixture.world, 24.5, 65.0, 24.5))
            val initialHealth = player.health
            repeat(60) { controller.update(runtime) }
            player.health shouldBe initialHealth
            runtime.state.tornado!!.elapsedSeconds shouldBe 0
            player.playerTime shouldBe 13_000L
            player.playerWeather shouldBe WeatherType.DOWNFALL
            fixture.world.entities.count(controller::owns) shouldBe 8
            fixture.world.entities.filter(controller::owns).all { it is BlockDisplay && !it.isPersistent } shouldBe true
            controller.update(runtime)
            (player.health < initialHealth) shouldBe true
            player.velocity.y shouldBe 1.1
            (player.velocity.lengthSquared() > 0.0) shouldBe true
            val elevated = fixture.paper.addPlayer("ElevatedStormRunner")
            elevated.gameMode = GameMode.SURVIVAL
            elevated.teleport(Location(fixture.world, 24.5, 75.0, 24.5))
            controller.update(runtime)
            elevated.velocity.y shouldBe 0.65
            (elevated.velocity.clone().setY(0).length() > 1.0) shouldBe true
            player.teleport(Location(fixture.world, 50.5, 65.0, 50.5))
            repeat(39) { controller.update(runtime) }
            runtime.state.tornado!!.elapsedSeconds shouldBe 2
            every { fixture.port.players(any()) } returns emptyList()
            repeat(100) { controller.update(runtime) }
            runtime.state.tornado!!.elapsedSeconds shouldBe 2
            fixture.world.entities.count(controller::owns) shouldBe 0
            player.playerWeather shouldBe null
            repeat(60) { fixture.night.updatePlayerTimes() }
            player.playerTime shouldBe fixture.world.time

            every { fixture.port.players(any()) } returns listOf(player)
            val saved = runtime.state.copy()
            controller.cleanup()
            runtime.state = saved
            repeat(60 + 8 * 20) { controller.update(runtime) }
            runtime.state.phase shouldBe FarmPhase.HARVESTING
            runtime.state.tornado shouldBe null
            runtime.state.contributors shouldBe emptyMap()
            controller.update(runtime)
            fixture.world.entities.count(controller::owns) shouldBe 0
            fixture.world.getBlockAt(24, 64, 24).type shouldBe Material.FARMLAND
            controller.cleanup()
            controller.cleanup()
        }
    }

    test("roofed beds reject placement while creative participates and spectator or admin editing pauses") {
        FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.HARVESTING, tornado = FarmTornadoState(),
                sequence = 8, placementSequence = 3,
            ))
            val plot = FarmPlotPosition(fixture.world.name, 24, 64, 24)
            fixture.world.getBlockAt(24, 64, 24).type = Material.FARMLAND
            fixture.world.getBlockAt(24, 70, 24).type = Material.STONE
            val controller = FarmTornadoIncident(
                fixture.plugin, { fixture.settings }, fixture.port, fixture.port, fixture.port,
                FarmIncidentBedProvider { setOf(plot) },
                FarmTransitionSink { target, result, _ -> target.state = result.state },
                FarmBlockLedger(fixture.plugin), fixture.night,
            )
            controller.initialize(runtime) shouldBe false
            fixture.world.getBlockAt(24, 70, 24).type = Material.AIR
            controller.initialize(runtime) shouldBe true
            val player = fixture.paper.addPlayer("Observer")
            player.gameMode = GameMode.CREATIVE
            player.teleport(Location(fixture.world, 24.5, 65.0, 24.5))
            repeat(200) { controller.update(runtime) }
            (runtime.state.tornado!!.elapsedSeconds > 0) shouldBe true
            player.velocity.y shouldBe 1.1
            player.health shouldBe 20.0
            val creativeProgress = runtime.state.tornado!!.elapsedSeconds
            player.gameMode = GameMode.SPECTATOR
            repeat(200) { controller.update(runtime) }
            runtime.state.tornado!!.elapsedSeconds shouldBe creativeProgress
            // A persisted incident that was interrupted before planning heals on the next visual tick.
            runtime.state = runtime.state.copy(tornado = runtime.state.tornado!!.copy(points = emptyList()))
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

package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Interaction
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmTextDisplays

class FarmHellGreenhouseIncidentTest : FunSpec({
    test("greenhouse builds once, harvest must be cooled, exit completes and scene disappears") {
        FarmIncidentScenarioFixture.open().use { f ->
            val runtime = f.runtime(FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.HELL_GREENHOUSE,
                sequence = 4, placementSequence = 2, orderId = "bakery_supply"))
            runtime.settings = runtime.settings.copy(specialIncidents = runtime.settings.specialIncidents.copy(
                hellGreenhouse = FarmHellGreenhouseRules(quota = 2, growSeconds = 2, hotSeconds = 10),
            ))
            val plot = FarmPlotPosition(f.world.name, 24, 64, 24)
            for (x in 19..29) for (z in 18..30) f.world.getBlockAt(x, 64, z).type = Material.FARMLAND
            val controller = FarmHellGreenhouseIncident(f.plugin, { f.settings }, f.locale, f.port, f.port, f.port,
                FarmIncidentBedProvider { (19..29).flatMap { x -> (18..30).map { z -> FarmPlotPosition(f.world.name, x, 64, z) } }.toSet() }, FarmTransitionSink { target, result, _ -> target.state = result.state },
                MockBukkitFarmTextDisplays)
            val player = f.paper.addPlayer("PepperPicker")
            player.gameMode = GameMode.SURVIVAL
            player.teleport(Location(f.world, 24.5, 65.0, 24.5))
            val inventory = player.inventory.contents.toList()
            controller.initialize(runtime) shouldBe true
            controller.update(runtime)
            val count = f.world.entities.count(controller::owns)
            (count in 30..100) shouldBe true
            repeat(20) { controller.update(runtime) }
            f.world.entities.count(controller::owns) shouldBe count
            fun target(role: HellGreenhouseRole, index: Int = -1) = f.world.entities.filterIsInstance<Interaction>().single {
                controller.identity(it)?.let { id -> id.role == role && id.index == index } == true
            }
            fun click(entity: Interaction) {
                player.teleport(entity.location)
                controller.interact(PlayerInteractEntityEvent(player, entity, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            }
            click(target(HellGreenhouseRole.PEPPER, 0))
            runtime.state.hellGreenhouse!!.carried shouldBe emptyMap()
            repeat(20) { controller.update(runtime) }
            click(target(HellGreenhouseRole.PEPPER, 0))
            runtime.state.hellGreenhouse!!.carried.size shouldBe 1
            runtime.state.contributors shouldBe emptyMap()
            click(target(HellGreenhouseRole.VAT))
            runtime.state.hellGreenhouse!!.cooled shouldBe 1
            runtime.state.contributors[player.uniqueId] shouldBe 1
            click(target(HellGreenhouseRole.VAT))
            runtime.state.contributors[player.uniqueId] shouldBe 1
            click(target(HellGreenhouseRole.PEPPER, 1))
            click(target(HellGreenhouseRole.VAT))
            click(target(HellGreenhouseRole.EXIT))
            runtime.state.phase shouldBe FarmPhase.HARVESTING
            runtime.state.hellGreenhouse shouldBe null
            runtime.state.contributors[player.uniqueId] shouldBe 2
            f.world.entities.count(controller::owns) shouldBe 0
            player.inventory.contents.toList() shouldBe inventory
            f.world.getBlockAt(24, 64, 24).type shouldBe Material.FARMLAND
            controller.cleanup()
        }
    }

    test("pause restart leave and stale interactions cannot duplicate greenhouse progress") {
        FarmIncidentScenarioFixture.open().use { f ->
            val runtime = f.runtime(FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.HELL_GREENHOUSE,
                sequence = 5, placementSequence = 3, orderId = "bakery_supply"))
            val plot = FarmPlotPosition(f.world.name, 24, 64, 24)
            for (x in 19..29) for (z in 18..30) f.world.getBlockAt(x, 64, z).type = Material.FARMLAND
            fun controller() = FarmHellGreenhouseIncident(f.plugin, { f.settings }, f.locale, f.port, f.port, f.port,
                FarmIncidentBedProvider { setOf(plot) }, FarmTransitionSink { target, result, _ -> target.state = result.state },
                MockBukkitFarmTextDisplays)
            var owner = controller()
            val player = f.paper.addPlayer("HotHands")
            player.gameMode = GameMode.SURVIVAL
            player.teleport(Location(f.world, 24.5, 65.0, 24.5))
            owner.initialize(runtime) shouldBe true
            repeat(140) { owner.update(runtime) }
            val plant = f.world.entities.filterIsInstance<Interaction>().first { owner.identity(it)?.role == HellGreenhouseRole.PEPPER }
            player.teleport(plant.location)
            owner.interact(PlayerInteractEntityEvent(player, plant, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            val saved = runtime.state
            owner.cleanup()
            owner = controller()
            every { f.port.players(any()) } returns emptyList()
            repeat(100) { owner.update(runtime) }
            runtime.state shouldBe saved
            every { f.port.players(any()) } returns listOf(player)
            owner.update(runtime)
            runtime.state.hellGreenhouse!!.harvested.size shouldBe 1
            owner.releasePlayer(player.uniqueId, listOf(runtime))
            runtime.state.hellGreenhouse!!.carried shouldBe emptyMap()
            runtime.state.hellGreenhouse!!.harvested.size shouldBe 1
            owner.interact(PlayerInteractEntityEvent(player, plant, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            runtime.state.hellGreenhouse!!.cooled shouldBe 0
            runtime.state = runtime.state.copy(phase = FarmPhase.HARVESTING, hellGreenhouse = null)
            owner.update(runtime)
            f.world.entities.count(owner::owns) shouldBe 0
            owner.cleanup()
        }
    }

    test("placement rejects obstructed sites and unauthorized or remote clicks cannot harvest") {
        FarmIncidentScenarioFixture.open().use { f ->
            val runtime = f.runtime(FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.HELL_GREENHOUSE))
            val plot = FarmPlotPosition(f.world.name, 24, 64, 24)
            for (x in 19..29) for (z in 18..30) f.world.getBlockAt(x, 64, z).type = Material.FARMLAND
            val owner = FarmHellGreenhouseIncident(f.plugin, { f.settings }, f.locale, f.port, f.port, f.port,
                FarmIncidentBedProvider { setOf(plot) }, FarmTransitionSink { target, result, _ -> target.state = result.state },
                MockBukkitFarmTextDisplays)
            f.world.getBlockAt(24, 67, 24).type = Material.STONE
            owner.initialize(runtime) shouldBe false
            f.world.getBlockAt(24, 67, 24).type = Material.AIR
            f.world.getBlockAt(25, 64, 24).type = Material.WATER
            f.world.getBlockAt(25, 63, 24).type = Material.STONE
            owner.initialize(runtime) shouldBe true
            val player = f.paper.addPlayer("Observer")
            player.gameMode = GameMode.SURVIVAL
            player.teleport(Location(f.world, 24.5, 65.0, 24.5))
            repeat(140) { owner.update(runtime) }
            val plant = f.world.entities.filterIsInstance<Interaction>().first { owner.identity(it)?.role == HellGreenhouseRole.PEPPER }
            player.teleport(Location(f.world, 50.5, 65.0, 50.5))
            owner.interact(PlayerInteractEntityEvent(player, plant, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            runtime.state.hellGreenhouse!!.carried shouldBe emptyMap()
            player.teleport(plant.location)
            every { f.port.hasAccess(player, any()) } returns false
            owner.interact(PlayerInteractEntityEvent(player, plant, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            runtime.state.hellGreenhouse!!.carried shouldBe emptyMap()
            owner.cleanup()
        }
    }
    test("overheat exit and timeout both restore the ordinary order without extra contribution") {
        for (exitEarly in listOf(false, true)) FarmIncidentScenarioFixture.open().use { f ->
            val runtime = f.runtime(FarmShiftState(phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.HELL_GREENHOUSE, orderId = "bakery_supply"))
            runtime.settings = runtime.settings.copy(specialIncidents = runtime.settings.specialIncidents.copy(
                hellGreenhouse = FarmHellGreenhouseRules(heatLimit = 3, evacuationSeconds = 2),
            ))
            val plot = FarmPlotPosition(f.world.name, 24, 64, 24)
            f.world.getBlockAt(24, 64, 24).type = Material.FARMLAND
            val owner = FarmHellGreenhouseIncident(f.plugin, { f.settings }, f.locale, f.port, f.port, f.port,
                FarmIncidentBedProvider { setOf(plot) }, FarmTransitionSink { target, result, _ -> target.state = result.state },
                MockBukkitFarmTextDisplays)
            val player = f.paper.addPlayer("Evacuee")
            player.gameMode = GameMode.SURVIVAL
            player.teleport(Location(f.world, 50.5, 65.0, 50.5))
            owner.initialize(runtime) shouldBe true
            repeat(60) { owner.update(runtime) }
            runtime.state.hellGreenhouse!!.elapsedSeconds shouldBe 0
            player.teleport(Location(f.world, 24.5, 65.0, 24.5))
            repeat(60) { owner.update(runtime) }
            runtime.state.hellGreenhouse!!.evacuationSeconds shouldBe 2
            if (exitEarly) {
                val exit = f.world.entities.filterIsInstance<Interaction>().single { owner.identity(it)?.role == HellGreenhouseRole.EXIT }
                player.teleport(exit.location)
                owner.interact(PlayerInteractEntityEvent(player, exit, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            } else repeat(40) { owner.update(runtime) }
            runtime.state.phase shouldBe FarmPhase.HARVESTING
            runtime.state.hellGreenhouse shouldBe null
            runtime.state.contributors shouldBe emptyMap()
            f.world.entities.count(owner::owns) shouldBe 0
            owner.cleanup()
        }
    }

})

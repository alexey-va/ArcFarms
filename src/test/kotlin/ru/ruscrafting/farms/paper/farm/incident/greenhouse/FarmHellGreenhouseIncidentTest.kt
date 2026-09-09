package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Interaction
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorld
import ru.ruscrafting.farms.paper.fixtures.*

class FarmHellGreenhouseIncidentTest : FunSpec({
    test("underground entry preserves hands and returns players before restoring the room") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { f ->
            val runtime = f.runtime(FarmShiftState(phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.HELL_GREENHOUSE, sequence = 4, orderId = "bakery_supply"))
            runtime.settings = runtime.settings.copy(moleBurrow = runtime.settings.moleBurrow.copy(minDepth = 10, maxDepth = 10))
            for (x in 16..32) for (z in 16..32) for (y in 50..64) f.world.getBlockAt(x, y, z).type = Material.STONE
            val token = RuntimeTaskSupervisor(TestTaskScheduler()).apply { activate() }.token()
            every { f.port.lifecycleToken() } returns token
            every { f.port.runAsync(any(), any()) } answers { secondArg<() -> Unit>()(); true }
            every { f.port.runSync(any(), any()) } answers { secondArg<() -> Unit>()(); true }
            val rooms = FarmMoleBurrowWorld(f.plugin, ArcFarmsDebug({ false }) {},
                MockBukkitMoleBurrowChunkRetention(), MockBukkitFarmBlockDataDecoder, "farm_greenhouse")
            val visuals = mockk<ru.ruscrafting.farms.config.ArcFarmsConfig> {
                every { particles } returns false
                every { sounds } returns false
            }
            val owner = FarmHellGreenhouseIncident(f.plugin, { visuals }, f.locale,
                f.port, f.port, f.port, FarmIncidentBedProvider { setOf(FarmPlotPosition(f.world.name, 24, 64, 24)) },
                FarmTransitionSink { target, result, _ -> target.state = result.state }, FarmBlockLedger(f.plugin),
                MockBukkitFarmTextDisplays, rooms, f.port)
            val player = f.paper.addPlayer("GreenhouseExplorer")
            player.gameMode = GameMode.SURVIVAL
            player.teleport(Location(f.world, 24.5, 65.0, 24.5))
            player.inventory.heldItemSlot = 4
            owner.initialize(runtime) shouldBe true
            runtime.state.hellGreenhouse!!.entrance!!.y shouldBe 65.05
            runtime.state.hellGreenhouse!!.points.first().y shouldBe 55.0
            owner.update(runtime)
            f.world.entities.filterIsInstance<Interaction>().any { owner.identity(it)?.role == HellGreenhouseRole.ENTRANCE } shouldBe true
            repeat(3) { owner.processBlocks(listOf(runtime), 1000) }
            owner.update(runtime)
            val entrance = f.world.entities.filterIsInstance<Interaction>().single { owner.identity(it)?.role == HellGreenhouseRole.ENTRANCE }
            owner.interact(PlayerInteractEntityEvent(player, entrance, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            player.location.y shouldBe 55.05
            player.inventory.heldItemSlot shouldBe 4
            owner.retains(player) shouldBe true
            val pad = runtime.state.hellGreenhouse!!.points.first()
            player.teleport(Location(f.world, pad.x, pad.y, pad.z))
            runtime.state = runtime.state.copy(hellGreenhouse = runtime.state.hellGreenhouse!!.copy(elapsedSeconds = 5))
            repeat(59) { owner.update(runtime) }
            runtime.state.incidentProgress shouldBe 0
            owner.update(runtime)
            runtime.state.incidentProgress shouldBe 1
            player.inventory.heldItemSlot shouldBe 4
            repeat(60) { owner.update(runtime) }
            runtime.state.incidentProgress shouldBe 1
            owner.clear(runtime)
            player.location.y shouldBe 65.0
            owner.retains(player) shouldBe false
            repeat(3) { owner.processBlocks(listOf(runtime), 1000) }
            f.world.getBlockAt(24, 55, 24).type shouldBe Material.STONE
            owner.cleanup()
        } }
    }

    test("legacy surface greenhouse becomes a rift without losing progress") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { f ->
            val points = listOf(-2, 2).flatMap { x -> listOf(-3, -1, 1, 3).map { z ->
                FarmPointPosition(f.world.name, 24.5 + x, 65.0, 24.5 + z)
            } }
            val runtime = f.runtime(FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.HELL_GREENHOUSE,
                sequence = 5, incidentProgress = 1, incidentRequired = 4,
                hellGreenhouse = FarmHellGreenhouseState(points, harvested = setOf(0), cooled = 1)))
            runtime.settings = runtime.settings.copy(moleBurrow = runtime.settings.moleBurrow.copy(minDepth = 10, maxDepth = 10))
            for (x in 16..32) for (z in 16..32) for (y in 50..64) f.world.getBlockAt(x, y, z).type = Material.STONE
            val rooms = FarmMoleBurrowWorld(f.plugin, ArcFarmsDebug({ false }) {},
                MockBukkitMoleBurrowChunkRetention(), MockBukkitFarmBlockDataDecoder, "farm_greenhouse")
            val owner = FarmHellGreenhouseIncident(f.plugin, { f.settings }, f.locale, f.port, f.port, f.port,
                FarmIncidentBedProvider { setOf(FarmPlotPosition(f.world.name, 24, 64, 24)) }, FarmTransitionSink { target, result, _ -> target.state = result.state },
                FarmBlockLedger(f.plugin), MockBukkitFarmTextDisplays, rooms, f.port)
            owner.initialize(runtime) shouldBe true
            runtime.state.incidentProgress shouldBe 1
            runtime.state.hellGreenhouse!!.cooled shouldBe 1
            runtime.state.hellGreenhouse!!.points.first().y shouldBe 55.0
            runtime.state.hellGreenhouse!!.entrance!!.y shouldBe 65.05
            owner.initialize(runtime) shouldBe true
            runtime.state.hellGreenhouse!!.points.first().y shouldBe 55.0
            val quota = runtime.settings.specialIncidents.hellGreenhouse.quota
            runtime.state = runtime.state.copy(incidentProgress = quota,
                hellGreenhouse = runtime.state.hellGreenhouse!!.copy(cooled = quota, harvested = (0 until quota).toSet(), finished = true))
            owner.update(runtime)
            (runtime.state.incidentType == FarmIncidentType.HELL_GREENHOUSE) shouldBe false
            owner.cleanup()
        } }
    }
})

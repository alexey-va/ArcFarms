package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import ru.ruscrafting.farms.persistence.FarmBurrowReturn
import ru.ruscrafting.farms.persistence.FarmBurrowReturnRepository
import org.bukkit.Location
import java.nio.file.Path

class WorksiteExpeditionTravelTest : FunSpec({
    test("entry rejection does not create a return or move the player") {
        requiredMockBukkitScenario {
            FarmIncidentScenarioFixture.open().use { scenario ->
                val player = scenario.paper.addPlayer("RejectedExpedition")
                val surface = Location(scenario.world, 12.5, 65.0, 12.5)
                player.teleport(surface.clone().add(40.0, 0.0, 0.0))
                val travel = travel(scenario)
                travel.enter(request(player, surface, scenario.world.spawnLocation)) { true }
                player.location.distanceSquared(surface) shouldBe 1_600.0
                FarmBurrowReturnRepository(scenario.plugin.dataFolder.toPath(), DIR).load(player.uniqueId) shouldBe null
            }
        }
    }

    test("repeated entry is rejected while the first session is retained") {
        requiredMockBukkitScenario {
            FarmIncidentScenarioFixture.open().use { scenario ->
                val player = scenario.paper.addPlayer("RepeatedExpedition")
                val surface = Location(scenario.world, 12.5, 65.0, 12.5)
                player.teleport(surface)
                val travel = travel(scenario)
                travel.enter(request(player, surface, scenario.world.spawnLocation)) { true }
                val before = travel.record(player)
                travel.enter(request(player, surface, scenario.world.spawnLocation)) { true }
                travel.record(player) shouldBe before
            }
        }
    }

    test("denied return keeps the durable escrow for recovery") {
        requiredMockBukkitScenario {
            FarmIncidentScenarioFixture.open().use { scenario ->
                val player = scenario.paper.addPlayer("DeniedReturn")
                val record = FarmBurrowReturn(player.uniqueId, "mine", 4, "missing_world", 1.0, 64.0, 1.0, 0f, 0f, 1L)
                val repository = FarmBurrowReturnRepository(scenario.plugin.dataFolder.toPath(), DIR)
                repository.commit(record)
                val travel = travel(scenario)
                travel.returnToSurface(player, record) shouldBe false
                repository.load(player.uniqueId) shouldBe record
            }
        }
    }

    test("stale sync callback clears pending but leaves durable escrow") {
        requiredMockBukkitScenario {
            FarmIncidentScenarioFixture.open().use { scenario ->
                val token = RuntimeTaskSupervisor(TestTaskScheduler()).apply { activate() }.token()
                every { scenario.port.lifecycleToken() } returns token
                every { scenario.port.runAsync(any(), any()) } answers { secondArg<() -> Unit>()(); true }
                every { scenario.port.runSync(any(), any()) } returns false
                val player = scenario.paper.addPlayer("StaleExpedition")
                val surface = Location(scenario.world, 12.5, 65.0, 12.5)
                player.teleport(surface)
                val travel = travel(scenario)
                travel.enter(request(player, surface, scenario.world.spawnLocation)) { true }
                travel.retains(player) shouldBe false
                FarmBurrowReturnRepository(scenario.plugin.dataFolder.toPath(), DIR).load(player.uniqueId)?.zoneId shouldBe "mine"
            }
        }
    }
}) {
    companion object {
        private val DIR = Path.of("data/recovery/test-expedition-returns")

        private fun travel(fixture: FarmIncidentScenarioFixture) = WorksiteExpeditionTravel(
            fixture.plugin, fixture.port, fixture.port, fixture.port, DIR,
        )

        private fun request(player: org.bukkit.entity.Player, surface: Location, destination: Location) =
            WorksiteExpeditionTravel.EntryRequest(player, "mine", 4, "arcfarms.mine", surface, destination)
    }
}

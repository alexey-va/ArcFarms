package ru.ruscrafting.farms.paper.farm.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import org.bukkit.Location
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowScene
import ru.ruscrafting.farms.paper.fixtures.*
import ru.ruscrafting.farms.persistence.FarmBurrowReturnRepository

class FarmUndergroundExpeditionTest : FunSpec({
    test("commits durable return before entry, preserves held slot and exits exactly once") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.CARE,
                careType = FarmCareType.MOLES,
                sequence = 14,
            ))
            val expedition = expedition(fixture)
            val player = fixture.paper.addPlayer("UndergroundExplorer")
            player.gameMode = org.bukkit.GameMode.SURVIVAL
            player.teleport(Location(fixture.world, 12.5, 65.0, 12.5))
            player.inventory.heldItemSlot = 6
            val scene = scene(fixture.world, runtime.state.sequence)

            expedition.enter(player, runtime, scene)

            val record = FarmBurrowReturnRepository(fixture.plugin.dataFolder.toPath()).load(player.uniqueId)
            record?.world shouldBe fixture.world.name
            player.location.y shouldBe 55.05
            player.inventory.heldItemSlot shouldBe 6
            expedition.record(player)?.playerId shouldBe player.uniqueId
            expedition.exit(player) shouldBe true
            player.location.y shouldBe 65.0
            FarmBurrowReturnRepository(fixture.plugin.dataFolder.toPath()).load(player.uniqueId) shouldBe null
            expedition.retains(player) shouldBe false
        } }
    }

    test("stale lifecycle state acknowledges return without teleporting into the room") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.CARE,
                careType = FarmCareType.MOLES,
                sequence = 15,
            ))
            val expedition = expedition(fixture)
            val player = fixture.paper.addPlayer("StaleExplorer")
            player.teleport(Location(fixture.world, 12.5, 65.0, 12.5))
            val scene = scene(fixture.world, runtime.state.sequence)
            runtime.state = runtime.state.copy(phase = FarmPhase.HARVESTING)

            expedition.enter(player, runtime, scene)

            player.location.y shouldBe 65.0
            FarmBurrowReturnRepository(fixture.plugin.dataFolder.toPath()).load(player.uniqueId) shouldBe null
            expedition.retains(player) shouldBe false
        } }
    }

    test("rejected sync callback clears pending but preserves durable return for retry") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.CARE,
                careType = FarmCareType.MOLES,
                sequence = 16,
            ))
            var rejectSync = true
            val token = RuntimeTaskSupervisor(TestTaskScheduler()).apply { activate() }.token()
            every { fixture.port.lifecycleToken() } returns token
            every { fixture.port.runAsync(any(), any()) } answers { secondArg<() -> Unit>()(); true }
            every { fixture.port.runSync(any(), any()) } answers {
                if (rejectSync) {
                    rejectSync = false
                    false
                } else {
                    secondArg<() -> Unit>()()
                    true
                }
            }
            val expedition = FarmUndergroundExpedition(
                plugin = fixture.plugin,
                tasks = fixture.port,
                access = fixture.port,
                audience = fixture.port,
                state = fixture.port,
                variant = FarmUndergroundVariant.MOLES,
                settings = { fixture.settings },
                locale = fixture.locale,
                textDisplays = MockBukkitFarmTextDisplays,
            )
            val player = fixture.paper.addPlayer("RetryExplorer")
            player.teleport(Location(fixture.world, 12.5, 65.0, 12.5))
            val scene = scene(fixture.world, runtime.state.sequence)

            expedition.enter(player, runtime, scene)

            expedition.retains(player) shouldBe false
            FarmBurrowReturnRepository(fixture.plugin.dataFolder.toPath()).load(player.uniqueId)?.sequence shouldBe 16

            expedition.enter(player, runtime, scene)

            expedition.retains(player) shouldBe true
            player.location.y shouldBe 55.05
        } }
    }
}) {
    companion object {
        private fun expedition(fixture: FarmIncidentScenarioFixture): FarmUndergroundExpedition {
            val token = RuntimeTaskSupervisor(TestTaskScheduler()).apply { activate() }.token()
            every { fixture.port.lifecycleToken() } returns token
            every { fixture.port.runAsync(any(), any()) } answers { secondArg<() -> Unit>()(); true }
            every { fixture.port.runSync(any(), any()) } answers { secondArg<() -> Unit>()(); true }
            return FarmUndergroundExpedition(
            plugin = fixture.plugin,
            tasks = fixture.port,
            access = fixture.port,
            audience = fixture.port,
            state = fixture.port,
            variant = FarmUndergroundVariant.MOLES,
            settings = { fixture.settings },
            locale = fixture.locale,
            textDisplays = ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmTextDisplays,
        )

        }

        private fun scene(world: org.mockbukkit.mockbukkit.world.WorldMock, sequence: Long) = FarmMoleBurrowScene(
            world = world,
            zoneId = "communal_farm",
            sequence = sequence,
            burrowId = 0,
            surface = Location(world, 12.5, 65.0, 12.5),
            start = Location(world, 12.5, 55.0, 12.5),
            lair = Location(world, 14.5, 55.0, 12.5),
            records = emptyList(),
        )
    }
}

package ru.ruscrafting.farms.paper.farm.incident.route

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Horse
import org.bukkit.entity.Interaction
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import ru.ruscrafting.farms.paper.platform.FarmRouteChunkLoader
import java.util.concurrent.CompletableFuture

class FarmFoodDeliveryRecoveryMockBukkitTest : FunSpec({
    test("reload recovers a progressed delivery when its resume chunk was unloaded") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val supervisor = RuntimeTaskSupervisor(TestTaskScheduler()).apply(RuntimeTaskSupervisor::activate)
            val token = supervisor.token()
            every { fixture.port.lifecycleToken() } returns token
            every { fixture.port.runSync(any(), any()) } answers {
                secondArg<() -> Unit>().invoke()
                true
            }
            val route = (0..24).map { index ->
                FarmPointPosition(fixture.world.name, 8.5 + index * 4.0, 65.0, 32.5, -90f, 0f)
            }
            // Seed the eventual resume area, then unload its chunk to model a restart
            // before the route's persisted checkpoint has been loaded.
            for (x in 64..112) for (z in 28..36) fixture.world.getBlockAt(x, 64, z).type = Material.STONE
            fixture.world.unloadChunk(4, 2)
            fixture.world.isChunkLoaded(4, 2) shouldBe false
            var loadRequests = 0
            val resumeChunk = CompletableFuture<org.bukkit.Chunk?>()
            val chunkLoader = FarmRouteChunkLoader { _, _, _ ->
                loadRequests++
                resumeChunk
            }

            var runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 902,
                    placementSequence = 41,
                    orderId = "bakery_supply",
                    incidentType = FarmIncidentType.FOOD_DELIVERY,
                ),
            )
            var delivery = fixture.foodDelivery(runtime, route, chunkLoader)
            val player = fixture.paper.addPlayer("RecoveredDriver")

            // Establish the incident and its durable route identity before restart.
            fixture.world.getChunkAt(0, 2).load()
            delivery.ensure(runtime, 1_000L)
            runtime.state = runtime.state.copy(incidentProgress = 18)
            val persistedProgress = runtime.state.incidentProgress
            delivery.cleanup("simulated_restart")
            runtime = fixture.persistAndReload(runtime)
            delivery = fixture.foodDelivery(runtime, route, chunkLoader)
            runtime.state.incidentProgress shouldBe persistedProgress
            player.resetPlayerTime()

            // The missing checkpoint requests exactly once while portal/ambience
            // are restored immediately, without resetting persisted progress.
            delivery.ensure(runtime, 2_000L)
            delivery.ensure(runtime, 2_001L)
            loadRequests shouldBe 1
            runtime.state.incidentProgress shouldBe persistedProgress
            fixture.world.entities.filter(delivery::owns).shouldHaveSize(2)
            fixture.world.time = 6_000L
            repeat(400) { fixture.night.updatePlayerTimes() }
            player.playerTime shouldBe runtime.settings.routeDelivery.playerTime

            // Completing the platform load resumes the same session.
            fixture.world.loadChunk(4, 2, false)
            resumeChunk.complete(fixture.world.getChunkAt(4, 2))
            fixture.world.isChunkLoaded(4, 2) shouldBe true
            runtime.state.incidentProgress shouldBe persistedProgress

            val role = NamespacedKey(fixture.plugin, "farm_food_route_role")
            fixture.world.entities.filter(delivery::owns).shouldHaveSize(9)
            val portal = fixture.world.entities.filterIsInstance<Interaction>().single { entity ->
                entity.persistentDataContainer.get(role, PersistentDataType.STRING) == "portal"
            }
            portal.location.x shouldBe (12.5 plusOrMinus 0.0001)
            portal.location.z shouldBe (12.5 plusOrMinus 0.0001)
            fixture.world.entities.filterIsInstance<TextDisplay>().count { entity ->
                entity.persistentDataContainer.get(role, PersistentDataType.STRING) == "portal_label"
            } shouldBe 1
            fixture.world.entities.filterIsInstance<Horse>().count(delivery::owns) shouldBe 1

            fixture.world.time = 6_000L
            repeat(400) { fixture.night.updatePlayerTimes() }
            player.playerTime shouldBe runtime.settings.routeDelivery.playerTime

            val ids = fixture.world.entities.filter(delivery::owns).mapTo(linkedSetOf()) { it.uniqueId }
            delivery.ensure(runtime, 2_001L)
            fixture.world.entities.filter(delivery::owns).mapTo(linkedSetOf()) { it.uniqueId } shouldBe ids
        } }
    }
})

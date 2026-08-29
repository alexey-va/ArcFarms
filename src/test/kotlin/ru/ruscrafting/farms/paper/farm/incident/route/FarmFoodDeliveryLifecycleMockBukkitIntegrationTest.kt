package ru.ruscrafting.farms.paper.farm.incident.route

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.NamespacedKey
import org.bukkit.entity.Horse
import org.bukkit.entity.Interaction
import org.bukkit.entity.Mob
import org.bukkit.entity.TextDisplay
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario

class FarmFoodDeliveryLifecycleMockBukkitIntegrationTest : FunSpec({
    test("two-player delivery survives restart, resolves one wave at a time, and returns everyone to the farm") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val route = (0..50).map { index ->
                FarmPointPosition(fixture.world.name, 8.5 + index * 4.0, 65.0, 32.5, -90f, 0f)
            }
            var runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 63,
                    placementSequence = 27,
                    orderId = "bakery_supply",
                    incidentType = FarmIncidentType.FOOD_DELIVERY,
                    incidentCrop = "WHEAT",
                ),
            )
            var delivery = fixture.foodDelivery(runtime, route)
            val driver = fixture.paper.addPlayer("CaravanDriver")
            val gunner = fixture.paper.addPlayer("CaravanGunner")

            delivery.ensure(runtime, 1_000L)
            runtime.state.incidentProgress shouldBe 1
            runtime.state.incidentRequired shouldBe route.size
            requireNotNull(runtime.state.specialIncident).routeName shouldBe "main"
            fixture.world.entities.filter(delivery::owns) shouldHaveSize 9

            delivery.cleanup("simulated_restart")
            runtime = fixture.persistAndReload(runtime)
            delivery = fixture.foodDelivery(runtime, route)
            delivery.ensure(runtime, 2_000L)
            val restartedIds = fixture.world.entities.filter(delivery::owns).mapTo(linkedSetOf()) { it.uniqueId }
            restartedIds shouldHaveSize 9
            repeat(4) { delivery.ensure(runtime, 2_000L + it) }
            fixture.world.entities.filter(delivery::owns).mapTo(linkedSetOf()) { it.uniqueId } shouldBe restartedIds

            var horse = fixture.world.entities.filterIsInstance<Horse>().single(delivery::owns)
            val routeRole = NamespacedKey(fixture.plugin, "farm_food_route_role")
            val portal = fixture.world.entities.filterIsInstance<Interaction>().single { entity ->
                entity.persistentDataContainer.get(routeRole, PersistentDataType.STRING) == "portal"
            }
            val portalLabel = fixture.world.entities.filterIsInstance<TextDisplay>().single { entity ->
                entity.persistentDataContainer.get(routeRole, PersistentDataType.STRING) == "portal_label"
            }
            portal.location.x shouldBe (16.5 plusOrMinus 0.0001)
            portal.location.z shouldBe (12.5 plusOrMinus 0.0001)
            portal.interactionWidth shouldBe fixture.zone.routeDelivery.portalWidth
            portalLabel.location.pitch shouldBe 0f
            portalLabel.transformation.scale.x shouldBe fixture.zone.routeDelivery.portalLabelScale
            delivery.interact(PlayerInteractEntityEvent(gunner, portal, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            gunner.location.distanceSquared(horse.location) shouldBe (9.0 plusOrMinus 1.0)

            var seat = fixture.world.entities.filterIsInstance<Interaction>().filter(delivery::owns)
                .minBy { it.location.distanceSquared(horse.location) }
            // Either clickable entity fills the first free crew seat: driver first, then gunner.
            delivery.interact(PlayerInteractEntityEvent(driver, seat, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            delivery.interact(PlayerInteractEntityEvent(gunner, horse, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            horse.passengers.single() shouldBe driver
            seat.passengers.single() shouldBe gunner
            delivery.participants(runtime).map { it.uniqueId }.toSet() shouldBe
                setOf(driver.uniqueId, gunner.uniqueId)
            delivery.participantRuntime(driver, listOf(runtime)) shouldBe runtime
            delivery.participantRuntime(gunner, listOf(runtime)) shouldBe runtime
            driver.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true
            gunner.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true

            delivery.updateVisuals(listOf(runtime))
            seat.passengers.single() shouldBe gunner
            driver.leaveVehicle() shouldBe true
            delivery.updateVisuals(listOf(runtime))
            seat.passengers.single() shouldBe gunner
            delivery.interact(PlayerInteractEntityEvent(driver, horse, EquipmentSlot.HAND), listOf(runtime)) shouldBe true

            val farmExitIndex = route.indexOfFirst { point -> !runtime.region.contains(fixture.location(point)) }
            val firstAmbush = FarmFoodDeliveryAmbushPlanner.checkpoints(
                route,
                runtime.settings.routeDelivery.ambushDistance,
                runtime.settings.routeDelivery.ambushMaxCount,
                FarmFoodDeliveryAmbushPlanner.distanceAt(route, farmExitIndex) +
                    runtime.settings.routeDelivery.ambushAfterFarmDistance,
                runtime.settings.routeDelivery.ambushEndSafeDistance,
            ).first()
            val ambushLocation = fixture.location(route[firstAmbush - 1])
            val ambushAnchor = fixture.location(route[(firstAmbush + 2).coerceAtMost(route.lastIndex)])
            for (x in ambushAnchor.blockX - 20..ambushAnchor.blockX + 20) {
                for (z in ambushAnchor.blockZ - 20..ambushAnchor.blockZ + 20) {
                    fixture.world.getBlockAt(x, 64, z).type = org.bukkit.Material.STONE
                }
            }
            for (chunkX in (ambushAnchor.blockX shr 4) - 2..(ambushAnchor.blockX shr 4) + 2) {
                for (chunkZ in (ambushAnchor.blockZ shr 4) - 2..(ambushAnchor.blockZ shr 4) + 2) {
                    fixture.world.getChunkAt(chunkX, chunkZ).load()
                }
            }
            driver.leaveVehicle() shouldBe true
            horse.teleport(ambushLocation) shouldBe true
            delivery.interact(PlayerInteractEntityEvent(driver, horse, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            delivery.ensure(runtime, 13_000L)
            val firstWave = fixture.world.entities.filterIsInstance<Mob>()
                .filter { delivery.owns(it) && it !is Horse }
            firstWave.size shouldBeGreaterThanOrEqual 3
            firstWave.all { it.isGlowing } shouldBe true
            horse.passengers shouldBe emptyList()

            firstWave.forEach { it.remove() }
            delivery.ensure(runtime, 13_001L)
            fixture.world.entities.filterIsInstance<Mob>()
                .count { delivery.owns(it) && it !is Horse } shouldBe 0

            horse = fixture.world.entities.filterIsInstance<Horse>().single(delivery::owns)
            delivery.interact(PlayerInteractEntityEvent(driver, horse, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            delivery.ensure(runtime, 13_002L)
            fixture.world.entities.filterIsInstance<Mob>()
                .count { delivery.owns(it) && it !is Horse } shouldBe 0

            horse.passengers.single() shouldBe driver
            driver.leaveVehicle() shouldBe true
            // The visible arrival ring is wider than the route corridor. Entering
            // its edge must finish immediately, even from the previous checkpoint.
            runtime.state = runtime.state.copy(incidentProgress = route.lastIndex)
            val destination = fixture.location(route.last()).add(0.0, 0.0, 6.5)
            destination.chunk.load()
            horse.teleport(destination) shouldBe true
            delivery.interact(PlayerInteractEntityEvent(driver, horse, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            delivery.ensure(runtime, 13_003L)

            runtime.state.incidentProgress shouldBe 0
            runtime.state.phase shouldBe FarmPhase.HARVESTING
            runtime.state.incidentType shouldBe null
            runtime.state.specialIncident shouldBe null
            runtime.state.incidentsResolved shouldBe 1
            runtime.state.contributors.getValue(driver.uniqueId) shouldBe fixture.zone.routeDelivery.completionContribution
            driver.vehicle shouldBe null
            gunner.vehicle shouldBe null
            delivery.participants(runtime) shouldBe emptyList()

            fixture.runDelayedTasks().shouldContainExactly(fixture.zone.routeDelivery.returnDelaySeconds * 20L)
            driver.location.distanceSquared(fixture.location(route.first())) shouldBe 0.0
            gunner.location.distanceSquared(fixture.location(route.first())) shouldBe 0.0

            delivery.ensure(runtime, 13_004L)
            fixture.world.entities.none(delivery::owns) shouldBe true
            gunner.inventory.contents.filterNotNull().none(delivery::ownsServiceItem) shouldBe true
            fixture.transitions.count { it.result.accepted } shouldBeGreaterThanOrEqual 2
        } }
    }
})

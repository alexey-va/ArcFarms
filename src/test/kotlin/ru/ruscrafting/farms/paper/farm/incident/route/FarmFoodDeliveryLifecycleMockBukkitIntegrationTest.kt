package ru.ruscrafting.farms.paper.farm.incident.route

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Horse
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Mob
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.Zombie
import org.bukkit.attribute.Attribute
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
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
            val routeRole = NamespacedKey(fixture.plugin, "farm_food_route_role")
            val cart = fixture.world.entities.filterIsInstance<ItemDisplay>().single { entity ->
                entity.persistentDataContainer.get(routeRole, PersistentDataType.STRING) == "cart"
            }
            cart.isGlowing shouldBe true

            delivery.cleanup("simulated_restart")
            runtime = fixture.persistAndReload(runtime)
            delivery = fixture.foodDelivery(runtime, route)
            delivery.ensure(runtime, 2_000L)
            val restartedIds = fixture.world.entities.filter(delivery::owns).mapTo(linkedSetOf()) { it.uniqueId }
            restartedIds shouldHaveSize 9
            repeat(4) { delivery.ensure(runtime, 2_000L + it) }
            fixture.world.entities.filter(delivery::owns).mapTo(linkedSetOf()) { it.uniqueId } shouldBe restartedIds

            var horse = fixture.world.entities.filterIsInstance<Horse>().single(delivery::owns)
            val portal = fixture.world.entities.filterIsInstance<Interaction>().single { entity ->
                entity.persistentDataContainer.get(routeRole, PersistentDataType.STRING) == "portal"
            }
            val portalLabel = fixture.world.entities.filterIsInstance<TextDisplay>().single { entity ->
                entity.persistentDataContainer.get(routeRole, PersistentDataType.STRING) == "portal_label"
            }
            portal.location.x shouldBe (12.5 plusOrMinus 0.0001)
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

            fixture.world.time = 6_000L
            driver.resetPlayerTime()
            delivery.ensure(runtime, 2_004L)
            repeat(400) { fixture.night.updatePlayerTimes() }
            driver.playerTime shouldBe runtime.settings.routeDelivery.playerTime

            delivery.updateVisuals(listOf(runtime))
            seat.passengers.single() shouldBe gunner
            driver.leaveVehicle() shouldBe true
            delivery.updateVisuals(listOf(runtime))
            seat.passengers.single() shouldBe gunner
            every { fixture.port.players(any()) } returns listOf(gunner)
            delivery.ensure(runtime, 2_005L)
            driver.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true
            gunner.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true
            delivery.participants(runtime).map { it.uniqueId }.toSet() shouldBe
                setOf(driver.uniqueId, gunner.uniqueId)
            fixture.world.time = 12_000L
            repeat(40) { fixture.night.updatePlayerTimes() }
            driver.playerTime shouldBe runtime.settings.routeDelivery.playerTime

            val attacker = fixture.world.spawn(driver.location, Zombie::class.java)
            attacker.persistentDataContainer.set(
                NamespacedKey(fixture.plugin, "farm_food_route_zone"),
                PersistentDataType.STRING,
                runtime.settings.id,
            )
            attacker.persistentDataContainer.set(
                NamespacedKey(fixture.plugin, "farm_food_route_sequence"),
                PersistentDataType.LONG,
                runtime.state.sequence,
            )
            attacker.persistentDataContainer.set(routeRole, PersistentDataType.STRING, "monster")
            val attack = EntityDamageByEntityEvent(
                attacker,
                driver,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                1.0,
            )
            delivery.onDamage(attack, listOf(runtime)) shouldBe true
            attack.isCancelled shouldBe false
            attacker.remove()

            delivery.interact(PlayerInteractEntityEvent(driver, horse, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            driver.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true

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
            driver.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true
            gunner.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true
            delivery.ensure(runtime, 13_000L)
            driver.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true
            gunner.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true

            val target = firstWave.first()
            val firingPosition = target.location.clone().subtract(0.0, 0.0, 6.0)
            driver.teleport(firingPosition) shouldBe true
            driver.teleport(
                driver.location.clone().setDirection(
                    target.location.clone().add(0.0, target.height * 0.5, 0.0).toVector()
                        .subtract(driver.eyeLocation.toVector())
                        .normalize(),
                ),
            ) shouldBe true
            val totalHealthBeforeShot = firstWave.sumOf(Mob::getHealth)
            val shot = PlayerInteractEvent(
                driver,
                Action.RIGHT_CLICK_AIR,
                driver.inventory.itemInMainHand,
                null,
                BlockFace.SELF,
                EquipmentSlot.HAND,
            )
            delivery.onInteract(shot, listOf(runtime)) shouldBe true
            shot.isCancelled shouldBe true
            shot.useItemInHand() shouldBe Event.Result.DENY
            (firstWave.any(Mob::isDead) || firstWave.sumOf(Mob::getHealth) < totalHealthBeforeShot) shouldBe true
            driver.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true

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

    test("live route tuning keeps the mounted delivery session and refreshes its entities and rifle") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val route = (0..12).map { index ->
                FarmPointPosition(fixture.world.name, 8.5 + index * 2.0, 65.0, 32.5, -90f, 0f)
            }
            val runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 640,
                    placementSequence = 280,
                    orderId = "bakery_supply",
                    incidentType = FarmIncidentType.FOOD_DELIVERY,
                ),
            )
            val delivery = fixture.foodDelivery(runtime, route)
            val driver = fixture.paper.addPlayer("ReloadDriver")

            delivery.ensure(runtime, 3_000L)
            val horse = fixture.world.entities.filterIsInstance<Horse>().single(delivery::owns)
            delivery.interact(PlayerInteractEntityEvent(driver, horse, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            val cart = fixture.world.entities.filterIsInstance<ItemDisplay>().single { entity ->
                entity.persistentDataContainer.get(
                    NamespacedKey(fixture.plugin, "farm_food_route_role"),
                    PersistentDataType.STRING,
                ) == "cart"
            }
            val seat = fixture.world.entities.filterIsInstance<Interaction>().filter(delivery::owns)
                .minBy { it.location.distanceSquared(horse.location) }

            runtime.settings = runtime.settings.copy(
                routeDelivery = runtime.settings.routeDelivery.copy(
                    horseSpeed = 0.31,
                    horseJumpStrength = 0.2,
                    cartScale = 2.2f,
                    gunnerInteractionWidth = 1.4f,
                    rifleCustomModelData = 777,
                ),
            )
            delivery.ensure(runtime, 3_001L)
            delivery.updateVisuals(listOf(runtime))

            fixture.world.entities.filterIsInstance<Horse>().single(delivery::owns) shouldBe horse
            fixture.world.entities.filterIsInstance<ItemDisplay>().single { it.uniqueId == cart.uniqueId } shouldBe cart
            horse.passengers.single() shouldBe driver
            delivery.participants(runtime).single() shouldBe driver
            horse.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue shouldBe (0.31 plusOrMinus 0.0001)
            // MockBukkit does not expose this horse attribute on every supported API build.
            horse.getAttribute(Attribute.JUMP_STRENGTH)?.baseValue?.let { jumpStrength ->
                jumpStrength shouldBe (0.2 plusOrMinus 0.0001)
            }
            cart.transformation.scale.x shouldBe 2.2f
            seat.interactionWidth shouldBe 1.4f
            @Suppress("DEPRECATION")
            val rifle = driver.inventory.contents.filterNotNull().single(delivery::ownsServiceItem)
            @Suppress("DEPRECATION")
            rifle.itemMeta.customModelData shouldBe 777
        } }
    }

    test("suffocation rescue keeps the rear passenger armed as a walking escort") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val route = (0..12).map { index ->
                FarmPointPosition(fixture.world.name, 8.5 + index * 2.0, 65.0, 32.5, -90f, 0f)
            }
            val runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 64,
                    placementSequence = 28,
                    orderId = "bakery_supply",
                    incidentType = FarmIncidentType.FOOD_DELIVERY,
                ),
            )
            val delivery = fixture.foodDelivery(runtime, route)
            val driver = fixture.paper.addPlayer("RescueDriver")
            val gunner = fixture.paper.addPlayer("RescuedGunner")

            delivery.ensure(runtime, 3_000L)
            val horse = fixture.world.entities.filterIsInstance<Horse>().single(delivery::owns)
            val seat = fixture.world.entities.filterIsInstance<Interaction>().filter(delivery::owns)
                .minBy { it.location.distanceSquared(horse.location) }
            delivery.interact(PlayerInteractEntityEvent(driver, horse, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            delivery.interact(PlayerInteractEntityEvent(gunner, seat, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            driver.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true
            gunner.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true

            val suffocation = EntityDamageEvent(
                gunner,
                EntityDamageEvent.DamageCause.SUFFOCATION,
                DamageSource.builder(DamageType.IN_WALL).build(),
                1.0,
            )
            delivery.onDamage(suffocation, listOf(runtime)) shouldBe true

            suffocation.isCancelled shouldBe true
            gunner.vehicle shouldBe null
            delivery.participants(runtime).map { it.uniqueId }.toSet() shouldBe
                setOf(driver.uniqueId, gunner.uniqueId)
            driver.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true
            gunner.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true

            delivery.onQuit(gunner)
            delivery.participants(runtime).map { it.uniqueId }.toSet() shouldBe setOf(driver.uniqueId)
            gunner.inventory.contents.filterNotNull().none(delivery::ownsServiceItem) shouldBe true
        } }
    }

    test("stall watchdog ejects inactive crew without remounting or taking the rifle") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val route = (0..8).map { index ->
                FarmPointPosition(fixture.world.name, 8.5 + index * 2.0, 65.0, 32.5, -90f, 0f)
            }
            val runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 641,
                    placementSequence = 281,
                    orderId = "bakery_supply",
                    incidentType = FarmIncidentType.FOOD_DELIVERY,
                ),
            )
            runtime.settings = runtime.settings.copy(
                routeDelivery = runtime.settings.routeDelivery.copy(
                    inactivityReminderSeconds = 1,
                    inactivityResetSeconds = 2,
                ),
            )
            val delivery = fixture.foodDelivery(runtime, route)
            val driver = fixture.paper.addPlayer("InactiveDriver")

            delivery.ensure(runtime, 3_000L)
            val horse = fixture.world.entities.filterIsInstance<Horse>().single(delivery::owns)
            delivery.interact(PlayerInteractEntityEvent(driver, horse, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            driver.vehicle shouldBe horse

            fixture.paper.performTicks(41)
            delivery.ensure(runtime, 3_001L)
            delivery.updateVisuals(listOf(runtime))

            driver.vehicle shouldBe null
            horse.passengers shouldBe emptyList()
            delivery.participants(runtime).map { it.uniqueId } shouldContainExactly listOf(driver.uniqueId)
            driver.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true

            delivery.ensure(runtime, 3_002L)
            driver.vehicle shouldBe null
            driver.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true
        } }
    }

    test("route monsters can damage registered escorts but never bystanders") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val route = (0..8).map { index ->
                FarmPointPosition(fixture.world.name, 8.5 + index * 2.0, 65.0, 32.5, -90f, 0f)
            }
            val runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 65,
                    placementSequence = 29,
                    orderId = "bakery_supply",
                    incidentType = FarmIncidentType.FOOD_DELIVERY,
                ),
            )
            val delivery = fixture.foodDelivery(runtime, route)
            val escort = fixture.paper.addPlayer("RegisteredEscort")
            val bystander = fixture.paper.addPlayer("RouteBystander")

            delivery.ensure(runtime, 4_000L)
            val horse = fixture.world.entities.filterIsInstance<Horse>().single(delivery::owns)
            delivery.interact(PlayerInteractEntityEvent(escort, horse, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
            escort.inventory.contents.filterNotNull().any(delivery::ownsServiceItem) shouldBe true

            val monster = fixture.world.spawn(horse.location, Zombie::class.java)
            monster.persistentDataContainer.set(
                NamespacedKey(fixture.plugin, "farm_food_route_zone"),
                PersistentDataType.STRING,
                runtime.settings.id,
            )
            monster.persistentDataContainer.set(
                NamespacedKey(fixture.plugin, "farm_food_route_sequence"),
                PersistentDataType.LONG,
                runtime.state.sequence,
            )
            monster.persistentDataContainer.set(
                NamespacedKey(fixture.plugin, "farm_food_route_role"),
                PersistentDataType.STRING,
                "monster",
            )

            val bystanderAttack = EntityDamageByEntityEvent(
                monster,
                bystander,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                2.0,
            )
            delivery.onDamage(bystanderAttack, listOf(runtime)) shouldBe true
            bystanderAttack.isCancelled shouldBe true

            val escortAttack = EntityDamageByEntityEvent(
                monster,
                escort,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                2.0,
            )
            delivery.onDamage(escortAttack, listOf(runtime)) shouldBe true
            escortAttack.isCancelled shouldBe false

            delivery.clear(runtime.settings.id, "test_resolution")
            escort.inventory.contents.filterNotNull().none(delivery::ownsServiceItem) shouldBe true
            delivery.participants(runtime) shouldBe emptyList()
        } }
    }
})

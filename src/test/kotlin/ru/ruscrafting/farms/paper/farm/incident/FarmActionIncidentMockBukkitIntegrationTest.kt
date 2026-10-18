package ru.ruscrafting.farms.paper.farm.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Ghast
import org.bukkit.entity.Hoglin
import org.bukkit.entity.Interaction
import org.bukkit.entity.Mob
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Snowball
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import ru.ruscrafting.farms.paper.farm.presentation.FarmPortalRenderer
import kotlin.math.sqrt

class FarmActionIncidentMockBukkitIntegrationTest : FunSpec({
    test("successive boar starts choose different field points") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 4..44, 4..32)
            var bedEnumerations = 0
            val countedBeds = object : Set<FarmPlotPosition> by beds {
                override fun iterator(): Iterator<FarmPlotPosition> {
                    bedEnumerations++
                    return beds.iterator()
                }
            }
            val initial = actionState(FarmIncidentType.BOAR_BREAKOUT, 80)
            val runtime = fixture.runtime(initial).smallRivalField()
            val controller = fixture.actions(
                runtime,
                countedBeds,
                FarmPointPosition(fixture.world.name, 8.5, 65.0, 20.5),
                FarmPointPosition(fixture.world.name, 45.5, 65.0, 45.5),
                random = java.util.Random(73L),
            )

            controller.initialize(runtime, FarmIncidentType.BOAR_BREAKOUT) shouldBe FarmIncidentType.BOAR_BREAKOUT
            bedEnumerations shouldBe 1 // A successful boar plan must not scan the unused raid fallback.
            val first = requireNotNull(runtime.state.specialIncident).plots.toSet()
            controller.clear(runtime, "repeat_test")
            runtime.state = initial
            controller.initialize(runtime, FarmIncidentType.BOAR_BREAKOUT) shouldBe FarmIncidentType.BOAR_BREAKOUT
            bedEnumerations shouldBe 2
            val second = requireNotNull(runtime.state.specialIncident).plots.toSet()

            (first != second) shouldBe true
        } }
    }

    test("raid portal particles pulse even when the ambient scheduler is out of phase with world time") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 31..53, 31..53)
            val runtime = raidRuntime(fixture, 79)
            val boarRandom = mockk<java.util.random.RandomGenerator>()
            every { boarRandom.nextLong() } returns 1L
            val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
            val controller = fixture.actions(
                runtime,
                beds,
                receiving,
                FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5),
                random = boarRandom,
            )
            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            verify(exactly = 0) { boarRandom.nextLong() }
            controller.ensure(runtime)
            fixture.world.fullTime = 1L
            mockkObject(FarmPortalRenderer)
            try {
                every { FarmPortalRenderer.render(any(), any()) } just Runs

                controller.update(runtime)

                verify(exactly = 1) { FarmPortalRenderer.render(any(), any()) }
            } finally {
                unmockkObject(FarmPortalRenderer)
            }
        } }
    }

    test("a rejected raid still falls back to the next eligible boar plan") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 6..36, 8..24)
            val runtime = fixture.runtime(actionState(FarmIncidentType.RIVAL_RAID, 91))
            val controller = fixture.actions(runtime, beds,
                FarmPointPosition(fixture.world.name, 8.5, 65.0, 20.5),
                FarmPointPosition("other_world", 45.5, 65.0, 45.5))

            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.BOAR_BREAKOUT

            runtime.state.incidentType shouldBe FarmIncidentType.BOAR_BREAKOUT
            runtime.state.specialIncident?.plots?.isNotEmpty() shouldBe true
        } }
    }

    test("all rejected action plans retain every rejection in candidate order") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(actionState(FarmIncidentType.RIVAL_RAID, 92))
            val controller = fixture.actions(runtime, emptySet(),
                FarmPointPosition(fixture.world.name, 8.5, 65.0, 20.5),
                FarmPointPosition("other_world", 45.5, 65.0, 45.5))

            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe null

            verify(exactly = 1) {
                fixture.port.log(java.util.logging.Level.WARNING, match {
                    it.contains("attempts=RIVAL_RAID:wrong_world(candidates=0), BOAR_BREAKOUT:no_eligible_crops(candidates=0)")
                })
            }
            runtime.state.specialIncident shouldBe null
        } }
    }

    test("boars acquire a distant player and trample real crops along their charge") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 6..36, 8..24)
            val runtime = fixture.runtime(actionState(FarmIncidentType.BOAR_BREAKOUT, 81)).smallRivalField()
            val controller = fixture.actions(
                runtime,
                beds,
                FarmPointPosition(fixture.world.name, 8.5, 65.0, 20.5),
                FarmPointPosition(fixture.world.name, 45.5, 65.0, 45.5),
            )
            val defender = fixture.paper.addPlayer("BoarGuard")
            controller.initialize(runtime, FarmIncidentType.BOAR_BREAKOUT) shouldBe FarmIncidentType.BOAR_BREAKOUT
            (requireNotNull(runtime.state.specialIncident).plots.size > 16) shouldBe true
            controller.ensure(runtime)
            val boar = fixture.world.entities.filterIsInstance<Hoglin>().first(controller::owns)
            defender.teleport(boar.location.clone().add(8.0, 0.0, 0.0))
            val distance = sqrt(boar.location.distanceSquared(defender.location))
            (distance < fixture.zone.boarBreakout.aggroRadius) shouldBe true

            controller.update(runtime)

            boar.target shouldBe defender
            runtime.state.specialDamagedCrops.isNotEmpty() shouldBe true
            runtime.state.specialDamagedCrops.forEach { damage ->
                fixture.world.getBlockAt(damage.position.x, damage.position.y, damage.position.z).type shouldBe Material.DIRT
            }

            defender.teleport(fixture.world.getBlockAt(63, 100, 63).location.toCenterLocation())
            beds.take(140).forEach { plot ->
                boar.teleport(fixture.world.getBlockAt(plot.x, plot.y + 1, plot.z).location.toCenterLocation())
                controller.update(runtime)
            }
            (runtime.state.specialDamagedCrops.size > 96) shouldBe true
        } }
    }

    test("raid gives four riders smooth flight and two weapons while workers stay on outdoor beds") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 31..53, 31..53)
            val runtime = raidRuntime(fixture, 82)
            val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
            val rival = FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5)
            val controller = fixture.actions(runtime, beds, receiving, rival)
            val riders = (1..5).map { fixture.paper.addPlayer("GhastGunner$it") }
            riders.forEach { it.teleport(fixture.location(receiving)) }
            val packed = fixture.paper.addPlayer("PackedGunner")
            packed.inventory.storageContents.indices.forEach { slot ->
                packed.inventory.setItem(slot, org.bukkit.inventory.ItemStack(Material.COBBLESTONE, 64))
            }

            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            controller.ensure(runtime)
            val ghast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
            fixture.world.entities.filterIsInstance<Mob>().filter { it !is Ghast && controller.owns(it) } shouldHaveSize 0
            val plannedField = requireNotNull(runtime.state.specialIncident).plots
            plannedField shouldHaveSize 192
            (plannedField.minOf { it.x } in 36..38) shouldBe true
            (plannedField.maxOf { it.x } >= 52) shouldBe true
            (plannedField.minOf { it.z } <= 32) shouldBe true
            (plannedField.maxOf { it.z } >= 52) shouldBe true
            controller.interact(PlayerInteractEntityEvent(packed, ghast, EquipmentSlot.HAND)) shouldBe true
            packed.vehicle shouldBe null
            packed.inventory.storageContents.all { it?.type == Material.COBBLESTONE } shouldBe true

            riders.first().inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            riders.first().inventory.setItem(1, org.bukkit.inventory.ItemStack(Material.EMERALD, 5))
            riders.forEach { rider ->
                controller.interact(PlayerInteractEntityEvent(rider, ghast, EquipmentSlot.HAND)) shouldBe true
            }

            controller.updateRaidMotion(runtime)
            ghast.teleport(fixture.location(rival.copy(y = rival.y + fixture.zone.rivalRaid.flightHeight)))
            controller.update(runtime)
            fixture.world.entities.filterIsInstance<Mob>().filter { it !is Ghast && controller.owns(it) } shouldHaveSize
                fixture.zone.rivalRaid.workerSpawnBatchSize

            repeat(20) {
                controller.update(runtime)
                controller.updateRaidMotion(runtime)
                fixture.night.updatePlayerTimes()
            }

            (ghast.velocity.length() > 0.0) shouldBe true
            ghast.passengers shouldHaveSize 0
            val seats = fixture.world.entities.filterIsInstance<ArmorStand>()
                .filter { controller.owns(it) && it.passengers.isNotEmpty() }
            seats shouldHaveSize fixture.zone.rivalRaid.maximumRiders - 1
            seats.map { it.location.x to it.location.z }.toSet() shouldHaveSize seats.size
            riders.take(fixture.zone.rivalRaid.maximumRiders - 1).all { it.vehicle is ArmorStand } shouldBe true
            controller.participants(runtime).map { it.name } shouldBe
                listOf("PackedGunner") + riders.take(fixture.zone.rivalRaid.maximumRiders - 1).map { it.name }

            packed.inventory.clear()
            controller.update(runtime)
            controller.updateRaidMotion(runtime)
            (packed.vehicle is ArmorStand) shouldBe true
            packed.inventory.storageContents.count { it?.type == Material.PAPER } shouldBe 2
            fixture.world.entities.filterIsInstance<ArmorStand>()
                .filter { controller.owns(it) && it.passengers.isNotEmpty() }
                .map { it.location.x to it.location.z }.toSet() shouldHaveSize fixture.zone.rivalRaid.maximumRiders
            (fixture.raidRiderVisibilityEvents.count { (_, ghastId, hidden) ->
                ghastId == ghast.uniqueId && hidden
            } > fixture.zone.rivalRaid.maximumRiders) shouldBe true
            val workers = fixture.world.entities.filterIsInstance<Mob>().filter { it !is Ghast && controller.owns(it) }
                .also { it shouldHaveSize fixture.zone.rivalRaid.workerCount }
                .onEach {
                    it.equipment.itemInMainHand.type shouldBe Material.TORCH
                    it.location.block.getRelative(BlockFace.DOWN).type shouldBe Material.FARMLAND
                }
            val repeatedPatrols = fixture.raidNavigationMoves.groupBy { it.first }.values.filter { it.size > 1 }
            (repeatedPatrols.isNotEmpty()) shouldBe true

            val groundPlayer = riders.last()
            val worker = workers.first()
            val targetEvent = EntityTargetLivingEntityEvent(
                worker,
                groundPlayer,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER,
            )
            controller.onTarget(targetEvent) shouldBe true
            targetEvent.isCancelled shouldBe true
            val workerAttack = EntityDamageByEntityEvent(
                worker,
                groundPlayer,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                4.0,
            )
            controller.onDamage(workerAttack) shouldBe true
            workerAttack.isCancelled shouldBe true

            val gunner = riders.first()
            @Suppress("DEPRECATION")
            gunner.inventory.getItem(0)?.itemMeta?.customModelData shouldBe fixture.zone.rivalRaid.gunCustomModelData
            @Suppress("DEPRECATION")
            gunner.inventory.getItem(1)?.itemMeta?.customModelData shouldBe fixture.zone.rivalRaid.grenadeCustomModelData
            gunner.inventory.getItem(9)?.type shouldBe Material.DIAMOND
            gunner.inventory.getItem(10)?.type shouldBe Material.EMERALD
            val issued = gunner.inventory.storageContents.filterNotNull().filter { it.type == Material.PAPER }
            issued shouldHaveSize 2
            val gun = issued.single {
                @Suppress("DEPRECATION")
                it.itemMeta.customModelData == fixture.zone.rivalRaid.gunCustomModelData
            }
            gun.itemMeta.displayName()?.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            @Suppress("DEPRECATION")
            gun.itemMeta.customModelData shouldBe fixture.zone.rivalRaid.gunCustomModelData
            val grenade = issued.single {
                @Suppress("DEPRECATION")
                it.itemMeta.customModelData == fixture.zone.rivalRaid.grenadeCustomModelData
            }
            grenade.itemMeta.displayName()?.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE

            gunner.inventory.setItemInMainHand(gun)
            val burstShot = PlayerInteractEvent(
                gunner,
                Action.LEFT_CLICK_AIR,
                gun,
                null,
                BlockFace.SELF,
                EquipmentSlot.HAND,
            )
            controller.interact(burstShot) shouldBe true
            fixture.runDelayedTasks() shouldBe listOf(1L, 2L)

            gunner.inventory.setItemInMainHand(grenade)
            val leftClickShot = PlayerInteractEvent(
                gunner,
                Action.LEFT_CLICK_AIR,
                grenade,
                null,
                BlockFace.SELF,
                EquipmentSlot.HAND,
            )
            controller.interact(leftClickShot) shouldBe true
            leftClickShot.isCancelled shouldBe true
            leftClickShot.useItemInHand() shouldBe Event.Result.DENY
            fixture.world.entities.filterIsInstance<Snowball>() shouldHaveSize 1
            fixture.world.entities.filterIsInstance<Snowball>().single().remove()

            val targetClickGunner = riders[2]
            val targetClickGrenade = targetClickGunner.inventory.storageContents.filterNotNull().single {
                @Suppress("DEPRECATION")
                it.type == Material.PAPER && it.itemMeta.customModelData == fixture.zone.rivalRaid.grenadeCustomModelData
            }
            targetClickGunner.inventory.setItemInMainHand(targetClickGrenade)
            val leftClickTarget = EntityDamageByEntityEvent(
                targetClickGunner,
                workers.first(),
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                1.0,
            )
            controller.onDamage(leftClickTarget) shouldBe true
            leftClickTarget.isCancelled shouldBe true
            fixture.world.entities.filterIsInstance<Snowball>() shouldHaveSize 1
            fixture.world.entities.filterIsInstance<Snowball>().single().remove()

            val rightClickGunner = riders[1]
            val rightClickGrenade = rightClickGunner.inventory.storageContents.filterNotNull().single {
                @Suppress("DEPRECATION")
                it.type == Material.PAPER && it.itemMeta.customModelData == fixture.zone.rivalRaid.grenadeCustomModelData
            }
            rightClickGunner.inventory.setItemInMainHand(rightClickGrenade)
            controller.interact(PlayerInteractEntityEvent(rightClickGunner, workers.first(), EquipmentSlot.HAND)) shouldBe true
            val projectile = fixture.world.entities.filterIsInstance<Snowball>().single()
            val blastPlot = beds.first { it !in plannedField && it.x > 35 }
            val blastLocation = fixture.world.getBlockAt(blastPlot.x, blastPlot.y + 1, blastPlot.z).location.toCenterLocation()
            workers.take(2).forEach { it.teleport(blastLocation) }
            projectile.teleport(blastLocation)
            val blastSoil = fixture.world.getBlockAt(blastPlot.x, blastPlot.y, blastPlot.z)
            controller.onProjectileHit(ProjectileHitEvent(projectile, workers.first())) shouldBe true
            workers.take(2).all { it.isDead || it.health <= 0.0 } shouldBe true
            projectile.isValid shouldBe false
            runtime.state.incidentRequired shouldBe fixture.zone.rivalRaid.requiredKills
            blastSoil.type shouldBe Material.AIR
            blastSoil.getRelative(BlockFace.UP).type shouldBe Material.AIR
            fixture.world.entities.filterIsInstance<FallingBlock>() shouldHaveSize 0
            val debris = fixture.raidDebrisVisuals.fragments
            debris shouldHaveSize fixture.zone.rivalRaid.grenadeDebrisBlocks
            debris.all { it.positions.first().distanceSquared(blastLocation) < 2.0 } shouldBe true
            repeat(fixture.zone.rivalRaid.grenadeDebrisTicks) { controller.updateRaidMotion(runtime) }
            debris.all { it.removed } shouldBe true
            fixture.runDelayedTasks() shouldBe listOf(fixture.zone.rivalRaid.grenadePreviewTicks.toLong())
            blastSoil.type shouldBe Material.FARMLAND
            blastSoil.getRelative(BlockFace.UP).type shouldBe Material.WHEAT

            val seat = requireNotNull(gunner.vehicle)
            gunner.setPlayerTime(runtime.settings.rivalRaid.playerTime, false)
            controller.onDismount(EntityDismountEvent(gunner, seat)) shouldBe true
            gunner.leaveVehicle()
            fixture.runDelayedTasks() shouldBe listOf(1L)
            gunner.location.distanceSquared(fixture.location(receiving)) shouldBe 0.0
            fixture.raidRiderVisibilityEvents.last() shouldBe Triple(gunner.uniqueId, ghast.uniqueId, false)
            controller.participantRuntime(gunner) shouldBe null
            gunner.inventory.storageContents.filterNotNull().none { it.type == Material.PAPER } shouldBe true
            controller.update(runtime)
            repeat(runtime.settings.rivalRaid.timeTransitionSeconds * 20) { fixture.night.updatePlayerTimes() }
            gunner.playerTime shouldBe runtime.settings.rivalRaid.playerTime

            val remainingRiders = riders.drop(1).filter { it.vehicle != null }
            remainingRiders.forEach { rider ->
                val riderSeat = requireNotNull(rider.vehicle)
                controller.onDismount(EntityDismountEvent(rider, riderSeat)) shouldBe true
                rider.leaveVehicle()
            }
            fixture.runDelayedTasks() shouldBe List(remainingRiders.size) { 1L }
            ghast.velocity = org.bukkit.util.Vector()
            controller.updateRaidMotion(runtime)
            (ghast.velocity.length() > 0.0) shouldBe true
            controller.clear(runtime, "seat_cleanup_test")
            fixture.world.entities.filterIsInstance<ArmorStand>().filter(controller::owns) shouldHaveSize 0
        } }
    }

    test("raid starts airborne and switches from fast transit to slow orbit before steering") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 31..53, 31..53)
            val runtime = raidRuntime(fixture, 87)
            val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
            val rival = FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5)
            val clearFlight = object : ru.ruscrafting.farms.paper.platform.FarmRaidFlightSpace {
                override fun isClearAt(world: org.bukkit.World, center: org.bukkit.Location,
                    envelope: ru.ruscrafting.farms.paper.platform.FarmRaidFlightEnvelope) = true
                override fun isClearSegment(world: org.bukkit.World, from: org.bukkit.Location,
                    to: org.bukkit.Location, envelope: ru.ruscrafting.farms.paper.platform.FarmRaidFlightEnvelope) = true
            }
            val controller = fixture.actions(runtime, beds, receiving, rival, flightSpace = clearFlight)
            val rider = fixture.paper.addPlayer("FastTransitGunner")
            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            controller.ensure(runtime)
            val ghast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
            ghast.location.y shouldBe receiving.y + fixture.zone.rivalRaid.travelHeight
            controller.interact(PlayerInteractEntityEvent(rider, ghast, EquipmentSlot.HAND)) shouldBe true
            controller.updateRaidMotion(runtime)
            val transitSpeed = ghast.velocity.length()
            (transitSpeed > fixture.zone.rivalRaid.flightSpeed * 2.0) shouldBe true
            (ghast.velocity.y < 0.0) shouldBe true
            ghast.teleport(fixture.location(rival.copy(
                x = rival.x + fixture.zone.rivalRaid.orbitRadius,
                y = rival.y + fixture.zone.rivalRaid.flightHeight,
            )))
            fixture.world.gameTime = 10L
            controller.updateRaidMotion(runtime)
            (ghast.velocity.length() <= fixture.zone.rivalRaid.flightSpeed + 1.0e-9) shouldBe true
            (ghast.velocity.length() > 0.0) shouldBe true
            rider.vehicle shouldBe fixture.world.entities.filterIsInstance<ArmorStand>().single(controller::owns)
            controller.clear(runtime, "speed_mode_cleanup")
        } }
    }

    test("stuck raid recovery carries the existing rider seat and still cleans up on exit") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 31..53, 31..53)
            val runtime = raidRuntime(fixture, 86)
            val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
            val rival = FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5)
            val blockedFlight = object : ru.ruscrafting.farms.paper.platform.FarmRaidFlightSpace {
                override fun isClearAt(world: org.bukkit.World, center: org.bukkit.Location,
                    envelope: ru.ruscrafting.farms.paper.platform.FarmRaidFlightEnvelope) = true
                override fun isClearSegment(world: org.bukkit.World, from: org.bukkit.Location,
                    to: org.bukkit.Location, envelope: ru.ruscrafting.farms.paper.platform.FarmRaidFlightEnvelope) = false
            }
            val controller = fixture.actions(runtime, beds, receiving, rival, flightSpace = blockedFlight)
            val rider = fixture.paper.addPlayer("RecoveryGunner")
            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            controller.ensure(runtime)
            val ghast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
            controller.interact(PlayerInteractEntityEvent(rider, ghast, EquipmentSlot.HAND)) shouldBe true
            fixture.world.gameTime = 0L
            controller.updateRaidMotion(runtime)
            val seat = requireNotNull(rider.vehicle)
            val before = ghast.location
            val seatBefore = seat.location

            fixture.world.gameTime = 40L
            controller.updateRaidMotion(runtime)

            (ghast.location.distanceSquared(before) > 200.0) shouldBe true
            rider.vehicle shouldBe seat
            seat.passengers shouldBe listOf(rider)
            val expectedSeat = seatBefore.add(ghast.location.toVector().subtract(before.toVector()))
            (seat.location.distanceSquared(expectedSeat) < 1.0e-10) shouldBe true
            controller.participants(runtime) shouldBe listOf(rider)
            controller.clear(runtime, "recovery_cleanup_test")
            rider.vehicle shouldBe null
            fixture.world.entities.filterIsInstance<ArmorStand>().filter(controller::owns) shouldHaveSize 0
        } }
    }

    test("raid workers retarget their patrol around the moving ghast") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 2..61, 2..61)
            val runtime = raidRuntime(fixture, 85)
            val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
            val rival = FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5)
            val controller = fixture.actions(runtime, beds, receiving, rival)
            val rider = fixture.paper.addPlayer("MovingFocusGunner")

            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            controller.ensure(runtime)
            val ghast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
            controller.interact(PlayerInteractEntityEvent(rider, ghast, EquipmentSlot.HAND)) shouldBe true
            controller.updateRaidMotion(runtime)
            ghast.teleport(fixture.location(rival.copy(y = rival.y + fixture.zone.rivalRaid.flightHeight)))
            repeat(20) { controller.update(runtime) }

            fixture.raidNavigationMoves.clear()
            val movedFocus = FarmPointPosition(fixture.world.name, 25.5, ghast.location.y, 42.5)
            ghast.eject()
            ghast.teleport(fixture.location(movedFocus)) shouldBe true
            repeat(4) { controller.update(runtime) }

            fixture.raidNavigationMoves.isNotEmpty() shouldBe true
            val focusRadius = runtime.settings.rivalRaid.workerFocusRadius + 0.5
            val outside = fixture.raidNavigationMoves.filterNot { (_, target, _) ->
                val dx = target.x - movedFocus.x
                val dz = target.z - movedFocus.z
                dx * dx + dz * dz <= focusRadius * focusRadius
            }
            check(outside.isEmpty()) { "patrols outside ghast focus: ${outside.map { it.second }}" }
        } }
    }

    test("raid portal counts down by title, cancels on exit, and mounts only the current entry") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 31..53, 31..53)
            val runtime = raidRuntime(fixture, 83)
            val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
            val rival = FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5)
            val portalPoint = FarmPointPosition(fixture.world.name, 14.5, 65.0, 14.5)
            val controller = fixture.actions(runtime, beds, receiving, rival, portalPoint)
            val rider = fixture.paper.addPlayer("PortalGunner")
            val cancelled = fixture.paper.addPlayer("PortalCancelled")

            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            controller.ensure(runtime)
            val portal = fixture.world.entities.filterIsInstance<Interaction>().single(controller::owns)
            portal.location.x shouldBe portalPoint.x
            portal.location.z shouldBe portalPoint.z

            rider.teleport(portal.location)
            controller.enterPortal(rider, rider.location) shouldBe true
            verify(exactly = 1) {
                fixture.port.showScreenTitle(
                    rider,
                    MessageKey.FARM_ACTIVITY_PORTAL_COUNTDOWN,
                    match { values -> plain(values.getValue("seconds")) == "3" },
                    "activity_portal",
                )
            }
            fixture.runDelayedTasks() shouldBe listOf(20L)
            verify(exactly = 1) {
                fixture.port.showScreenTitle(
                    rider,
                    MessageKey.FARM_ACTIVITY_PORTAL_COUNTDOWN,
                    match { values -> plain(values.getValue("seconds")) == "2" },
                    "activity_portal",
                )
            }
            fixture.runDelayedTasks() shouldBe listOf(20L)
            verify(exactly = 1) {
                fixture.port.showScreenTitle(
                    rider,
                    MessageKey.FARM_ACTIVITY_PORTAL_COUNTDOWN,
                    match { values -> plain(values.getValue("seconds")) == "1" },
                    "activity_portal",
                )
            }
            fixture.runDelayedTasks() shouldBe listOf(20L)
            (rider.vehicle is ArmorStand) shouldBe true
            val airborneGhast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
            airborneGhast.location.y shouldBe receiving.y + fixture.zone.rivalRaid.travelHeight
            requireNotNull(rider.vehicle).location.y shouldBe airborneGhast.location.y + fixture.zone.rivalRaid.seatYOffset
            controller.participantRuntime(rider) shouldBe runtime

            cancelled.teleport(portal.location)
            controller.enterPortal(cancelled, cancelled.location) shouldBe true
            val outside = portal.location.clone().add(portal.interactionWidth.toDouble(), 0.0, 0.0)
            cancelled.teleport(outside)
            controller.enterPortal(cancelled, outside) shouldBe false
            verify(exactly = 1) { fixture.port.clearScreenTitle(cancelled) }

            // Re-enter before the cancelled timer fires: its stale callback must
            // not advance or cancel the new countdown for the same raid sequence.
            cancelled.teleport(portal.location)
            controller.enterPortal(cancelled, cancelled.location) shouldBe true
            fixture.runDelayedTasks() shouldBe listOf(20L, 20L)
            cancelled.vehicle shouldBe null
            fixture.runDelayedTasks() shouldBe listOf(20L)
            fixture.runDelayedTasks() shouldBe listOf(20L)
            (cancelled.vehicle is ArmorStand) shouldBe true
            controller.participantRuntime(cancelled) shouldBe runtime
        } }
    }

    test("outbound raid grenades leave the home farm and crops beyond the rival field unchanged") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 31..53, 31..53)
            val protected = plantedField(fixture, 6..10, 6..10) + plantedField(fixture, 34..35, 40..44) +
                plantedField(fixture, 108..112, 40..44)
            val originals = protected.associateWith { plot ->
                val soil = fixture.world.getBlockAt(plot.x, plot.y, plot.z)
                soil.blockData.asString to soil.getRelative(BlockFace.UP).blockData.asString
            }
            val runtime = raidRuntime(fixture, 88)
            val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
            val rival = FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5)
            val controller = fixture.actions(runtime, beds, receiving, rival)
            val gunner = fixture.paper.addPlayer("OutboundGunner")
            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            controller.ensure(runtime)
            val ghast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
            controller.interact(PlayerInteractEntityEvent(gunner, ghast, EquipmentSlot.HAND)) shouldBe true
            val grenade = gunner.inventory.getItem(1)!!
            gunner.inventory.setItemInMainHand(grenade)

            listOf(8 to 8, 35 to 42, 110 to 42).forEach { (x, z) ->
                fixture.world.gameTime += runtime.settings.rivalRaid.grenadeCooldownTicks
                controller.interact(PlayerInteractEvent(gunner, Action.LEFT_CLICK_AIR, grenade, null,
                    BlockFace.SELF, EquipmentSlot.HAND)) shouldBe true
                val projectile = fixture.world.entities.filterIsInstance<Snowball>().single()
                val impact = fixture.world.getBlockAt(x, 65, z)
                projectile.teleport(impact.location.toCenterLocation())
                controller.onProjectileHit(ProjectileHitEvent(projectile, impact)) shouldBe true
                projectile.isValid shouldBe false
                originals.forEach { (plot, original) ->
                    val soil = fixture.world.getBlockAt(plot.x, plot.y, plot.z)
                    (soil.blockData.asString to soil.getRelative(BlockFace.UP).blockData.asString) shouldBe original
                }
                fixture.raidDebrisVisuals.fragments shouldHaveSize 0
                fixture.world.loadedChunks.flatMap(FarmBlockLedger(fixture.plugin)::blockRecords) shouldHaveSize 0
            }
        } }
    }

    test("raid gun and grenade damage do not reach workers or beds inside the home boundary") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 31..53, 31..53) + plantedField(fixture, 34..40, 40..44)
            val runtime = raidRuntime(fixture, 89)
            val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
            val rival = FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5)
            val controller = fixture.actions(runtime, beds, receiving, rival)
            val gunner = fixture.paper.addPlayer("BoundaryGunner")
            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            controller.ensure(runtime)
            val ghast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
            controller.interact(PlayerInteractEntityEvent(gunner, ghast, EquipmentSlot.HAND)) shouldBe true
            controller.updateRaidMotion(runtime)
            ghast.teleport(fixture.location(rival.copy(y = rival.y + fixture.zone.rivalRaid.flightHeight)))
            controller.update(runtime)
            val workers = fixture.world.entities.filterIsInstance<Mob>().filter { it !is Ghast && controller.owns(it) }
            val protectedWorker = workers[0]
            val enemyWorker = workers[1]
            val homeSoil = fixture.world.getBlockAt(35, 64, 42)
            val original = homeSoil.blockData.asString to homeSoil.getRelative(BlockFace.UP).blockData.asString
            protectedWorker.teleport(homeSoil.location.clone().add(0.5, 1.0, 0.5))
            val protectedHealth = protectedWorker.health
            val enemySoil = fixture.world.getBlockAt(36, 64, 42)
            gunner.leaveVehicle()
            val aim = protectedWorker.location.clone().add(0.0, protectedWorker.height * 0.5, -3.0)
                .subtract(0.0, gunner.eyeHeight, 0.0)
            aim.yaw = 0f
            aim.pitch = 0f
            gunner.teleport(aim)
            val gun = gunner.inventory.getItem(0)!!
            val grenade = gunner.inventory.getItem(1)!!
            gunner.inventory.setItemInMainHand(gun)
            controller.interact(PlayerInteractEvent(gunner, Action.LEFT_CLICK_AIR, gun, null,
                BlockFace.SELF, EquipmentSlot.HAND)) shouldBe true
            fixture.runDelayedTasks() shouldBe listOf(1L, 2L)
            protectedWorker.health shouldBe protectedHealth
            (homeSoil.blockData.asString to homeSoil.getRelative(BlockFace.UP).blockData.asString) shouldBe original
            enemyWorker.teleport(enemySoil.location.clone().add(0.5, 1.0, 0.5))

            gunner.inventory.setItemInMainHand(grenade)
            controller.interact(PlayerInteractEvent(gunner, Action.LEFT_CLICK_AIR, grenade, null,
                BlockFace.SELF, EquipmentSlot.HAND)) shouldBe true
            val projectile = fixture.world.entities.filterIsInstance<Snowball>().single()
            projectile.teleport(enemyWorker.location)
            controller.onProjectileHit(ProjectileHitEvent(projectile, enemyWorker)) shouldBe true
            protectedWorker.health shouldBe protectedHealth
            (enemyWorker.isDead || enemyWorker.health <= 0.0) shouldBe true
            (homeSoil.blockData.asString to homeSoil.getRelative(BlockFace.UP).blockData.asString) shouldBe original
            enemySoil.type shouldBe Material.AIR
        } }
    }

    test("raid cleanup without a session restores old home and rival craters but preserves foreign journals") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val ledger = FarmBlockLedger(fixture.plugin)
            val home = fixture.world.getBlockAt(8, 64, 8).also { it.type = Material.FARMLAND }
            val enemy = fixture.world.getBlockAt(42, 64, 42).also { it.type = Material.FARMLAND }
            val foreign = fixture.world.getBlockAt(9, 64, 8).also { it.type = Material.FARMLAND }
            home.blockData = (home.blockData as org.bukkit.block.data.type.Farmland).apply { moisture = maximumMoisture }
            home.getRelative(BlockFace.UP).type = Material.POTATOES
            home.getRelative(BlockFace.UP).blockData = (home.getRelative(BlockFace.UP).blockData as Ageable).apply { age = maximumAge }
            enemy.getRelative(BlockFace.UP).type = Material.CARROTS
            val originals = listOf(home, enemy).associateWith {
                it.blockData.asString to it.getRelative(BlockFace.UP).blockData.asString
            }
            ledger.replaceZoneIndex(home.chunk, fixture.zone.id, listOf(home, foreign), emptyList(), emptyList())
            ledger.beginTemporaryRemoval(listOf(home, enemy), fixture.zone.id, "raid:${fixture.zone.id}:2", 100L)
            ledger.beginTemporaryRemoval(listOf(foreign), fixture.zone.id, "tornado:${fixture.zone.id}:2", 100L)
            listOf(home, enemy, foreign).forEach {
                it.type = Material.COARSE_DIRT
                it.getRelative(BlockFace.UP).type = Material.FIRE
            }
            val runtime = raidRuntime(fixture, 90)
            val controller = fixture.actions(runtime, emptySet(),
                FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5),
                FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5))

            controller.cleanup("service_start")

            originals.forEach { (soil, original) ->
                (soil.blockData.asString to soil.getRelative(BlockFace.UP).blockData.asString) shouldBe original
            }
            val recovered = FarmBlockLedger(fixture.plugin)
            recovered.record(home)?.temporaryMutation shouldBe null
            recovered.record(home)?.indexed shouldBe true
            recovered.record(enemy) shouldBe null
            foreign.type shouldBe Material.COARSE_DIRT
            foreign.getRelative(BlockFace.UP).type shouldBe Material.FIRE
            recovered.record(foreign)?.temporaryMutation shouldBe "tornado:${fixture.zone.id}:2"
            recovered.record(foreign)?.indexed shouldBe true
        } }
    }

    listOf(false, true).forEach { unloadedTimer ->
    test(if (unloadedTimer) "raid crater timer retains unloaded journals and retries after chunk load"
        else "raid resolution immediately restores an active grenade crater") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 31..53, 31..53)
            val runtime = raidRuntime(fixture, 84)
            val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
            val rival = FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5)
            val controller = fixture.actions(runtime, beds, receiving, rival)
            val gunner = fixture.paper.addPlayer("FinalBlastGunner")

            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            controller.ensure(runtime)
            val ghast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
            controller.interact(PlayerInteractEntityEvent(gunner, ghast, EquipmentSlot.HAND)) shouldBe true
            controller.updateRaidMotion(runtime)
            ghast.teleport(fixture.location(rival.copy(y = rival.y + fixture.zone.rivalRaid.flightHeight)))
            controller.update(runtime)
            val worker = fixture.world.entities.filterIsInstance<Mob>().first { it !is Ghast && controller.owns(it) }

            val grenade = gunner.inventory.storageContents.filterNotNull().single {
                @Suppress("DEPRECATION")
                it.type == Material.PAPER && it.itemMeta.customModelData == fixture.zone.rivalRaid.grenadeCustomModelData
            }
            gunner.inventory.setItemInMainHand(grenade)
            controller.interact(PlayerInteractEntityEvent(gunner, worker, EquipmentSlot.HAND)) shouldBe true
            val projectile = fixture.world.entities.filterIsInstance<Snowball>().single()
            val impact = worker.location.clone()
            val blastSoil = impact.block.getRelative(BlockFace.DOWN)
            projectile.teleport(impact)

            controller.onProjectileHit(ProjectileHitEvent(projectile, worker)) shouldBe true

            blastSoil.type shouldBe Material.AIR
            if (unloadedTimer) {
                val chunk = blastSoil.chunk
                chunk.unload() shouldBe true
                fixture.runDelayedTasks() shouldBe listOf(fixture.zone.rivalRaid.grenadePreviewTicks.toLong())
                blastSoil.type shouldBe Material.AIR
                FarmBlockLedger(fixture.plugin).record(blastSoil)?.temporaryMutation shouldBe "raid:${fixture.zone.id}:84"
                chunk.load() shouldBe true
                fixture.runDelayedTasks() shouldBe listOf(fixture.zone.rivalRaid.grenadePreviewTicks.toLong())
                blastSoil.type shouldBe Material.FARMLAND
                fixture.runDelayedTasks() shouldBe emptyList()
            }
            controller.clear(runtime, "incident_resolved")
            blastSoil.type shouldBe Material.FARMLAND
            blastSoil.getRelative(BlockFace.UP).type shouldBe Material.WHEAT
            fixture.raidDebrisVisuals.fragments.all { it.removed } shouldBe true
        } }
    }
    }
})

private fun plain(component: net.kyori.adventure.text.Component): String =
    PlainTextComponentSerializer.plainText().serialize(component)

private fun plantedLine(
    fixture: FarmIncidentScenarioFixture,
    xs: IntRange,
    z: Int,
): Set<FarmPlotPosition> = xs.mapTo(linkedSetOf()) { x ->
    FarmPlotPosition(fixture.world.name, x, 64, z).also { plot ->
        val soil = fixture.world.getBlockAt(plot.x, plot.y, plot.z)
        soil.type = Material.FARMLAND
        val crop = soil.getRelative(BlockFace.UP)
        crop.type = Material.WHEAT
        val age = crop.blockData as Ageable
        age.age = age.maximumAge
        crop.blockData = age
    }
}

private fun plantedField(
    fixture: FarmIncidentScenarioFixture,
    xs: IntRange,
    zs: IntRange,
): Set<FarmPlotPosition> = xs.flatMapTo(linkedSetOf()) { x ->
    zs.map { z -> FarmPlotPosition(fixture.world.name, x, 64, z) }
}.onEach { plot ->
    val soil = fixture.world.getBlockAt(plot.x, plot.y, plot.z)
    soil.type = Material.FARMLAND
    val crop = soil.getRelative(BlockFace.UP)
    crop.type = Material.WHEAT
    val age = crop.blockData as Ageable
    age.age = age.maximumAge
    crop.blockData = age
}

private fun actionState(type: FarmIncidentType, sequence: Long) = FarmShiftState(
    phase = FarmPhase.INCIDENT,
    sequence = sequence,
    placementSequence = 10,
    orderId = "bakery_supply",
    incidentType = type,
)

private fun raidRuntime(fixture: FarmIncidentScenarioFixture, sequence: Long) = fixture.runtime(
    actionState(FarmIncidentType.RIVAL_RAID, sequence),
    CuboidBounds(0, 0, 0, 35, 128, 63),
).smallRivalField()

private fun FarmRuntime.smallRivalField() = apply {
    settings = settings.copy(rivalRaid = settings.rivalRaid.copy(workerRadius = 16.0, workerFocusRadius = 16.0))
}

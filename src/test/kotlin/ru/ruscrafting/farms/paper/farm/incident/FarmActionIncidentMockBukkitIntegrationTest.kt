package ru.ruscrafting.farms.paper.farm.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.verify
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Ghast
import org.bukkit.entity.Hoglin
import org.bukkit.entity.Interaction
import org.bukkit.entity.Mob
import org.bukkit.entity.Snowball
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import kotlin.math.sqrt

class FarmActionIncidentMockBukkitIntegrationTest : FunSpec({
    test("boars acquire a distant player and trample real crops along their charge") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 6..36, 8..24)
            val runtime = fixture.runtime(actionState(FarmIncidentType.BOAR_BREAKOUT, 81))
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
            val runtime = fixture.runtime(actionState(FarmIncidentType.RIVAL_RAID, 82))
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
            (plannedField.minOf { it.x } <= 32) shouldBe true
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
            val occupiedSeats = fixture.world.entities.filterIsInstance<ArmorStand>().filter { it.passengers.isNotEmpty() }
            occupiedSeats shouldHaveSize fixture.zone.rivalRaid.maximumRiders
            val seatHeightOffsets = occupiedSeats.map { it.location.y - ghast.location.y }
            check(seatHeightOffsets.all { it <= -2.0 }) { "seat height offsets=$seatHeightOffsets" }
            val workers = fixture.world.entities.filterIsInstance<Mob>().filter { it !is Ghast && controller.owns(it) }
                .also { it shouldHaveSize fixture.zone.rivalRaid.workerCount }
                .onEach {
                    it.equipment.itemInMainHand.type shouldBe Material.TORCH
                    it.location.block.getRelative(BlockFace.DOWN).type shouldBe Material.FARMLAND
                }

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

            gunner.inventory.setItemInMainHand(grenade)
            controller.interact(PlayerInteractEntityEvent(gunner, workers.first(), EquipmentSlot.HAND)) shouldBe true
            val projectile = fixture.world.entities.filterIsInstance<Snowball>().single()
            val blastPlot = beds.first { it !in plannedField }
            val blastLocation = fixture.world.getBlockAt(blastPlot.x, blastPlot.y + 1, blastPlot.z).location.toCenterLocation()
            workers.take(2).forEach { it.teleport(blastLocation) }
            projectile.teleport(blastLocation)
            val blastSoil = fixture.world.getBlockAt(blastPlot.x, blastPlot.y, blastPlot.z)
            val authoritativeSoil = blastSoil.blockData.clone()
            val authoritativeCrop = blastSoil.getRelative(BlockFace.UP).blockData.clone()
            controller.onProjectileHit(ProjectileHitEvent(projectile, workers.first())) shouldBe true
            workers.take(2).all { it.isDead || it.health <= 0.0 } shouldBe true
            projectile.isValid shouldBe false
            runtime.state.incidentRequired shouldBe fixture.zone.rivalRaid.requiredKills
            blastSoil.blockData shouldBe authoritativeSoil
            blastSoil.getRelative(BlockFace.UP).blockData shouldBe authoritativeCrop
            val blastChanges = fixture.raidBlockPreviews.batches.flatMap { it.changes.entries }
            blastChanges.any { it.key == blastSoil.location && it.value.material == Material.AIR } shouldBe true
            blastChanges.any {
                it.key == blastSoil.getRelative(BlockFace.UP).location && it.value.material == Material.AIR
            } shouldBe true
            fixture.runDelayedTasks() shouldBe listOf(
                fixture.zone.rivalRaid.grenadeDebrisTicks.toLong(),
                fixture.zone.rivalRaid.grenadePreviewTicks.toLong(),
            )
            fixture.raidBlockPreviews.batches.last().changes.values.any { it.material == Material.FARMLAND } shouldBe true

            val seat = requireNotNull(gunner.vehicle)
            gunner.setPlayerTime(runtime.settings.rivalRaid.playerTime, false)
            controller.onDismount(EntityDismountEvent(gunner, seat)) shouldBe true
            gunner.leaveVehicle()
            fixture.runDelayedTasks() shouldBe listOf(1L)
            gunner.location.distanceSquared(fixture.location(receiving)) shouldBe 0.0
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
        } }
    }

    test("raid portal counts down by title, cancels on exit, and mounts only the current entry") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedField(fixture, 31..53, 31..53)
            val runtime = fixture.runtime(actionState(FarmIncidentType.RIVAL_RAID, 83))
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
                    MessageKey.FARM_RIVAL_RAID_PORTAL_COUNTDOWN,
                    match { values -> plain(values.getValue("seconds")) == "3" },
                    "raid_portal",
                )
            }
            fixture.runDelayedTasks() shouldBe listOf(20L)
            verify(exactly = 1) {
                fixture.port.showScreenTitle(
                    rider,
                    MessageKey.FARM_RIVAL_RAID_PORTAL_COUNTDOWN,
                    match { values -> plain(values.getValue("seconds")) == "2" },
                    "raid_portal",
                )
            }
            fixture.runDelayedTasks() shouldBe listOf(20L)
            verify(exactly = 1) {
                fixture.port.showScreenTitle(
                    rider,
                    MessageKey.FARM_RIVAL_RAID_PORTAL_COUNTDOWN,
                    match { values -> plain(values.getValue("seconds")) == "1" },
                    "raid_portal",
                )
            }
            fixture.runDelayedTasks() shouldBe listOf(20L)
            (rider.vehicle is ArmorStand) shouldBe true
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

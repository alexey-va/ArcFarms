package ru.ruscrafting.farms.paper.farm.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Ghast
import org.bukkit.entity.Hoglin
import org.bukkit.entity.Mob
import org.bukkit.entity.Snowball
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
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

            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            controller.ensure(runtime)
            val ghast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
            riders.forEach { rider ->
                controller.interact(PlayerInteractEntityEvent(rider, ghast, EquipmentSlot.HAND)) shouldBe true
            }

            repeat(20) {
                controller.update(runtime)
                controller.updateRaidMotion(runtime)
                fixture.night.updatePlayerTimes()
            }

            (ghast.velocity.length() > 0.0) shouldBe true
            val occupiedSeats = fixture.world.entities.filterIsInstance<ArmorStand>().filter { it.passengers.isNotEmpty() }
            occupiedSeats shouldHaveSize fixture.zone.rivalRaid.maximumRiders
            val workers = fixture.world.entities.filterIsInstance<Mob>().filter { it !is Ghast && controller.owns(it) }
                .also { it shouldHaveSize fixture.zone.rivalRaid.workerCount }
                .onEach {
                    it.equipment.itemInMainHand.type shouldBe Material.TORCH
                    it.location.block.getRelative(BlockFace.DOWN).type shouldBe Material.FARMLAND
                }

            val gunner = riders.first()
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
            workers.take(2).forEach { it.teleport(workers.first().location) }
            projectile.teleport(workers.first().location)
            controller.onProjectileHit(ProjectileHitEvent(projectile, workers.first())) shouldBe true
            workers.take(2).all { it.health < fixture.zone.rivalRaid.workerHealth } shouldBe true
            projectile.isValid shouldBe false
            runtime.state.incidentRequired shouldBe fixture.zone.rivalRaid.requiredKills
        } }
    }
})

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

package ru.ruscrafting.farms.paper.farm.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Ghast
import org.bukkit.entity.Hoglin
import org.bukkit.entity.Mob
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

    test("raid riders sit ahead of the ghast while it circles torch-lit workers with the configured gun model") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val beds = plantedLine(fixture, 10 until 34, 20)
            val runtime = fixture.runtime(actionState(FarmIncidentType.RIVAL_RAID, 82))
            val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
            val rival = FarmPointPosition(fixture.world.name, 42.5, 65.0, 42.5)
            val controller = fixture.actions(runtime, beds, receiving, rival)
            val gunner = fixture.paper.addPlayer("GhastGunner")
            gunner.teleport(fixture.location(receiving))

            controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
            controller.ensure(runtime)
            val ghast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
            controller.interact(PlayerInteractEntityEvent(gunner, ghast, EquipmentSlot.HAND)) shouldBe true

            repeat(180) {
                controller.update(runtime)
                fixture.night.updatePlayerTimes()
            }

            val horizontalRadius = sqrt(
                (ghast.location.x - rival.x) * (ghast.location.x - rival.x) +
                    (ghast.location.z - rival.z) * (ghast.location.z - rival.z),
            )
            horizontalRadius.shouldBeGreaterThan(fixture.zone.rivalRaid.orbitRadius - 1.0)
            val seat = fixture.world.entities.filterIsInstance<ArmorStand>().single { it.passengers.contains(gunner) }
            seat.location.distanceSquared(ghast.location).shouldBeGreaterThan(2.0)
            fixture.world.entities.filterIsInstance<Mob>().filter { it !is Ghast && controller.owns(it) }
                .also { it shouldHaveSize fixture.zone.rivalRaid.workerCount }
                .forEach { it.equipment.itemInMainHand.type shouldBe Material.TORCH }

            val gun = gunner.inventory.storageContents.filterNotNull().single { it.type == Material.CROSSBOW }
            gun.itemMeta.displayName()?.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            @Suppress("DEPRECATION")
            gun.itemMeta.customModelData shouldBe fixture.zone.rivalRaid.gunCustomModelData
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

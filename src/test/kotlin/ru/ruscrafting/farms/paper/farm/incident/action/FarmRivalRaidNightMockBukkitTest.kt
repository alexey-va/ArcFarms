package ru.ruscrafting.farms.paper.farm.incident.action

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Ghast
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import ru.ruscrafting.farms.paper.platform.FarmRaidFlightEnvelope
import ru.ruscrafting.farms.paper.platform.FarmRaidFlightSpace

class FarmRivalRaidNightMockBukkitTest : FunSpec({
    test("mounted rider keeps raid night outside home while a departing home observer returns to world time") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val raid = startRaid(fixture, 91)
            fixture.world.time = 6_000L
            val rider = fixture.paper.addPlayer("RemoteRaidGunner")
            val observer = fixture.paper.addPlayer("RaidHomeObserver")
            rider.teleport(fixture.location(raid.receiving))
            observer.teleport(fixture.location(FarmPointPosition(fixture.world.name, 12.5, 65.0, 10.5)))

            raid.controller.interact(PlayerInteractEntityEvent(rider, raid.ghast, EquipmentSlot.HAND)) shouldBe true
            raid.controller.update(raid.runtime)
            advanceTime(fixture, raid.runtime)
            rider.playerTime shouldBe raid.runtime.settings.rivalRaid.playerTime
            observer.playerTime shouldBe raid.runtime.settings.rivalRaid.playerTime

            val rivalAltitude = raid.rival.copy(y = raid.rival.y + raid.runtime.settings.rivalRaid.flightHeight)
            raid.ghast.teleport(fixture.location(rivalAltitude)) shouldBe true
            raid.controller.updateRaidMotion(raid.runtime)
            rider.vehicle shouldBe fixture.world.entities.filterIsInstance<org.bukkit.entity.ArmorStand>()
                .single(raid.controller::owns)
            raid.runtime.region.contains(rider.location) shouldBe false

            observer.teleport(fixture.location(rivalAltitude.copy(x = rivalAltitude.x + 8.0)))
            raid.controller.update(raid.runtime)
            advanceTime(fixture, raid.runtime)

            rider.playerTime shouldBe raid.runtime.settings.rivalRaid.playerTime
            observer.isPlayerTimeRelative() shouldBe true
            observer.getPlayerTimeOffset() shouldBe fixture.world.fullTime
            raid.controller.clear(raid.runtime, "night_departure_test")
        } }
    }

    test("participant quit and raid cleanup return every owned player clock to world time") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val raid = startRaid(fixture, 92)
            fixture.world.time = 6_000L
            val rider = fixture.paper.addPlayer("QuittingRaidGunner")
            val observer = fixture.paper.addPlayer("RaidCleanupObserver")
            rider.teleport(fixture.location(raid.receiving))
            observer.teleport(fixture.location(raid.receiving.copy(x = raid.receiving.x + 2.0)))

            raid.controller.interact(PlayerInteractEntityEvent(rider, raid.ghast, EquipmentSlot.HAND)) shouldBe true
            raid.controller.update(raid.runtime)
            advanceTime(fixture, raid.runtime)
            rider.playerTime shouldBe raid.runtime.settings.rivalRaid.playerTime
            observer.playerTime shouldBe raid.runtime.settings.rivalRaid.playerTime

            raid.controller.onQuit(rider)
            raid.controller.participantRuntime(rider) shouldBe null
            advanceTime(fixture, raid.runtime)
            rider.isPlayerTimeRelative() shouldBe true
            rider.getPlayerTimeOffset() shouldBe fixture.world.fullTime
            observer.playerTime shouldBe raid.runtime.settings.rivalRaid.playerTime

            raid.controller.clear(raid.runtime, "night_cleanup_test")
            advanceTime(fixture, raid.runtime)

            rider.isPlayerTimeRelative() shouldBe true
            rider.getPlayerTimeOffset() shouldBe fixture.world.fullTime
            observer.isPlayerTimeRelative() shouldBe true
            observer.getPlayerTimeOffset() shouldBe fixture.world.fullTime
        } }
    }
})

private data class RaidScenario(
    val runtime: FarmRuntime,
    val controller: FarmActionIncidentController,
    val receiving: FarmPointPosition,
    val rival: FarmPointPosition,
    val ghast: Ghast,
)

private fun startRaid(fixture: FarmIncidentScenarioFixture, sequence: Long): RaidScenario {
    // The fixture's default player query intentionally returns everyone; model
    // Paper's region-filtered audience so an off-region participant must be
    // retained by the raid owner itself.
    every { fixture.port.players(any()) } answers {
        val region = firstArg<ActivityRegion>()
        fixture.paper.server.onlinePlayers.filter { region.contains(it.location) }
    }
    val beds = plantedField(fixture, 76..87, 76..87)
    val runtime = fixture.runtime(actionState(sequence))
    runtime.settings = runtime.settings.copy(
        rivalRaid = runtime.settings.rivalRaid.copy(
            workerCount = 1,
            workerSpawnBatchSize = 1,
            workerPatrolBatchSize = 1,
            workerRadius = 16.0,
            workerFocusRadius = 16.0,
            workerRetireRadius = 16.0,
        ),
    )
    val receiving = FarmPointPosition(fixture.world.name, 10.5, 65.0, 10.5)
    val rival = FarmPointPosition(fixture.world.name, 80.5, 65.0, 80.5)
    val alwaysClear = object : FarmRaidFlightSpace {
        override fun isClearAt(
            world: org.bukkit.World,
            center: org.bukkit.Location,
            envelope: FarmRaidFlightEnvelope,
        ) = true

        override fun isClearSegment(
            world: org.bukkit.World,
            from: org.bukkit.Location,
            to: org.bukkit.Location,
            envelope: FarmRaidFlightEnvelope,
        ) = true
    }
    val controller = fixture.actions(runtime, beds, receiving, rival, flightSpace = alwaysClear)
    controller.initialize(runtime, FarmIncidentType.RIVAL_RAID) shouldBe FarmIncidentType.RIVAL_RAID
    controller.ensure(runtime)
    val ghast = fixture.world.entities.filterIsInstance<Ghast>().single(controller::owns)
    return RaidScenario(runtime, controller, receiving, rival, ghast)
}

private fun advanceTime(fixture: FarmIncidentScenarioFixture, runtime: FarmRuntime) {
    repeat(runtime.settings.rivalRaid.timeTransitionSeconds * 20) { fixture.night.updatePlayerTimes() }
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

private fun actionState(sequence: Long) = FarmShiftState(
    phase = FarmPhase.INCIDENT,
    sequence = sequence,
    placementSequence = 10,
    orderId = "bakery_supply",
    incidentType = FarmIncidentType.RIVAL_RAID,
)

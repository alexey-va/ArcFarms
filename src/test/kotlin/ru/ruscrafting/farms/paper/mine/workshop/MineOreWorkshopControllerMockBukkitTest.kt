package ru.ruscrafting.farms.paper.mine.workshop

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MineWorkingEngine
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.mineV2Settings
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

class MineOreWorkshopControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("authored stations keep one nonpersistent scene across repeated reconcile and reload") {
        val world = paper.server.addSimpleWorld("workshop")
        val plugin = paper.createSimplePlugin("MineOreWorkshopSceneTest")
        val runtime = activeRuntime(world)
        val controller = controller(plugin, runtime)

        controller.reconcile(runtime, now = 1_000L)
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 5
        controller.reconcile(runtime, now = 1_001L)
        workshopEntities(world) shouldHaveSize 10

        controller.cleanup("reload")
        workshopEntities(world) shouldHaveSize 0
        controller.reconcile(runtime, now = 1_002L)
        workshopEntities(world) shouldHaveSize 10
    }

    test("pickup is a carried ItemDisplay lease, repeated click is idempotent, and proximity drop advances LOAD") {
        val world = paper.server.addSimpleWorld("workshop")
        val plugin = paper.createSimplePlugin("MineOreWorkshopCarryTest")
        val runtime = activeRuntime(world, MineWorkingStage.LOAD)
        val controller = controller(plugin, runtime)
        controller.reconcile(runtime, now = 1_000L)
        val player = paper.server.addPlayer("WorkshopMiner")
        val points = stationPoints(world)
        player.teleport(points.getValue("ore"))
        val oreHitbox = workshopEntities(world).first { it.location.distanceSquared(points.getValue("ore")) < 1.0 }

        controller.onInteractEntity(PlayerInteractEntityEvent(player, oreHitbox, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        controller.onInteractEntity(PlayerInteractEntityEvent(player, oreHitbox, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 6
        val carried = workshopEntities(world).filterIsInstance<ItemDisplay>().single { display ->
            display.persistentDataContainer.keys.any { it.key == "mine_ore_workshop_carried" }
        }
        (carried.location.y > player.location.y + 0.5) shouldBe true
        (carried.location.y < player.eyeLocation.y) shouldBe true
        controller.tick(runtime, listOf(player), 1_050L)
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.LOAD
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 6

        player.teleport(points.getValue("crusher"))
        controller.tick(runtime, listOf(player), 1_100L)
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.CRUSH
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 5
    }

    test("walking around a different floor cannot turn the crusher") {
        val world = paper.server.addSimpleWorld("workshop")
        val plugin = paper.createSimplePlugin("MineOreWorkshopFloorTest")
        val runtime = activeRuntime(world, MineWorkingStage.CRUSH)
        val controller = controller(plugin, runtime)
        val player = paper.server.addPlayer("WrongFloorMiner")
        val center = stationPoints(world).getValue("crusher")
        repeat(4) {
            (0..8).forEach { index ->
                val angle = index * kotlin.math.PI / 4.0
                player.teleport(Location(world, center.x + 2.0 * kotlin.math.cos(angle), center.y - 10, center.z + 2.0 * kotlin.math.sin(angle)))
                controller.tick(runtime, listOf(player), 1_000L + index)
            }
        }
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.CRUSH
        runtime.state.incident!!.working!!.completed shouldBe emptySet()
    }

    test("final cleanup removes station hitboxes and transient carried displays") {
        val world = paper.server.addSimpleWorld("workshop")
        val plugin = paper.createSimplePlugin("MineOreWorkshopCleanupTest")
        val runtime = activeRuntime(world, MineWorkingStage.LOAD)
        val controller = controller(plugin, runtime)
        controller.reconcile(runtime, now = 1_000L)
        val player = paper.server.addPlayer("WorkshopCleaner")
        player.teleport(stationPoints(world).getValue("ore"))
        val oreHitbox = workshopEntities(world).first { it.location.distanceSquared(stationPoints(world).getValue("ore")) < 1.0 }
        controller.onInteractEntity(PlayerInteractEntityEvent(player, oreHitbox, EquipmentSlot.HAND), listOf(runtime))

        controller.cleanup("shutdown")
        workshopEntities(world) shouldHaveSize 0
    }

    test("moving authored points replaces station entities without duplicates") {
        val world = paper.server.addSimpleWorld("workshop_move")
        val plugin = paper.createSimplePlugin("MineOreWorkshopMoveTest")
        val runtime = activeRuntime(world)
        var points = stationPoints(world)
        val controller = controller(plugin, runtime, pointSource = { points })
        controller.reconcile(runtime, now = 1_000L)
        val oldOre = workshopEntities(world).minBy { it.location.distanceSquared(points.getValue("ore")) }

        points = points.mapValues { (_, point) -> point.clone().add(1.0, 0.0, 0.0) }
        controller.reconcile(runtime, now = 1_001L)

        workshopEntities(world) shouldHaveSize 10
        oldOre.isValid shouldBe false
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 5
        workshopEntities(world).filterIsInstance<ItemDisplay>().all { display ->
            display.location.x in 1.0..17.0
        } shouldBe true
    }

    test("three batches complete through carry, three physical crank laps, heat window, quench and shipping") {
        val world = paper.server.addSimpleWorld("workshop_full")
        val plugin = paper.createSimplePlugin("MineOreWorkshopFullFlowTest")
        val runtime = activeRuntime(world)
        val now = AtomicLong(1_000L)
        val points = stationPoints(world)
        val controller = controller(plugin, runtime, nowSource = now::get)
        val player = paper.server.addPlayer("WorkshopFullMiner")
        controller.reconcile(runtime, now = now.get())

        fun click(role: String) {
            player.teleport(points.getValue(role))
            val target = workshopEntities(world).minBy { it.location.distanceSquared(points.getValue(role)) }
            controller.onInteractEntity(PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        }

        fun lap() {
            val center = points.getValue("crusher")
            listOf(0, 45, 90, 135, 180, 225, 270, 315, 360).forEach { degrees ->
                val radians = Math.toRadians(degrees.toDouble())
                player.teleport(Location(world, center.x + 2.0 * kotlin.math.cos(radians), center.y, center.z + 2.0 * kotlin.math.sin(radians)))
                now.incrementAndGet()
                controller.tick(runtime, listOf(player), now.get())
            }
        }

        repeat(MineWorkingEngine.BATCHES) { batch ->
            click("ore")
            player.teleport(points.getValue("crusher"))
            now.incrementAndGet()
            controller.tick(runtime, listOf(player), now.get())
            repeat(MineWorkingEngine.CRUSH_STROKES) { lap() }
            runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.HEAT

            val heatStarted = runtime.state.incident!!.working!!.heatStartedAt
            now.set(heatStarted + MineWorkingEngine.HEAT_MILLIS - 1L)
            controller.tick(runtime, listOf(player), now.get())
            runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.HEAT
            now.set(heatStarted + MineWorkingEngine.HEAT_MILLIS)
            click("furnace")
            runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.SHIP

            click("output")
            player.teleport(points.getValue("shipping"))
            now.incrementAndGet()
            controller.tick(runtime, listOf(player), now.get())
            if (batch + 1 < MineWorkingEngine.BATCHES) {
                runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.LOAD
            }
        }

        runtime.state.incident shouldBe null
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 5
    }
})

private fun activeRuntime(world: WorldMock, stage: MineWorkingStage = MineWorkingStage.LOAD): MineRuntime {
    val position = WorksitePosition(world.name, 0, 64, 0)
    return MineRuntime(
        settings = mineV2Settings().copy(id = "authored_workshop"),
        region = CuboidActivityRegion(world, "authored_workshop", CuboidBounds(-20, 50, -20, 20, 90, 20)),
        cooldownMillis = 0L,
        state = MineShiftState(
            engineVersion = 2,
            phase = MinePhase.INCIDENT,
            sequence = 7L,
            incident = MineIncidentState(
                type = MineIncidentType.ORE_WORKSHOP,
                required = 18,
                objectiveNonce = 9L,
                startedAt = 1_000L,
                working = ru.ruscrafting.farms.domain.MineWorkingState(
                    placement = ru.ruscrafting.farms.domain.MineWorkingPlacement(position, 0, "authored-floor"),
                    stage = stage,
                ),
            ),
        ),
    )
}

private fun stationPoints(world: org.bukkit.World): Map<String, Location> = mapOf(
    "ore" to Location(world, 0.0, 64.0, 0.0),
    "crusher" to Location(world, 4.0, 64.0, 0.0),
    "furnace" to Location(world, 8.0, 64.0, 0.0),
    "output" to Location(world, 12.0, 64.0, 0.0),
    "shipping" to Location(world, 16.0, 64.0, 0.0),
)

private fun controller(
    plugin: org.bukkit.plugin.Plugin,
    runtime: MineRuntime,
    pointSource: () -> Map<String, Location> = { stationPoints(runtime.region.world) },
    nowSource: () -> Long = { 1_000L },
): MineOreWorkshopController {
    val incidents = mockk<MineIncidentCoordinator>()
    every { incidents.work(any(), any(), any(), any()) } answers {
        val incoming = arg<MineShiftState>(3)
        val incident = requireNotNull(incoming.incident)
        val progress = incident.progress + 1
        val next = if (progress >= incident.required) {
            incoming.copy(phase = MinePhase.COOLDOWN, incident = null)
        } else {
            incoming.copy(incident = incident.copy(progress = progress))
        }
        runtime.state = next
        EngineResult(next, accepted = true)
    }
    val access = mockk<WorksiteAccessPort>(relaxed = true)
    every { access.isAdminEditing(any()) } returns false
    every { access.hasAccess(any(), any()) } returns true
    every { access.allowInteraction(any(), any()) } returns true
    val state = mockk<WorksiteStatePort>(relaxed = true)
    every { state.persistAsync() } returns CompletableFuture.completedFuture(Unit)
    return MineOreWorkshopController(
        plugin,
        { pointSource() },
        incidents,
        access,
        state,
        clock = nowSource,
    )
}

private fun workshopEntities(world: WorldMock): List<Entity> = world.entities.filter { entity ->
    entity.persistentDataContainer.keys.any { it.key == "mine_ore_workshop_zone" || it.key == "mine_ore_workshop_carried" }
}

// The controller keeps these keys private in production; tests use the same
// namespace to select an authored station without depending on entity order.

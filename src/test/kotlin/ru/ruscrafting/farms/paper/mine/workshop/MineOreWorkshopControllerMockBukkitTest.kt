package ru.ruscrafting.farms.paper.mine.workshop

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
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
    lateinit var scheduler: TestTaskScheduler

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        scheduler = TestTaskScheduler()
        Tasks.install(scheduler)
    }
    afterEach {
        Tasks.reset()
        paper.close()
    }

    test("authored stations keep one nonpersistent scene across repeated reconcile and reload") {
        val world = paper.server.addSimpleWorld("workshop")
        val plugin = paper.createSimplePlugin("MineOreWorkshopSceneTest")
        val runtime = activeRuntime(world)
        val controller = controller(plugin, runtime)

        controller.reconcile(runtime, now = 1_000L)
        workshopInteractions(world) shouldHaveSize 8
        controller.reconcile(runtime, now = 1_001L)
        workshopInteractions(world) shouldHaveSize 8

        controller.cleanup("reload")
        workshopEntities(world) shouldHaveSize 0
        controller.reconcile(runtime, now = 1_002L)
        workshopInteractions(world) shouldHaveSize 8
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
        val oreHitbox = roleEntity(world, "ore")

        controller.onInteractEntity(PlayerInteractEntityEvent(player, oreHitbox, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        controller.onInteractEntity(PlayerInteractEntityEvent(player, oreHitbox, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 1
        val carried = workshopEntities(world).filterIsInstance<ItemDisplay>().single { display ->
            display.persistentDataContainer.keys.any { it.key == "mine_ore_workshop_carried" }
        }
        (carried.location.y > player.location.y + 0.5) shouldBe true
        (carried.location.y < player.eyeLocation.y) shouldBe true
        controller.tick(runtime, listOf(player), 1_050L)
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.LOAD
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 1

        player.teleport(points.getValue("crusher"))
        controller.tick(runtime, listOf(player), 1_100L)
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.CRUSH
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 0
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

    test("crusher rejects the wrong lever and accepts only feed drive release") {
        val world = paper.server.addSimpleWorld("workshop_controls")
        val plugin = paper.createSimplePlugin("MineOreWorkshopControlsTest")
        val runtime = activeRuntime(world, MineWorkingStage.CRUSH)
        val now = AtomicLong(1_000L)
        val controller = controller(plugin, runtime, nowSource = now::get)
        val player = paper.server.addPlayer("WorkshopOperator")
        controller.reconcile(runtime, now = 1_000L)

        fun clickControl(role: String) {
            val target = roleEntity(world, role)
            player.teleport(target.location.clone().add(0.0, 0.8, 0.0))
            controller.onInteractEntity(PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        }

        clickControl("crusher_drive")
        runtime.state.incident!!.working!!.completed shouldBe emptySet()
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.CRUSH

        clickControl("crusher_feed")
        runtime.state.incident!!.working!!.completed shouldBe setOf(0)
        clickControl("crusher_feed")
        runtime.state.incident!!.working!!.completed shouldBe setOf(0)
        clickControl("crusher_drive")
        runtime.state.incident!!.working!!.completed shouldBe setOf(0, 1)
        clickControl("crusher_release")
        runtime.state.incident!!.working!!.completed shouldBe setOf(0, 1)
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.CRUSH
        now.addAndGet(MineWorkingEngine.HEAT_MILLIS)
        controller.reconcile(runtime, now = now.get())
        clickControl("crusher_release")
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.HEAT
        runtime.state.incident!!.working!!.completed shouldBe emptySet()
    }

    test("persisted crusher drive restarts its transient cycle after scene reload") {
        val world = paper.server.addSimpleWorld("workshop_restart")
        val plugin = paper.createSimplePlugin("MineOreWorkshopRestartTest")
        val runtime = activeRuntime(world, MineWorkingStage.CRUSH).also { current ->
            val incident = requireNotNull(current.state.incident)
            current.state = current.state.copy(incident = incident.copy(
                working = requireNotNull(incident.working).copy(completed = setOf(0, 1)),
            ))
        }
        val now = AtomicLong(1_000L)
        val controller = controller(plugin, runtime, nowSource = now::get)
        val player = paper.server.addPlayer("WorkshopRestartMiner")

        controller.reconcile(runtime, now = now.get())
        controller.cleanup("reload")
        now.set(5_000L)
        controller.reconcile(runtime, now = now.get())
        val release = roleEntity(world, "crusher_release")
        player.teleport(release.location.clone().add(0.0, 0.8, 0.0))
        controller.onInteractEntity(PlayerInteractEntityEvent(player, release, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.CRUSH
        runtime.state.incident!!.working!!.completed shouldBe setOf(0, 1)

        now.set(9_000L)
        controller.reconcile(runtime, now = now.get())
        val readyRelease = roleEntity(world, "crusher_release")
        player.teleport(readyRelease.location.clone().add(0.0, 0.8, 0.0))
        controller.onInteractEntity(PlayerInteractEntityEvent(player, readyRelease, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.HEAT
    }

    test("final cleanup removes station hitboxes and transient carried displays") {
        val world = paper.server.addSimpleWorld("workshop")
        val plugin = paper.createSimplePlugin("MineOreWorkshopCleanupTest")
        val runtime = activeRuntime(world, MineWorkingStage.LOAD)
        val controller = controller(plugin, runtime)
        controller.reconcile(runtime, now = 1_000L)
        val player = paper.server.addPlayer("WorkshopCleaner")
        player.teleport(stationPoints(world).getValue("ore"))
        val oreHitbox = roleEntity(world, "ore")
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
        val oldOre = roleEntity(world, "ore")

        points = points.mapValues { (_, point) -> point.clone().add(1.0, 0.0, 0.0) }
        controller.reconcile(runtime, now = 1_001L)

        workshopInteractions(world) shouldHaveSize 8
        oldOre.isValid shouldBe false
        listOf("ore", "crusher", "furnace", "output", "shipping").all { role ->
            roleEntity(world, role).location.x == points.getValue(role).x
        } shouldBe true
    }

    test("three batches complete through carry, ordered lever steps, heat window, quench and shipping") {
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
            val target = roleEntity(world, role)
            controller.onInteractEntity(PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        }

        fun clickControl(role: String) {
            val target = roleEntity(world, role)
            player.teleport(target.location.clone().add(0.0, 0.8, 0.0))
            now.incrementAndGet()
            controller.onInteractEntity(PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        }

        repeat(MineWorkingEngine.BATCHES) { batch ->
            click("ore")
            player.teleport(points.getValue("crusher"))
            now.incrementAndGet()
            controller.tick(runtime, listOf(player), now.get())
            clickControl("crusher_feed")
            clickControl("crusher_drive")
            now.addAndGet(MineWorkingEngine.HEAT_MILLIS)
            controller.reconcile(runtime, now = now.get())
            clickControl("crusher_release")
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
        workshopInteractions(world) shouldHaveSize 8
    }

    test("niche station points keep the south approach clear") {
        val world = paper.server.addSimpleWorld("workshop_bounds")
        val points = stationPoints(world)

        points.values.all { point ->
            point.x in 35.0..53.0 && point.y in 111.0..119.0 && point.z in 13.0..22.0
        } shouldBe true
        points.values.all { it.z >= 17.0 } shouldBe true
    }
})

private fun activeRuntime(world: WorldMock, stage: MineWorkingStage = MineWorkingStage.LOAD): MineRuntime {
    val position = WorksitePosition(world.name, 40, 111, 17)
    return MineRuntime(
        settings = mineV2Settings().copy(id = "authored_workshop"),
        region = CuboidActivityRegion(world, "authored_workshop", CuboidBounds(30, 100, 10, 60, 130, 30)),
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
    "ore" to Location(world, 36.5, 111.0, 20.5),
    "crusher" to Location(world, 40.5, 111.0, 17.5),
    "furnace" to Location(world, 46.5, 111.0, 17.5),
    "output" to Location(world, 50.5, 111.0, 17.5),
    "shipping" to Location(world, 51.5, 111.0, 21.5),
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

private fun workshopInteractions(world: WorldMock): List<Interaction> = workshopEntities(world).filterIsInstance<Interaction>()

private fun roleEntity(world: WorldMock, role: String): Interaction = workshopInteractions(world).single { entity ->
    val key = entity.persistentDataContainer.keys.firstOrNull { it.key == "mine_ore_workshop_role" }
    key != null && entity.persistentDataContainer.get(key, PersistentDataType.STRING) == role
}

// The controller keeps these keys private in production; tests use the same
// namespace to select an authored station without depending on entity order.

package ru.ruscrafting.farms.paper.mine.workshop

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import ru.arc.paper.display.PacketBlockDisplay
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
import kotlin.math.abs

class MineOreWorkshopControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var scheduler: TestTaskScheduler

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        scheduler = TestTaskScheduler()
        Tasks.install(scheduler)
        mockkConstructor(MineWorkshopMachines::class)
        every { anyConstructed<MineWorkshopMachines>().create(any(), any()) } answers {
            fixtureMachine(firstArg<String>(), secondArg<Location>())
        }
    }

    afterEach {
        unmockkConstructor(MineWorkshopMachines::class)
        Tasks.reset()
        paper.close()
    }

    test("authored stations keep one scene, expose nine entities, and leave inactive bodies nonblocking") {
        val world = paper.server.addSimpleWorld("workshop")
        val plugin = paper.createSimplePlugin("MineOreWorkshopSceneTest")
        val runtime = activeRuntime(world)
        val controller = controller(plugin, runtime)

        controller.reconcile(runtime, now = 1_000L)
        workshopInteractions(world) shouldHaveSize 9
        roleEntity(world, "furnace").interactionWidth shouldBe 0f

        controller.reconcile(runtime, now = 1_001L)
        workshopInteractions(world) shouldHaveSize 9

        controller.cleanup("reload")
        workshopEntities(world) shouldHaveSize 0
        controller.reconcile(runtime, now = 1_002L)
        workshopInteractions(world) shouldHaveSize 9
    }

    test("pickup is idempotent, feed is an explicit front-hopper click, and guidance names it") {
        val world = paper.server.addSimpleWorld("workshop_load")
        val plugin = paper.createSimplePlugin("MineOreWorkshopLoadTest")
        val runtime = activeRuntime(world)
        val controller = controller(plugin, runtime)
        val player = paper.server.addPlayer("WorkshopMiner")
        val points = stationPoints(world)
        val plain = PlainTextComponentSerializer.plainText()

        controller.reconcile(runtime, now = 1_000L)
        plain.serialize(requireNotNull(controller.guidanceHint(runtime, player, 1_000L))) shouldBe "load-pickup"

        fun click(role: String) {
            val target = roleEntity(world, role)
            player.teleport(target.location.clone().add(0.0, 0.8, 0.0))
            controller.onInteractEntity(PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        }

        fun clickControl(role: String) {
            val target = roleEntity(world, role)
            player.teleport(target.location.clone().add(0.0, 0.8, 0.0))
            controller.onInteractEntity(PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        }

        click("ore")
        click("ore")
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 1
        plain.serialize(requireNotNull(controller.guidanceHint(runtime, player, 1_000L))) shouldBe "load-deliver"

        player.teleport(points.getValue("crusher"))
        controller.tick(runtime, listOf(player), 1_050L)
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.LOAD
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 1

        click("crusher")
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.LOAD

        val feed = roleEntity(world, "crusher_feed")
        (abs(feed.location.z - points.getValue("crusher").z - 2.0) < .01) shouldBe true
        clickControl("crusher_feed")
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.CRUSH
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 0
    }

    test("out-of-area and stale sequence leases are removed without advancing work") {
        val world = paper.server.addSimpleWorld("workshop_leases")
        val plugin = paper.createSimplePlugin("MineOreWorkshopLeaseTest")
        val runtime = activeRuntime(world)
        val controller = controller(plugin, runtime)
        val player = paper.server.addPlayer("WorkshopLeaseMiner")
        val points = stationPoints(world)

        controller.reconcile(runtime, now = 1_000L)
        val ore = roleEntity(world, "ore")
        player.teleport(ore.location.clone().add(0.0, 0.8, 0.0))
        controller.onInteractEntity(PlayerInteractEntityEvent(player, ore, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 1

        player.teleport(Location(world, 100.0, 111.0, 100.0))
        controller.tick(runtime, listOf(player), 1_050L)
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 0
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.LOAD

        player.teleport(points.getValue("ore"))
        val source = roleEntity(world, "ore")
        controller.onInteractEntity(PlayerInteractEntityEvent(player, source, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        runtime.state = runtime.state.copy(incident = runtime.state.incident!!.copy(objectiveNonce = 10L))
        controller.tick(runtime, listOf(player), 1_100L)
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 0
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.LOAD
    }

    test("one drive click starts a four-second grind and a three-second transfer") {
        val world = paper.server.addSimpleWorld("workshop_crusher")
        val plugin = paper.createSimplePlugin("MineOreWorkshopCrusherTest")
        val runtime = activeRuntime(world, MineWorkingStage.CRUSH)
        val now = AtomicLong(1_000L)
        val calls = mutableListOf<MineShiftState>()
        val controller = controller(plugin, runtime, nowSource = now::get, workCalls = calls)
        val player = paper.server.addPlayer("WorkshopOperator")
        controller.reconcile(runtime, now = now.get())

        fun clickControl(role: String) {
            val target = roleEntity(world, role)
            player.teleport(target.location.clone().add(0.0, 0.8, 0.0))
            controller.onInteractEntity(PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        }

        clickControl("furnace_air")
        runtime.state.incident!!.working!!.completed shouldBe emptySet()
        clickControl("crusher_drive")
        runtime.state.incident!!.working!!.completed shouldBe setOf(0)
        clickControl("crusher_drive")
        runtime.state.incident!!.working!!.completed shouldBe setOf(0)

        now.set(4_999L)
        controller.tick(runtime, listOf(player), now.get())
        runtime.state.incident!!.working!!.completed shouldBe setOf(0)

        now.set(5_000L)
        controller.tick(runtime, listOf(player), now.get())
        runtime.state.incident!!.working!!.completed shouldBe setOf(0, 1)
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.CRUSH

        now.set(7_999L)
        controller.tick(runtime, listOf(player), now.get())
        runtime.state.incident!!.working!!.completed shouldBe setOf(0, 1)

        now.set(8_000L)
        controller.tick(runtime, listOf(player), now.get())
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.HEAT
        runtime.state.incident!!.working!!.completed shouldBe emptySet()
        calls shouldHaveSize 3
    }

    test("recovered crusher state replays only the three-second transfer") {
        val world = paper.server.addSimpleWorld("workshop_recovery")
        val plugin = paper.createSimplePlugin("MineOreWorkshopRecoveryTest")
        val runtime = activeRuntime(world, MineWorkingStage.CRUSH, completed = setOf(0, 1))
        val now = AtomicLong(1_000L)
        val calls = mutableListOf<MineShiftState>()
        val controller = controller(plugin, runtime, nowSource = now::get, workCalls = calls)
        val player = paper.server.addPlayer("WorkshopRecoveryMiner")
        player.teleport(stationPoints(world).getValue("crusher"))

        controller.reconcile(runtime, now = now.get())
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.CRUSH
        runtime.state.incident!!.working!!.completed shouldBe setOf(0, 1)
        calls shouldHaveSize 0

        now.set(3_999L)
        controller.tick(runtime, listOf(player), now.get())
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.CRUSH
        runtime.state.incident!!.working!!.completed shouldBe setOf(0, 1)
        calls shouldHaveSize 0

        now.set(4_000L)
        controller.tick(runtime, listOf(player), now.get())
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.HEAT
        calls shouldHaveSize 1

        now.set(7_000L)
        controller.tick(runtime, listOf(player), now.get())
        calls shouldHaveSize 1
    }

    test("furnace air control reaches ready, has no expiring window, and starts the pour") {
        val world = paper.server.addSimpleWorld("workshop_heat")
        val plugin = paper.createSimplePlugin("MineOreWorkshopHeatTest")
        val runtime = activeRuntime(world, MineWorkingStage.HEAT)
        val now = AtomicLong(1_000L)
        val calls = mutableListOf<MineShiftState>()
        val controller = controller(plugin, runtime, nowSource = now::get, workCalls = calls)
        val player = paper.server.addPlayer("WorkshopHeater")
        player.teleport(stationPoints(world).getValue("furnace"))
        controller.reconcile(runtime, now = now.get())
        val heatStart = now.get()
        var closed = false
        var reopened = false
        var ready = false

        fun clickControl(role: String) {
            val target = roleEntity(world, role)
            player.teleport(target.location.clone().add(0.0, 0.8, 0.0))
            controller.onInteractEntity(PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        }

        for (step in 0 until 300) {
            now.addAndGet(50L)
            controller.tick(runtime, listOf(player), now.get())
            if (!closed && now.get() - heatStart >= 4_300L) {
                clickControl("furnace_air")
                closed = true
            }
            if (closed && !reopened && now.get() - heatStart >= 7_000L) {
                clickControl("furnace_air")
                reopened = true
            }
            if (roleEntity(world, "furnace_tap").interactionHeight > 0f) {
                ready = true
                break
            }
        }

        ready shouldBe true
        (now.get() - heatStart <= 9_000L) shouldBe true
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.HEAT

        now.addAndGet(30_000L)
        controller.tick(runtime, listOf(player), now.get())
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.HEAT
        roleEntity(world, "furnace_tap").interactionHeight shouldBe .85f

        clickControl("furnace_tap")
        runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.SHIP
        calls shouldHaveSize 1
    }

    test("three batches require the five-second pour before billet collection and contribute eighteen steps") {
        val world = paper.server.addSimpleWorld("workshop_full")
        val plugin = paper.createSimplePlugin("MineOreWorkshopFullFlowTest")
        val runtime = activeRuntime(world)
        val now = AtomicLong(1_000L)
        val points = stationPoints(world)
        val calls = mutableListOf<MineShiftState>()
        val controller = controller(plugin, runtime, nowSource = now::get, workCalls = calls)
        val player = paper.server.addPlayer("WorkshopFullMiner")
        controller.reconcile(runtime, now = now.get())

        fun click(role: String) {
            val target = roleEntity(world, role)
            player.teleport(target.location.clone().add(0.0, 0.8, 0.0))
            controller.onInteractEntity(PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        }

        fun clickControl(role: String) {
            val target = roleEntity(world, role)
            player.teleport(target.location.clone().add(0.0, 0.8, 0.0))
            controller.onInteractEntity(PlayerInteractEntityEvent(player, target, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        }

        fun finishHeat() {
            val heatStart = now.get()
            var closed = false
            var reopened = false
            var ready = false
            for (step in 0 until 300) {
                now.addAndGet(50L)
                controller.tick(runtime, listOf(player), now.get())
                if (!closed && now.get() - heatStart >= 4_300L) {
                    clickControl("furnace_air")
                    closed = true
                }
                if (closed && !reopened && now.get() - heatStart >= 7_000L) {
                    clickControl("furnace_air")
                    reopened = true
                }
                if (roleEntity(world, "furnace_tap").interactionHeight > 0f) {
                    ready = true
                    break
                }
            }
            ready shouldBe true
            clickControl("furnace_tap")
        }

        repeat(MineWorkingEngine.BATCHES) { batch ->
            click("ore")
            player.teleport(points.getValue("crusher"))
            now.addAndGet(50L)
            controller.tick(runtime, listOf(player), now.get())
            runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.LOAD
            clickControl("crusher_feed")
            clickControl("crusher_drive")

            now.addAndGet(3_999L)
            controller.tick(runtime, listOf(player), now.get())
            runtime.state.incident!!.working!!.completed shouldBe setOf(0)
            now.addAndGet(1L)
            controller.tick(runtime, listOf(player), now.get())
            runtime.state.incident!!.working!!.completed shouldBe setOf(0, 1)
            now.addAndGet(2_999L)
            controller.tick(runtime, listOf(player), now.get())
            runtime.state.incident!!.working!!.completed shouldBe setOf(0, 1)
            now.addAndGet(1L)
            controller.tick(runtime, listOf(player), now.get())
            runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.HEAT
            finishHeat()
            runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.SHIP
            controller.tick(runtime, listOf(player), now.get())

            now.addAndGet(4_999L)
            controller.tick(runtime, listOf(player), now.get())
            click("output")
            runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.SHIP
            now.addAndGet(1L)
            click("output")
            if (batch + 1 < MineWorkingEngine.BATCHES) {
                runtime.state.incident!!.working!!.stage shouldBe MineWorkingStage.LOAD
            }
        }

        runtime.state.incident shouldBe null
        calls shouldHaveSize 18
    }

    test("cleanup removes all station hitboxes and transient cargo") {
        val world = paper.server.addSimpleWorld("workshop_cleanup")
        val plugin = paper.createSimplePlugin("MineOreWorkshopCleanupTest")
        val runtime = activeRuntime(world)
        val controller = controller(plugin, runtime)
        val player = paper.server.addPlayer("WorkshopCleaner")

        controller.reconcile(runtime, now = 1_000L)
        val ore = roleEntity(world, "ore")
        player.teleport(ore.location.clone().add(0.0, 0.8, 0.0))
        controller.onInteractEntity(PlayerInteractEntityEvent(player, ore, EquipmentSlot.HAND), listOf(runtime)) shouldBe true
        workshopEntities(world).filterIsInstance<ItemDisplay>() shouldHaveSize 1

        controller.cleanup("shutdown")
        workshopEntities(world) shouldHaveSize 0
    }

    test("moving authored points replaces entities and all station points stay in the niche") {
        val world = paper.server.addSimpleWorld("workshop_bounds")
        val plugin = paper.createSimplePlugin("MineOreWorkshopBoundsTest")
        val runtime = activeRuntime(world)
        var points = stationPoints(world)
        val controller = controller(plugin, runtime, pointSource = { points })

        controller.reconcile(runtime, now = 1_000L)
        val oldOre = roleEntity(world, "ore")
        workshopInteractions(world) shouldHaveSize 9

        points = points.mapValues { (_, point) -> point.clone().add(1.0, 0.0, 0.0) }
        controller.reconcile(runtime, now = 1_001L)
        workshopInteractions(world) shouldHaveSize 9
        oldOre.isValid shouldBe false
        listOf("ore", "crusher", "furnace", "output", "shipping").all { role ->
            roleEntity(world, role).location.x == points.getValue(role).x
        } shouldBe true

        points.values.all { point ->
            point.x in 35.0..53.0 && point.y in 111.0..119.0 && point.z in 13.0..22.0
        } shouldBe true
        points.values.all { it.z >= 17.0 } shouldBe true
    }
})

private fun activeRuntime(
    world: WorldMock,
    stage: MineWorkingStage = MineWorkingStage.LOAD,
    completed: Set<Int> = emptySet(),
    batch: Int = 0,
): MineRuntime {
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
                    completed = completed,
                    batch = batch,
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
    workCalls: MutableList<MineShiftState> = mutableListOf(),
): MineOreWorkshopController {
    val incidents = mockk<MineIncidentCoordinator>()
    every { incidents.work(any(), any(), any(), any()) } answers {
        val incoming = arg<MineShiftState>(3)
        workCalls += incoming
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

private fun fixtureMachine(role: String, at: Location): MineWorkshopMachines.Machine {
    val body = mockk<PacketBlockDisplay>(relaxed = true)
    every { body.isValid } returns true
    every { body.location } returns at.clone()
    val controls = when (role) {
        "crusher" -> mapOf(
            "crusher_feed" to at.clone().add(0.0, 1.08, 2.0),
            "crusher_drive" to at.clone().add(1.35, 1.08, 2.0),
        )
        "furnace" -> mapOf(
            "furnace_air" to at.clone().add(-1.0, 1.0, 1.4),
            "furnace_tap" to at.clone().add(1.0, 1.0, 1.4),
        )
        else -> emptyMap()
    }
    return mockk<MineWorkshopMachines.Machine>(relaxed = true).also { machine ->
        every { machine.body } returns body
        every { machine.controls } returns controls
        every { machine.interactionCenter } returns null
    }
}

package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.MineWorkingState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeFactory
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.mineV2Settings
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import ru.ruscrafting.farms.paper.mine.testMineComponentGraph
import ru.ruscrafting.farms.paper.mine.ImmediateMineJournal
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteExpeditionTravel
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneBlockDataDecoder
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneChunkRetention
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneOwner
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorksiteSceneCodec
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MineWorkingLifecycleMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("migration and reconfigure preserve working stage/progress while dropping transient cargo leases") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val plugin = paper.createSimplePlugin("MineWorkingLifecycleMigrationTest")
        val base = mineV2Settings()
        val settings = base.copy(orders = base.orders.map { order ->
            order.copy(incidentTypes = listOf(
                MineIncidentType.TUNNEL_DRIVE,
                MineIncidentType.RAIL_EXTENSION,
                MineIncidentType.ORE_WORKSHOP,
            ))
        })
        val placement = legacyWorkingPlacement(
            WorksitePosition(world.name, 2, 64, 2), direction = 0, floorId = "floor-a",
        )
        val working = MineWorkingState(
            placement = placement,
            stage = MineWorkingStage.SUPPORT,
            completed = setOf(0, 2),
            batch = 1,
        )
        val leases = mapOf(
            "supports_123" to UUID.fromString("00000000-0000-0000-0000-000000000123"),
            // This models cargo held by a player who is offline during restart.
            "ore_1" to UUID.fromString("00000000-0000-0000-0000-000000000456"),
        )
        val persisted = MineShiftState(
            engineVersion = 2,
            phase = MinePhase.INCIDENT,
            sequence = 41,
            orderId = "ore_run",
            incidentSchedule = listOf(MineIncidentType.TUNNEL_DRIVE),
            incident = MineIncidentState(
                type = MineIncidentType.TUNNEL_DRIVE,
                required = 9,
                progress = 3,
                objectiveNonce = 17,
                startedAt = 1_000,
                serviceLeases = leases,
                working = working,
            ),
        )
        val graph = testMineComponentGraph(
            plugin = plugin,
            regions = CuboidRegionGateway(),
            port = immediateMinePort(),
            clock = { 2_000L },
            journal = ImmediateMineJournal(),
        )
        val persistedIncident = requireNotNull(persisted.incident)

        graph.module.rebuild(listOf(settings), mapOf(settings.id to persisted), 5_000L)
        val rebuilt = graph.registry.byId(settings.id)!!
        rebuilt.state.phase shouldBe MinePhase.INCIDENT
        rebuilt.state.sequence shouldBe persisted.sequence
        val rebuiltIncident = requireNotNull(rebuilt.state.incident)
        rebuiltIncident.progress shouldBe persistedIncident.progress
        rebuiltIncident.working shouldBe working
        rebuiltIncident.serviceLeases shouldBe emptyMap()

        graph.module.reconfigure(listOf(settings), graph.module.states(), 5_000L)
        val reconfigured = graph.registry.byId(settings.id)!!
        reconfigured.state.sequence shouldBe persisted.sequence
        val reconfiguredIncident = requireNotNull(reconfigured.state.incident)
        reconfiguredIncident.progress shouldBe 3
        reconfiguredIncident.working shouldBe working
        reconfiguredIncident.serviceLeases shouldBe emptyMap()

        // The migration guard is deliberately scoped to a working incident.
        val ordinary = MineRuntimeFactory.migrate(
            settings,
            persisted.copy(incident = persistedIncident.copy(working = null)),
        )
        ordinary.incident!!.serviceLeases shouldBe leases
    }

    test("beforeReload releases a pending working save and ignores its stale callback") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("ReloadMiner")
        val runtime = lifecycleRuntime(world)
        val registry = MineRuntimeRegistry().also { it.replace(listOf(runtime)) }
        val pending = CompletableFuture<Unit>()
        val token = mockk<RuntimeTaskSupervisor.Token>()
        val placement = mockk<MineWorkingPlacementService>(relaxed = true)
        val scene = mockk<MineWorkingWorld>(relaxed = true)
        val incidents = mockk<MineIncidentCoordinator>(relaxed = true)
        val equipment = mockk<MineWorkingEquipment>(relaxed = true)
        val presentation = mockk<MineWorkingPresentation>(relaxed = true)
        val travel = mockk<WorksiteExpeditionTravel>(relaxed = true)
        val access = mockk<WorksiteAccessPort>(relaxed = true)
        val state = mockk<WorksiteStatePort>(relaxed = true)
        val tasks = mockk<WorksiteTaskPort>(relaxed = true)
        every { incidents.work(any(), any(), any(), any()) } returns EngineResult(runtime.state, true)
        every { state.persistAsync() } returns pending
        every { tasks.lifecycleToken() } returns token
        every { tasks.runSync(token, any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        val controller = MineWorkingController(
            registry, placement, scene, incidents, equipment, presentation, travel, access, state, tasks, { 2_000L }, drive = mockk(relaxed = true),
        )

        val advance = MineWorkingController::class.java.getDeclaredMethod(
            "advance", MineRuntime::class.java, Player::class.java,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
        ).also { it.isAccessible = true }
        advance.invoke(controller, runtime, player, 0, 2, false)
        controller.transitioning(runtime) shouldBe true
        controller.blocksOreSupply(runtime) shouldBe true

        controller.beforeReload()
        controller.transitioning(runtime) shouldBe false
        runtime.state = runtime.state.copy(sequence = runtime.state.sequence + 1)
        pending.complete(Unit)
        controller.blocksOreSupply(runtime) shouldBe false

        // The callback belongs to the discarded lifecycle and must not project old progress.
        verify(exactly = 0) { scene.project(runtime) }
        verify(exactly = 1) { placement.clear() }
    }

    test("module beforeReload routes to the working owner and releases its transition blocker") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val graph = testMineComponentGraph(
            plugin = paper.createSimplePlugin("MineWorkingModuleReloadTest"),
            regions = CuboidRegionGateway(),
            port = immediateMinePort(),
            clock = { 2_000L },
            journal = ImmediateMineJournal(),
        )
        graph.module.rebuild(listOf(mineV2Settings()), emptyMap(), 5_000L)
        val runtime = graph.registry.byId("old_shafts")!!
        val pendingField = MineWorkingController::class.java.getDeclaredField("pendingSaves")
            .also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(graph.workings) as MutableSet<String>
        pending += runtime.settings.id
        graph.workings.transitioning(runtime) shouldBe true

        graph.module.beforeReload("test_reload")

        graph.workings.transitioning(runtime) shouldBe false
    }

    test("walking through a ready working entrance is not cancelled and records the destination") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("WalkingMiner")
        val from = Location(world, 4.5, 65.0, 4.5)
        val to = Location(world, 5.5, 65.0, 5.5)
        player.teleport(from)
        val runtime = lifecycleRuntime(world)
        val registry = MineRuntimeRegistry().also { it.replace(listOf(runtime)) }
        val placement = mockk<MineWorkingPlacementService>(relaxed = true)
        val sceneWorld = mockk<MineWorkingWorld>(relaxed = true)
        val scene = mockk<MineWorkingScene>(relaxed = true)
        val incidents = mockk<MineIncidentCoordinator>(relaxed = true)
        val equipment = mockk<MineWorkingEquipment>(relaxed = true)
        val presentation = mockk<MineWorkingPresentation>(relaxed = true)
        val travel = mockk<WorksiteExpeditionTravel>(relaxed = true)
        val access = mockk<WorksiteAccessPort>(relaxed = true)
        val state = mockk<WorksiteStatePort>(relaxed = true)
        val tasks = mockk<WorksiteTaskPort>(relaxed = true)
        every { sceneWorld.scene(runtime) } returns scene
        every { sceneWorld.isReady(runtime) } returns true
        every { scene.inside(to) } returns true
        every { scene.surface() } returns from
        every { travel.isAuthorized(player, to) } returns false
        every { travel.record(player) } returns null
        every { travel.retains(player) } returns false
        every { access.isAdminEditing(player) } returns false
        every { access.hasAccess(player, runtime.settings.permission) } returns true
        val request = slot<WorksiteExpeditionTravel.EntryRequest>()
        every { travel.enterOnFoot(capture(request), any()) } answers { }
        val controller = MineWorkingController(
            registry, placement, sceneWorld, incidents, equipment, presentation, travel, access, state, tasks, { 2_000L }, drive = mockk(relaxed = true),
        )

        val event = PlayerMoveEvent(player, from, to)
        controller.guardMovement(event) shouldBe false
        event.isCancelled shouldBe false
        request.captured.destination.blockX shouldBe to.blockX
        request.captured.destination.blockY shouldBe to.blockY
        request.captured.destination.blockZ shouldBe to.blockZ
        verify(exactly = 1) { travel.enterOnFoot(any(), any()) }
    }

    test("completed working remains walkable during grace and restores after everyone leaves") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("GraceMiner")
        player.teleport(Location(world, 100.5, 65.0, 100.5))
        val runtime = lifecycleRuntime(world).also { it.state = it.state.copy(phase = MinePhase.MINING, incident = null) }
        val registry = MineRuntimeRegistry().also { it.replace(listOf(runtime)) }
        val placement = mockk<MineWorkingPlacementService>(relaxed = true)
        val sceneWorld = mockk<MineWorkingWorld>(relaxed = true)
        val scene = mockk<MineWorkingScene>(relaxed = true)
        val blocks = mockk<WorksitePreparedScene>(relaxed = true)
        val plan = mockk<MineWorkingPlan>(relaxed = true)
        val incidents = mockk<MineIncidentCoordinator>(relaxed = true)
        val equipment = mockk<MineWorkingEquipment>(relaxed = true)
        val presentation = mockk<MineWorkingPresentation>(relaxed = true)
        val travel = mockk<WorksiteExpeditionTravel>(relaxed = true)
        val access = mockk<WorksiteAccessPort>(relaxed = true)
        val state = mockk<WorksiteStatePort>(relaxed = true)
        val tasks = mockk<WorksiteTaskPort>(relaxed = true)
        every { scene.blocks } returns blocks
        every { scene.plan } returns plan
        every { blocks.world } returns world
        every { blocks.sequence } returns runtime.state.sequence
        every { plan.footprint } returns emptySet()
        every { scene.surface() } returns Location(world, 0.5, 64.0, 0.5)
        every { sceneWorld.scene(runtime) } returns scene
        every { sceneWorld.retainedScene(runtime.settings.id, runtime.state.sequence) } returns scene
        every { sceneWorld.isReady(runtime) } returns true
        every { travel.isAuthorized(player, any<Location>()) } returns false
        every { travel.record(player) } returns null
        every { travel.evacuate(runtime.settings.id, any<Long>()) } returns true
        every { sceneWorld.occupied(scene) } returns false
        val controller = MineWorkingController(
            registry, placement, sceneWorld, incidents, equipment, presentation, travel, access, state, tasks, { 0L }, drive = mockk(relaxed = true),
        )
        MineWorkingController::class.java.getDeclaredMethod(
            "beginCompletionGrace", MineRuntime::class.java, Long::class.javaPrimitiveType!!,
        ).also { it.isAccessible = true }.invoke(controller, runtime, runtime.state.sequence)

        controller.transitioning(runtime) shouldBe true
        // A subsequent order may advance the runtime sequence while the old
        // prepared scene is still visible; it must not evict that scene early.
        runtime.state = runtime.state.copy(sequence = runtime.state.sequence + 1)
        every { sceneWorld.scene(runtime) } returns null
        val from = Location(world, 5.5, 65.0, 5.5)
        val to = Location(world, 6.5, 65.0, 6.5)
        every { scene.inside(to) } returns true
        controller.guardMovement(PlayerMoveEvent(player, from, to)) shouldBe false
        controller.tick(runtime, 59_999L)
        controller.transitioning(runtime) shouldBe true
        controller.tick(runtime, 60_000L)
        controller.transitioning(runtime) shouldBe false
        verify(exactly = 1) { sceneWorld.startRestore(scene) }
    }

    test("completion warning is sent fifteen seconds before hard deadline and visitors are evacuated") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("VisitorMiner")
        val runtime = lifecycleRuntime(world).also { it.state = it.state.copy(phase = MinePhase.MINING, incident = null) }
        val registry = MineRuntimeRegistry().also { it.replace(listOf(runtime)) }
        val placement = mockk<MineWorkingPlacementService>(relaxed = true)
        val sceneWorld = mockk<MineWorkingWorld>(relaxed = true)
        val scene = mockk<MineWorkingScene>(relaxed = true)
        val blocks = mockk<WorksitePreparedScene>(relaxed = true)
        val plan = mockk<MineWorkingPlan>(relaxed = true)
        val incidents = mockk<MineIncidentCoordinator>(relaxed = true)
        val equipment = mockk<MineWorkingEquipment>(relaxed = true)
        val presentation = mockk<MineWorkingPresentation>(relaxed = true)
        val travel = mockk<WorksiteExpeditionTravel>(relaxed = true)
        val access = mockk<WorksiteAccessPort>(relaxed = true)
        val state = mockk<WorksiteStatePort>(relaxed = true)
        val tasks = mockk<WorksiteTaskPort>(relaxed = true)
        val surface = Location(world, 0.5, 64.0, 0.5)
        every { scene.blocks } returns blocks
        every { scene.plan } returns plan
        every { blocks.world } returns world
        every { blocks.sequence } returns runtime.state.sequence
        every { plan.footprint } returns emptySet()
        every { scene.surface() } returns surface
        every { scene.inside(any()) } returns true
        every { sceneWorld.scene(runtime) } returns scene
        every { sceneWorld.retainedScene(runtime.settings.id, runtime.state.sequence) } returns scene
        every { travel.evacuate(runtime.settings.id, runtime.state.sequence) } returns true
        every { travel.evacuatePlayer(player, any()) } returns true
        every { sceneWorld.occupied(scene) } returns false
        player.teleport(surface)
        val controller = MineWorkingController(
            registry, placement, sceneWorld, incidents, equipment, presentation, travel, access, state, tasks, { 0L }, drive = mockk(relaxed = true),
        )
        MineWorkingController::class.java.getDeclaredMethod(
            "beginCompletionGrace", MineRuntime::class.java, Long::class.javaPrimitiveType!!,
        ).also { it.isAccessible = true }.invoke(controller, runtime, runtime.state.sequence)

        controller.tick(runtime, 4 * 60_000L + 44_999L)
        verify(exactly = 0) { presentation.feedback(player, "closing-warning", any()) }
        controller.tick(runtime, 4 * 60_000L + 45_000L)
        verify(exactly = 1) { presentation.feedback(player, "closing-warning", any()) }
        controller.tick(runtime, 5 * 60_000L)
        verify(exactly = 1) { travel.evacuatePlayer(player, surface) }
        verify(exactly = 1) { sceneWorld.startRestore(scene) }
    }

    test("final working step projects its physical state before the incident is cleared") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("ProjectionMiner")
        val runtime = lifecycleRuntime(world)
        val registry = MineRuntimeRegistry().also { it.replace(listOf(runtime)) }
        val placement = mockk<MineWorkingPlacementService>(relaxed = true)
        val sceneWorld = mockk<MineWorkingWorld>(relaxed = true)
        val scene = mockk<MineWorkingScene>(relaxed = true)
        val incidents = mockk<MineIncidentCoordinator>(relaxed = true)
        val equipment = mockk<MineWorkingEquipment>(relaxed = true)
        val presentation = mockk<MineWorkingPresentation>(relaxed = true)
        val travel = mockk<WorksiteExpeditionTravel>(relaxed = true)
        val access = mockk<WorksiteAccessPort>(relaxed = true)
        val state = mockk<WorksiteStatePort>(relaxed = true)
        val tasks = mockk<WorksiteTaskPort>(relaxed = true)
        val token = mockk<RuntimeTaskSupervisor.Token>()
        every { sceneWorld.retainedScene(runtime.settings.id, runtime.state.sequence) } returns scene
        every { incidents.work(any(), any(), any(), any()) } answers {
            runtime.state = runtime.state.copy(phase = MinePhase.MINING, incident = null)
            EngineResult(runtime.state, true)
        }
        every { state.persistAsync() } returns CompletableFuture.completedFuture(Unit)
        every { tasks.lifecycleToken() } returns token
        every { tasks.runSync(token, any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        val controller = MineWorkingController(
            registry, placement, sceneWorld, incidents, equipment, presentation, travel, access, state, tasks, { 2_000L }, drive = mockk(relaxed = true),
        )
        val advance = MineWorkingController::class.java.getDeclaredMethod(
            "advance", MineRuntime::class.java, Player::class.java,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
        ).also { it.isAccessible = true }
        advance.invoke(controller, runtime, player, 0, 1, false)

        verify(exactly = 1) {
            sceneWorld.project(runtime, MineIncidentType.TUNNEL_DRIVE, any<MineWorkingState>())
        }
    }

    test("reconcileLoaded reapplies saved working projection and restores an orphaned scene") {
        val world = paper.server.addSimpleWorld("mine_working_lifecycle")
        for (chunkX in -1..1) for (chunkZ in -1..2) world.getChunkAt(chunkX, chunkZ).load()
        val plugin = paper.createSimplePlugin("MineWorkingReconcileTest")
        val placement = legacyWorkingPlacement(
            WorksitePosition(world.name, 0, 64, 0), direction = 0, floorId = "fixture-floor",
        )
        val plan = MineWorkingLayout.plan(MineIncidentType.TUNNEL_DRIVE, placement)
        val runtime = MineRuntime(
            settings = mineV2Settings().copy(id = "mine_working_lifecycle"),
            region = CuboidActivityRegion(world, "mine_working_lifecycle", CuboidBounds(-16, 50, -16, 32, 80, 40)),
            cooldownMillis = 0L,
            state = MineShiftState(
                engineVersion = 2,
                phase = MinePhase.INCIDENT,
                sequence = 7,
                incident = MineIncidentState(
                    type = MineIncidentType.TUNNEL_DRIVE,
                    required = 1,
                    objectiveNonce = 9,
                    working = MineWorkingState(placement, MineWorkingStage.EXCAVATE),
                ),
            ),
        )
        val registry = MineRuntimeRegistry().also { it.replace(listOf(runtime)) }
        populateGeology(world, plan, placement)
        val excavation = plan.excavation.first()
        val original = world.getBlockAt(excavation.x, excavation.y, excavation.z).blockData.asString
        val owner = sceneOwner(plugin)
        val first = MineWorkingWorld(registry, owner, MockBukkitFarmBlockDataDecoder)
        first.prewarm(runtime, MineIncidentType.TUNNEL_DRIVE, placement)
        repeat(256) { first.process() }
        first.prepare(runtime, MineIncidentType.TUNNEL_DRIVE, placement, 9) shouldBe true
        drain(owner, first, runtime)
        first.isReady(runtime) shouldBe true

        // Simulate a saved stage update whose callback never reached world.project.
        val stagedIncident = requireNotNull(runtime.state.incident)
        runtime.state = runtime.state.copy(incident = stagedIncident.copy(
            working = MineWorkingState(placement, MineWorkingStage.SUPPORT, completed = setOf(0)),
        ))
        first.clearQueues()
        val restartedOwner = sceneOwner(plugin)
        val restarted = MineWorkingWorld(registry, restartedOwner, MockBukkitFarmBlockDataDecoder)
        restarted.reconcileLoaded()
        drain(restartedOwner, restarted, runtime)
        restarted.isReady(runtime) shouldBe true
        world.getBlockAt(excavation.x, excavation.y, excavation.z).blockData.asString shouldBe "minecraft:air"
        original shouldBe "minecraft:stone"

        // Removing the durable incident makes the journal orphaned; reload must restore it and drop the scene.
        runtime.state = runtime.state.copy(incident = null)
        restarted.reconcileLoaded()
        restarted.isRestoring(runtime) shouldBe true
        restarted.hasScene(runtime) shouldBe true
        var rounds = 0
        while (restarted.isRestoring(runtime)) {
            restarted.process()
            check(++rounds < 64) { "orphaned mine scene did not restore in bounded test" }
        }
        restarted.process()
        restarted.isRestoring(runtime) shouldBe false
        restarted.hasScene(runtime) shouldBe false
        restarted.scene(runtime) shouldBe null
        world.getBlockAt(excavation.x, excavation.y, excavation.z).blockData.asString shouldBe original
    }
})

private fun lifecycleRuntime(world: WorldMock): MineRuntime {
    val settings = mineV2Settings()
    val placement = legacyWorkingPlacement(WorksitePosition(world.name, 2, 64, 2), 0, "lifecycle-floor")
    return MineRuntime(
        settings = settings,
        region = CuboidActivityRegion(world, settings.id, CuboidBounds(0, 50, 0, 20, 90, 20)),
        cooldownMillis = 0L,
        state = MineShiftState(
            engineVersion = 2,
            phase = MinePhase.INCIDENT,
            sequence = 4,
            orderId = "ore_run",
            incident = MineIncidentState(
                type = MineIncidentType.TUNNEL_DRIVE,
                required = 4,
                objectiveNonce = 8,
                working = MineWorkingState(placement, MineWorkingStage.SUPPORT),
            ),
        ),
    )
}

private fun sceneOwner(plugin: Plugin): WorksitePreparedSceneOwner = WorksitePreparedSceneOwner(
    plugin = plugin,
    namespace = "mine_working",
    codec = FarmMoleBurrowWorksiteSceneCodec(plugin, "mine_working"),
    chunkRetention = WorksitePreparedSceneChunkRetention { AutoCloseable {} },
    blockDataDecoder = WorksitePreparedSceneBlockDataDecoder { raw -> MockBukkitFarmBlockDataDecoder.decode(raw) },
)

private fun populateGeology(world: WorldMock, plan: MineWorkingPlan, placement: MineWorkingPlacement) {
    plan.blocks.keys.forEach { position -> world.getBlockAt(position.x, position.y, position.z).type = Material.STONE }
    plan.walkable.filter { it.z - placement.entrance.z in 0..2 }.forEach { position ->
        world.getBlockAt(position.x, position.y, position.z).type = Material.AIR
    }
    val ground = placement.position(0, 0, -1)
    world.getBlockAt(ground.x, ground.y, ground.z).type = Material.STONE
    val head = placement.position(0, 1, -1)
    for (up in 0..2) world.getBlockAt(head.x, head.y + up, head.z).type = Material.AIR
    plan.shell.firstOrNull()?.let { world.getBlockAt(it.x, it.y, it.z).type = Material.DEEPSLATE }
    plan.excavation.lastOrNull()?.let { world.getBlockAt(it.x, it.y, it.z).type = Material.TUFF }
}

private fun drain(owner: WorksitePreparedSceneOwner, world: MineWorkingWorld, runtime: MineRuntime) {
    val sceneId = (requireNotNull(runtime.state.incident).objectiveNonce % 16).toInt()
    var rounds = 0
    while (owner.isBuilding(runtime.settings.id, runtime.state.sequence, sceneId)) {
        world.process()
        check(++rounds < 64) { "prepared mine scene did not finish in bounded lifecycle test" }
    }
}

private fun legacyWorkingPlacement(entrance: WorksitePosition, direction: Int, floorId: String, layoutSeed: Long = 0L, geometryVersion: Int = 4) =
    MineWorkingPlacement(entrance, direction, floorId, layoutSeed, geometryVersion)

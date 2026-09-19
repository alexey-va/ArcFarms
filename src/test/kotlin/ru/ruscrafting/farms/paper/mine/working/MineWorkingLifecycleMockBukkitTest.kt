package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.entity.Player
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
        val placement = MineWorkingPlacement(
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
            registry, placement, scene, incidents, equipment, presentation, travel, access, state, tasks, { 2_000L },
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

    test("reconcileLoaded reapplies saved working projection and restores an orphaned scene") {
        val world = paper.server.addSimpleWorld("mine_working_lifecycle")
        for (chunkX in -1..1) for (chunkZ in -1..2) world.getChunkAt(chunkX, chunkZ).load()
        val plugin = paper.createSimplePlugin("MineWorkingReconcileTest")
        val placement = MineWorkingPlacement(
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
        val owner = sceneOwner(plugin)
        val first = MineWorkingWorld(registry, owner, MockBukkitFarmBlockDataDecoder)
        first.prepare(runtime, MineIncidentType.TUNNEL_DRIVE, placement, 9) shouldBe true
        drain(owner, first, runtime)
        first.isReady(runtime) shouldBe true
        val excavation = plan.excavation.first()
        val original = world.getBlockAt(excavation.x, excavation.y, excavation.z).blockData.asString

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
    val placement = MineWorkingPlacement(WorksitePosition(world.name, 2, 64, 2), 0, "lifecycle-floor")
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

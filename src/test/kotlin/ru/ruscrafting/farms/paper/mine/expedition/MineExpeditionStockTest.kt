package ru.ruscrafting.farms.paper.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.domain.mine.expedition.*
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder
import ru.ruscrafting.farms.paper.mine.*
import ru.ruscrafting.farms.paper.worksite.scene.*
import ru.ruscrafting.farms.persistence.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class MineExpeditionStockTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("permanent sites are claimed and released without relocation or regeneration") {
        val world = paper.server.addSimpleWorld("world")
        val repository = MineExpeditionSceneRepository(MemoryExpeditionStorage())
        val loader = ReadySceneLoader(world)
        val port = immediateMinePort()
        val stock = MineExpeditionStock(repository, port, port, loader)
        stock.site = MineExpeditionSite("world", 40, 0, 64, 10.5, 64.0, 10.5)
        stock.activate()
        stock.maintain(1_000L)
        repository.records().size shouldBe 3
        stock.status().all { it.ready == 1 } shouldBe true
        val before = repository.records().single { it.kind == MineExpeditionKind.DEAD_FACTORY }
        val runtime = MineRuntimeFactory.build(listOf(mineV2Settings()), emptyMap(), 5_000L, CuboidRegionGateway()).single()
        runtime.state = MineShiftState(engineVersion = 2, phase = MinePhase.INCIDENT, orderId = "ore_run", sequence = 5,
            incident = MineIncidentState(MineIncidentType.DEAD_FACTORY, required = 14, objectiveNonce = 100))
        val surface = Location(world, 10.5, 64.0, 10.5)
        stock.ensure(runtime, surface)
        val scene = stock.ensure(runtime, surface)!!
        scene.ready shouldBe true
        scene.placement shouldBe before.placement
        scene.journalOwner shouldBe before.journalOwner
        scene.sceneId shouldBe before.sceneId
        scene.zoneId shouldBe "old_shafts"
        runtime.state.incident!!.expedition!!.placement shouldBe before.placement
        stock.maintain(2_000L)
        repository.records().size shouldBe 3
        stock.status().single { it.kind == MineExpeditionKind.DEAD_FACTORY }.ready shouldBe 0
        stock.complete(scene, 3_000L)
        stock.release(scene)
        stock.maintain(4_000L)
        repository.records().size shouldBe 3
        val released = repository.records().single { it.kind == MineExpeditionKind.DEAD_FACTORY }
        released.placement shouldBe before.placement
        released.reserved shouldBe true
        scene.completedAt shouldBe 0L
        loader.restored.size shouldBe 0
        stock.status().all { it.ready == 1 } shouldBe true
        stock.rebuild(null) shouldBe 3
        loader.restored.size shouldBe 3
        stock.deactivate()
        repository.close()
    }

    test("next factory preset survives unrelated ensures and never rerolls an initialized run") {
        val world = paper.server.addSimpleWorld("world")
        val repository = MineExpeditionSceneRepository(MemoryExpeditionStorage())
        val loader = ReadySceneLoader(world)
        val port = immediateMinePort()
        val stock = MineExpeditionStock(repository, port, port, loader)
        stock.site = MineExpeditionSite("world", 40, 0, 64, 10.5, 64.0, 10.5)
        stock.activate()
        stock.maintain(1_000L)
        val runtime = MineRuntimeFactory.build(listOf(mineV2Settings()), emptyMap(), 5_000L, CuboidRegionGateway()).single()
        val surface = Location(world, 10.5, 64.0, 10.5)
        stock.configureFactoryExperiments("old_shafts", "all") shouldBe true
        stock.ensure(runtime, surface) shouldBe null
        runtime.state = MineShiftState(engineVersion = 2, phase = MinePhase.INCIDENT, orderId = "ore_run", sequence = 5,
            incident = MineIncidentState(MineIncidentType.DEAD_FACTORY, required = 10, objectiveNonce = 100))
        stock.ensure(runtime, surface)
        val scene = stock.ensure(runtime, surface)!!
        val selected = runtime.state.incident!!.expedition!!
        selected.factoryExperiments!!.selected shouldBe MineFactoryExperiments.supported
        stock.configureFactoryExperiments("old_shafts", "none") shouldBe true
        stock.ensure(runtime, surface)
        runtime.state.incident!!.expedition shouldBe selected
        stock.complete(scene, 3_000L)
        stock.release(scene)
        runtime.state = runtime.state.copy(incident = MineIncidentState(MineIncidentType.DEAD_FACTORY, required = 10, objectiveNonce = 101))
        stock.ensure(runtime, surface)
        stock.ensure(runtime, surface)
        runtime.state.incident!!.expedition!!.factoryExperiments!!.selected shouldBe emptySet()
        stock.deactivate()
        repository.close()
    }

    test("connected saved factories normalize retired jobs and a missing plan from the stable journal seed") {
        val world = paper.server.addSimpleWorld("world")
        val repository = MineExpeditionSceneRepository(MemoryExpeditionStorage())
        val loader = ReadySceneLoader(world)
        val port = immediateMinePort()
        val stock = MineExpeditionStock(repository, port, port, loader)
        stock.site = MineExpeditionSite("world", 40, 0, 64, 10.5, 64.0, 10.5)
        stock.activate()
        stock.maintain(1_000L)
        val runtime = MineRuntimeFactory.build(listOf(mineV2Settings()), emptyMap(), 5_000L, CuboidRegionGateway()).single()
        val surface = Location(world, 10.5, 64.0, 10.5)
        runtime.state = MineShiftState(
            engineVersion = 2,
            phase = MinePhase.INCIDENT,
            orderId = "ore_run",
            sequence = 5,
            incident = MineIncidentState(MineIncidentType.DEAD_FACTORY, required = 10, objectiveNonce = 100),
        )
        stock.ensure(runtime, surface)
        stock.ensure(runtime, surface)!!
        val initialized = runtime.state.incident!!.expedition!!
        val oldPlan = MineFactoryExperimentPlan(
            selected = setOf(
                MineFactoryExperiment.ROCK_JAM,
                MineFactoryExperiment.MOULD,
                MineFactoryExperiment.DRIVE_REPAIR,
            ),
            resolved = setOf(MineFactoryExperiment.MOULD, MineFactoryExperiment.ROCK_JAM),
            product = 2,
        )

        runtime.state = runtime.state.copy(incident = runtime.state.incident!!.copy(
            expedition = initialized.copy(
                stage = MineExpeditionStage.FACTORY_COAL,
                completed = setOf(0, 1),
                factoryExperiments = oldPlan,
            ),
        ))
        stock.ensure(runtime, surface)
        val normalized = runtime.state.incident!!.expedition!!
        normalized.completed shouldBe setOf(0, 1)
        normalized.factoryExperiments!!.selected shouldBe setOf(MineFactoryExperiment.ROCK_JAM)
        normalized.factoryExperiments!!.resolved shouldBe setOf(MineFactoryExperiment.ROCK_JAM)
        normalized.factoryExperiments!!.product shouldBe 2

        runtime.state = runtime.state.copy(incident = runtime.state.incident!!.copy(
            expedition = normalized.copy(
                stage = MineExpeditionStage.FACTORY_COAL,
                completed = setOf(0, 1, 2),
                factoryExperiments = null,
            ),
        ))
        stock.ensure(runtime, surface)
        val missingPlan = runtime.state.incident!!.expedition!!.factoryExperiments
        missingPlan shouldBe MineFactoryExperimentPlan()
        runtime.state.incident!!.expedition!!.completed shouldBe setOf(0, 1, 2)

        stock.deactivate()
        repository.close()
    }

    test("a late claim releases the permanent site without deleting its blocks") {
        val world = paper.server.addSimpleWorld("world")
        val storage = MemoryExpeditionStorage()
        val repository = MineExpeditionSceneRepository(storage)
        val loader = ReadySceneLoader(world)
        val port = immediateMinePort()
        val stock = MineExpeditionStock(repository, port, port, loader)
        stock.site = MineExpeditionSite("world", 40, 0, 64, 10.5, 64.0, 10.5)
        stock.activate()
        stock.maintain(1_000L)
        val runtime = MineRuntimeFactory.build(listOf(mineV2Settings()), emptyMap(), 5_000L, CuboidRegionGateway()).single()
        runtime.state = MineShiftState(engineVersion = 2, phase = MinePhase.INCIDENT, orderId = "ore_run", sequence = 5,
            incident = MineIncidentState(MineIncidentType.DEAD_FACTORY, required = 14, objectiveNonce = 100))
        storage.delay = CompletableFuture()
        stock.ensure(runtime, Location(world, 10.5, 64.0, 10.5))
        repository.records().all { it.reserved } shouldBe true
        runtime.state = runtime.state.copy(phase = MinePhase.MINING, incident = null)
        val pending = storage.delay!!
        storage.delay = null
        pending.complete(Unit)
        repository.records().all { it.reserved && !it.restoring } shouldBe true
        loader.restored.size shouldBe 0
        runtime.state.incident shouldBe null
        repository.close()
    }

    test("permanent rooms stay protected while reserved and during the result display") {
        val bukkitWorld = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("ExpeditionProtection")
        val repository = MineExpeditionSceneRepository(MemoryExpeditionStorage())
        val port = immediateMinePort()
        val world = MineExpeditionWorld(plugin, mockk(), port, port, mockk(relaxed = true),
            WorksitePreparedSceneBlockDataDecoder(MockBukkitFarmBlockDataDecoder::decode), repository)
        val receipt = MineExpeditionSceneReceipt("reserve", 0, 1, 1, MineExpeditionKind.DEAD_FACTORY,
            MineExpeditionPlacement("world", 0, 0, 0, 73), "world", 0.5, 64.0, 0.5,
            reserved = true, journalZoneId = "reserve", journalSceneId = 1)
        val loader = ReadySceneLoader(bukkitWorld)
        loader.prepare(receipt)
        val scene = loader.prepared(1)!!
        // Substitute only world generation; exercise the production ownership gate.
        val field = MineExpeditionWorld::class.java.getDeclaredField("scenes").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(world) as MutableMap<Long, MineExpeditionScene>)[1] = scene
        for ((reserved, completed) in listOf(true to 0L, false to 0L, false to 5_000L)) {
            scene.reserved = reserved
            scene.completedAt = completed
            world.protects(Location(bukkitWorld, .5, 64.0, .5)) shouldBe true
            world.protects(Location(bukkitWorld, 20.5, 64.0, .5)) shouldBe false
        }
        world.close()
    }

    test("startup scene cleanup keeps its repository open until plugin shutdown") {
        paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("ExpeditionStartup")
        val storage = MemoryExpeditionStorage()
        val repository = MineExpeditionSceneRepository(storage)
        val port = immediateMinePort()
        val world = MineExpeditionWorld(plugin, mockk(), port, port, mockk(relaxed = true),
            WorksitePreparedSceneBlockDataDecoder(MockBukkitFarmBlockDataDecoder::decode), repository)
        val controller = MineExpeditionController(plugin, MineRuntimeRegistry(), world, mockk(), mockk(),
            { null }, port, port, null, { 1_000L })
        controller.cleanup()
        storage.closed shouldBe false
        val receipt = MineExpeditionSceneReceipt("reserve", 0, 1, 1, MineExpeditionKind.DEAD_FACTORY,
            MineExpeditionPlacement("rc_arcfarms_expeditions", 0, 0, 0, 73), "world", 0.5, 64.0, 0.5,
            reserved = true, journalZoneId = "reserve", journalSceneId = 1)
        repository.commit(receipt).join()
        controller.cleanup(shutdown = true)
        storage.closed shouldBe true
        shouldThrow<CompletionException> { repository.commit(receipt).join() }
    }
})

private class MemoryExpeditionStorage : MineExpeditionLedgerStorage {
    var closed = false
    var delay: CompletableFuture<Unit>? = null
    override fun load() = CompletableFuture.completedFuture(MineExpeditionSceneLedger())
    override fun save(ledger: MineExpeditionSceneLedger) = delay ?: CompletableFuture.completedFuture(Unit)
    override fun shutdown() { closed = true }
}

/** Only world preparation is substituted; stock persistence, claiming and lifecycle use production owners. */
private class ReadySceneLoader(private val world: World) : MineExpeditionSceneLoader {
    val scenes = linkedMapOf<Long, MineExpeditionScene>()
    val restored = mutableListOf<MineExpeditionSceneReceipt>()
    override fun prepare(receipt: MineExpeditionSceneReceipt) {
        if (receipt.journalSequence in scenes) return
        val point = ExpeditionPoint(0, 64, 0)
        val plan = MineExpeditionPlan(receipt.kind, receipt.placement.seed, ExpeditionBounds(point, point),
            mapOf(point to "minecraft:air"), mapOf("entry" to point, "exit" to point), emptyMap(), emptyList())
        val at = Location(world, 0.5, 64.0, 0.5)
        val prepared = WorksitePreparedScene(world, receipt.journalOwner, receipt.journalSequence, receipt.sceneId, at, at, at, emptyList())
        scenes[receipt.journalSequence] = MineExpeditionScene(plan, receipt.placement, world, receipt.zoneId, receipt.sequence,
            receipt.objectiveNonce, receipt.journalSequence, at, prepared, reserved = receipt.reserved).also { it.refreshReady(false, true) }
    }
    override fun prepared(id: Long) = scenes[id]
    override fun failure(id: Long): String? = null
    override fun restore(receipt: MineExpeditionSceneReceipt) { restored += receipt }
}

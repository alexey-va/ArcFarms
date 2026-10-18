package ru.ruscrafting.farms.paper.worksite.scene

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Chunk
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BukkitTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.computeWorksiteTask
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorksiteSceneCodec
import ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionWorld
import ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionWorldRegistry
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionBounds
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlan
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement
import ru.ruscrafting.farms.persistence.MineExpeditionLedgerStorage
import ru.ruscrafting.farms.persistence.MineExpeditionSceneLedger
import ru.ruscrafting.farms.persistence.MineExpeditionSceneReceipt
import ru.ruscrafting.farms.persistence.MineExpeditionSceneRepository
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level

class WorksitePreparedSceneAsyncTest : FunSpec({
    test("large scenes encode on worker and apply and restore within the shared native tick budget") {
        SceneHarness().use { h ->
            val scene = h.scene(600)
            h.owner.prepareIncrementally(scene, NATIVE_STEP_BUDGET) shouldBe true
            scene.ready shouldBe false
            h.until { scene.ready }
            (h.maxNativeCallsPerTick <= NATIVE_STEP_BUDGET) shouldBe true
            (h.nativeTicks > 1) shouldBe true
            (h.workerCalls.get() > 1) shouldBe true
            scene.records.all { h.world.getBlockAt(it.x, it.y, it.z).type == Material.AIR } shouldBe true

            val changed = scene.records.associate { Triple(it.x, it.y, it.z) to "minecraft:dirt" }
            val projection = h.owner.projectAsync(scene, changed)
            projection.isDone shouldBe false
            h.until { projection.isDone }
            projection.join() shouldBe true
            scene.records.all { h.world.getBlockAt(it.x, it.y, it.z).type == Material.DIRT } shouldBe true

            h.owner.beginRestore(h.world, scene.zoneId, scene.sequence)
            h.until(beforeTick = { h.owner.beginRestore(h.world, scene.zoneId, scene.sequence) }) { !h.owner.restoring(scene.zoneId) }
            scene.records.all { h.world.getBlockAt(it.x, it.y, it.z).type == Material.STONE } shouldBe true
            h.world.loadedChunks.all { it.persistentDataContainer.get(h.key, PersistentDataType.BYTE_ARRAY) == null } shouldBe true
            (h.maxNativeCallsPerTick <= NATIVE_STEP_BUDGET) shouldBe true
            h.nativeDecodeCalls.get() shouldBe 0
        }
    }

    test("clearing an owner while worker preparation is delayed cannot publish its stale scene") {
        SceneHarness().use { h ->
            val blocker = CompletableFuture<Unit>()
            h.computeGate = blocker
            val scene = h.scene(300)
            h.owner.prepareIncrementally(scene, 128) shouldBe true
            h.owner.clearQueues()
            blocker.complete(Unit)
            h.until { h.pendingComputations.get() == 0 }
            repeat(5) { h.tick() }
            h.owner.isBuilding(scene.zoneId, scene.sequence) shouldBe false
            scene.records.all { h.world.getBlockAt(it.x, it.y, it.z).type == Material.STONE } shouldBe true
            h.world.loadedChunks.all { it.persistentDataContainer.get(h.key, PersistentDataType.BYTE_ARRAY) == null } shouldBe true
        }
    }

    test("failed restoration admission releases its lease and retries the protected journal") {
        SceneHarness().use { h ->
            val scene = h.scene(1)
            h.owner.prepareIncrementally(scene, 128) shouldBe true
            h.until { scene.ready }
            h.owner.reconcileLoaded({ _, _ -> true }, maintenance = true)
            h.failNextCapture = IllegalStateException("Native admission unavailable")

            h.owner.beginRestore(h.world, scene.zoneId, scene.sequence)
            h.until { !h.owner.restoring(scene.zoneId) }
            h.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            h.owner.protects(Location(h.world, 0.0, 64.0, 0.0)) shouldBe true
            (h.world.getChunkAt(0, 0).persistentDataContainer.get(h.key, PersistentDataType.BYTE_ARRAY) != null) shouldBe true

            h.owner.beginRestore(h.world, scene.zoneId, scene.sequence)
            h.until { !h.owner.restoring(scene.zoneId) }
            h.world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            h.world.getChunkAt(0, 0).persistentDataContainer.get(h.key, PersistentDataType.BYTE_ARRAY) shouldBe null
        }
    }

    test("maintenance reimports protected journals after owner cleanup without per-chunk capture") {
        SceneHarness().use { h ->
            val scene = h.scene(20)
            h.owner.prepareIncrementally(scene, 128) shouldBe true
            h.until { scene.ready }
            h.owner.reconcileLoaded({ _, _ -> true }, maintenance = true)
            h.failNextCapture = IllegalStateException("Native admission unavailable")
            h.owner.beginRestore(h.world, scene.zoneId, scene.sequence)
            h.until { !h.owner.restoring(scene.zoneId) }
            h.owner.clearQueues()

            val captures = h.captureSubmissions
            h.owner.reconcileLoaded({ _, _ -> false }, maintenance = true)
            h.captureSubmissions shouldBe captures
            (h.nativeDecodeCalls.get() > 0) shouldBe true
            scene.records.all { h.world.getBlockAt(it.x, it.y, it.z).type == Material.AIR } shouldBe true
            (h.world.getChunkAt(0, 0).persistentDataContainer.get(h.key, PersistentDataType.BYTE_ARRAY) != null) shouldBe true

            h.until { !h.owner.restoring(scene.zoneId) }
            scene.records.all { h.world.getBlockAt(it.x, it.y, it.z).type == Material.STONE } shouldBe true
            h.world.loadedChunks.all { it.persistentDataContainer.get(h.key, PersistentDataType.BYTE_ARRAY) == null } shouldBe true
        }
    }

    test("background loaded scene recovery keeps journal decoding asynchronous") {
        SceneHarness().use { h ->
            val scene = h.scene(1)
            h.owner.prepareIncrementally(scene, 128) shouldBe true
            h.until { scene.ready }
            h.owner.clearQueues()
            val captures = h.captureSubmissions

            h.owner.reconcileLoaded({ _, _ -> false })
            h.captureSubmissions shouldBe captures + 1
            h.nativeDecodeCalls.get() shouldBe 0
            h.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR

            h.until { h.world.getChunkAt(0, 0).persistentDataContainer.get(h.key, PersistentDataType.BYTE_ARRAY) == null }
            h.world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
            h.nativeDecodeCalls.get() shouldBe 0
        }
    }

    test("more than 256 empty chunk callbacks do not submit worker work or invalidate pending recovery") {
        SceneHarness().use { h ->
            val scene = h.scene(1)
            h.writeJournal(scene.records)
            val blocker = CompletableFuture<Unit>()
            h.computeGate = blocker
            val recovery = h.recover(scene)
            h.until { h.pendingComputations.get() > 0 }
            val computations = h.pendingComputations.get()
            val captures = h.captureSubmissions

            repeat(300) { x -> h.owner.onChunkLoad(h.world.getChunkAt(x + 10, 10), { _, _ -> true }) }

            h.pendingComputations.get() shouldBe computations
            h.captureSubmissions shouldBe captures
            recovery.isDone shouldBe false
            blocker.complete(Unit)
            h.until { recovery.isDone }
            recovery.join()?.records shouldBe scene.records
            h.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            h.owner.restoring(scene.zoneId) shouldBe false
        }
    }

    test("one restoration rescans a real journal revision and finishes without a second admission") {
        SceneHarness().use { h ->
            val scene = h.scene(1)
            val first = scene.records.single().copy(totalRecords = 2)
            val second = first.copy(x = 16)
            h.writeJournal(listOf(first))
            val blocker = CompletableFuture<Unit>()
            h.computeGate = blocker
            h.owner.beginRestore(h.world, scene.zoneId, scene.sequence)
            h.until { h.pendingComputations.get() > 0 }

            h.writeJournal(listOf(second))
            h.owner.onChunkLoad(h.world.getChunkAt(1, 0), { _, _ -> false })
            blocker.complete(Unit)

            h.until { !h.owner.restoring(scene.zoneId) }
            listOf(0, 16).forEach { x ->
                h.world.getBlockAt(x, 64, 0).type shouldBe Material.STONE
                h.world.getChunkAt(x shr 4, 0).persistentDataContainer.get(h.key, PersistentDataType.BYTE_ARRAY) shouldBe null
            }
        }
    }

    test("a confirmed partial recovery can restore its originals after its future completes") {
        SceneHarness().use { h ->
            val scene = h.scene(1)
            h.writeJournal(listOf(scene.records.single().copy(totalRecords = 2)))
            val recovery = h.recover(scene)
            recovery.isDone shouldBe false
            h.until { recovery.isDone }
            recovery.join() shouldBe null
            h.owner.restoring(scene.zoneId) shouldBe false
            h.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR

            h.owner.beginRestore(h.world, scene.zoneId, scene.sequence)
            h.until { !h.owner.restoring(scene.zoneId) }
            h.world.getBlockAt(0, 64, 0).type shouldBe Material.STONE
        }
    }

    test("actual worker overload retains calculations until admission succeeds once") {
        SceneHarness(workerCapacity = 1).use { h ->
            val blocker = CompletableFuture<Unit>()
            h.computeGate = blocker
            val effects = AtomicInteger()
            val results = List(5) { h.compute { effects.incrementAndGet() } }
            h.until { h.rejectedAdmissions.get() > 0 }
            results.all { !it.isDone } shouldBe true
            blocker.complete(Unit)

            h.until { results.all { it.isDone } }
            results.map { it.join() }.toSet() shouldBe (1..5).toSet()
            effects.get() shouldBe 5
            h.attemptsByOperation.values.forEach { ticks -> ticks.distinct().size shouldBe ticks.size }
        }
    }

    test("task-thrown rejection is terminal and shutdown cancels an unadmitted retry") {
        SceneHarness().use { h ->
            val effects = AtomicInteger()
            val failure = h.compute<Int> { effects.incrementAndGet(); throw RejectedExecutionException("task failure") }
            h.until { failure.isDone }
            shouldThrow<CompletionException> { failure.join() }.cause?.message shouldBe "task failure"
            effects.get() shouldBe 1

            h.rejectComputations = true
            val pending = h.compute { effects.incrementAndGet() }
            h.tick()
            pending.isDone shouldBe false
            h.stopRuntime()
            pending.isCompletedExceptionally shouldBe true
            effects.get() shouldBe 1
        }
    }

    test("clearing discovery while decoding is delayed terminates recovery without retiring its journal") {
        SceneHarness().use { h ->
            val scene = h.scene(1)
            h.writeJournal(scene.records)
            h.computeGate = CompletableFuture()
            val recovery = h.recover(scene)
            h.until { h.pendingComputations.get() > 0 }
            h.owner.clearQueues()
            recovery.isCompletedExceptionally shouldBe true
            h.computeGate?.complete(Unit)
            h.until { h.pendingComputations.get() == 0 }
            h.world.getBlockAt(0, 64, 0).type shouldBe Material.AIR
            (h.world.getChunkAt(0, 0).persistentDataContainer.get(h.key, PersistentDataType.BYTE_ARRAY) != null) shouldBe true
        }
    }

    test("expedition preload ownership survives delayed preparation until the scene retains its chunks") {
        SceneHarness().use { h ->
            val (expedition, receipt, plan) = h.expedition()
            val pending = requireNotNull(expedition.beginPreparation(receipt.journalSequence))
            val releases = AtomicInteger()
            pending.leases += h.preloadLease { releases.incrementAndGet() }
            val blocker = CompletableFuture<Unit>()
            h.computeGate = blocker
            expedition.install(receipt, plan, h.scene(1).records.map { it.copy(sceneId = receipt.sceneId) }, pending)
            h.until { h.pendingComputations.get() > 0 }
            releases.get() shouldBe 0
            h.ticketCount(0, 0) shouldBe 1
            (expedition.prepared(receipt.journalSequence) != null) shouldBe true

            blocker.complete(Unit)
            h.until { releases.get() == 1 }
            h.ticketCount(0, 0) shouldBe 1
            h.until { expedition.prepared(receipt.journalSequence)?.ready == true }
            releases.get() shouldBe 1
        }
    }

    test("failed asynchronous expedition admission removes only its installed scene and releases preload once") {
        SceneHarness().use { h ->
            val (expedition, receipt, plan) = h.expedition()
            val pending = requireNotNull(expedition.beginPreparation(receipt.journalSequence))
            val releases = AtomicInteger()
            pending.leases += h.preloadLease { releases.incrementAndGet() }
            h.failNextCapture = IllegalStateException("Native admission unavailable")
            expedition.install(receipt, plan, h.scene(1).records.map { it.copy(sceneId = receipt.sceneId) }, pending)

            h.until { expedition.failure(receipt.journalSequence) != null }
            expedition.prepared(receipt.journalSequence) shouldBe null
            releases.get() shouldBe 1
            (expedition.beginPreparation(receipt.journalSequence) != null) shouldBe true
            expedition.beforeReload()
            releases.get() shouldBe 1
        }
    }

    test("reload closes delayed expedition preload once and its stale completion cannot publish") {
        SceneHarness().use { h ->
            val (expedition, receipt, plan) = h.expedition()
            val pending = requireNotNull(expedition.beginPreparation(receipt.journalSequence))
            val releases = AtomicInteger()
            pending.leases += h.preloadLease { releases.incrementAndGet() }
            h.computeGate = CompletableFuture()
            expedition.install(receipt, plan, h.scene(1).records.map { it.copy(sceneId = receipt.sceneId) }, pending)
            h.until { h.pendingComputations.get() > 0 }
            expedition.beforeReload()
            releases.get() shouldBe 1
            h.computeGate?.complete(Unit)
            h.until { h.pendingComputations.get() == 0 }
            repeat(3) { h.tick() }
            expedition.prepared(receipt.journalSequence) shouldBe null
            releases.get() shouldBe 1
        }
    }

    test("retiring an empty journal keeps the chunk ticket needed by another scene preparation") {
        SceneHarness().use { h ->
            val old = h.scene(1)
            h.owner.prepareIncrementally(old) shouldBe true
            h.until { old.ready }
            h.owner.reconcileLoaded({ _, _ -> true }, maintenance = true)
            val captures = h.captureSubmissions
            h.owner.beginRestore(h.world, old.zoneId, old.sequence)
            h.until { h.captureSubmissions > captures && h.pendingComputations.get() == 0 }

            h.world.getBlockAt(1, 64, 0).type = Material.STONE
            val replacement = old.copy(zoneId = "new_zone", sequence = 2,
                records = listOf(old.records.single().copy(zoneId = "new_zone", sequence = 2, x = 1)))
            val blocker = CompletableFuture<Unit>()
            h.blockNextCapture = blocker
            val admission = h.owner.prepareAllAsync(listOf(replacement))
            h.until(processOwner = false) { h.blockNextCapture == null }
            h.until { h.world.getChunkAt(0, 0).persistentDataContainer.get(h.key, PersistentDataType.BYTE_ARRAY) == null }
            admission.isDone shouldBe false
            h.ticketCount(0, 0) shouldBe 1

            blocker.complete(Unit)
            h.until { admission.isDone && replacement.ready }
            admission.join() shouldBe true
            h.ticketCount(0, 0) shouldBe 1
            h.owner.beginRestore(h.world, replacement.zoneId, replacement.sequence)
            h.until { !h.owner.restoring(replacement.zoneId) }
            h.ticketCount(0, 0) shouldBe 0
        }
    }
})

private const val NATIVE_STEP_BUDGET = 512

private class SceneHarness(workerCapacity: Int = 256) : AutoCloseable {
    private val nativeThread = Thread.currentThread()
    val paper = MockBukkitTestRuntime.open()
    val world = paper.server.addSimpleWorld("prepared_async")
    val plugin = paper.createSimplePlugin("PreparedAsyncTest")
    val key = NamespacedKey(plugin, "mine_working_v1")
    private val supervisor = RuntimeTaskSupervisor(BukkitTaskScheduler(plugin)).also { it.activate() }
    private val worker = BoundedAsyncExecutor("prepared-scene-test", capacity = workerCapacity)
    val workerCalls = AtomicInteger()
    val nativeDecodeCalls = AtomicInteger()
    val pendingComputations = AtomicInteger()
    val rejectedAdmissions = AtomicInteger()
    var rejectComputations = false
    val attemptsByOperation = ConcurrentHashMap<Int, MutableList<Int>>()
    private val nextOperation = AtomicInteger()
    var computeGate: CompletableFuture<Unit>? = null
    var failNextCapture: Throwable? = null
    var blockNextCapture: CompletableFuture<Unit>? = null
    var captureSubmissions = 0
    private var nativeCalls = 0
    var maxNativeCallsPerTick = 0
    var nativeTicks = 0
    private val retained = mutableMapOf<Pair<Int, Int>, Int>()
    private val expeditions = mutableListOf<MineExpeditionWorld>()
    private val tickets = object : MineChunkTicket {
        override fun retain(chunk: Chunk): Boolean {
            check(Thread.currentThread() === nativeThread)
            val key = chunk.x to chunk.z
            retained[key] = (retained[key] ?: 0) + 1
            return true
        }
        override fun release(chunk: Chunk) {
            check(Thread.currentThread() === nativeThread)
            val key = chunk.x to chunk.z
            val count = requireNotNull(retained[key]) - 1
            check(count >= 0)
            if (count == 0) retained.remove(key) else retained[key] = count
        }
    }
    private val tasks = object : WorksiteTaskPort {
        override fun guarded(scope: String, task: () -> Unit) = task()
        override fun lifecycleToken() = supervisor.token()
        override fun capture(token: RuntimeTaskSupervisor.Token, isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> {
            check(Thread.currentThread() === nativeThread)
            captureSubmissions++
            failNextCapture?.let { failure ->
                failNextCapture = null
                return CompletableFuture.failedFuture(failure)
            }
            val gate = blockNextCapture
            blockNextCapture = null
            return supervisor.capture(token, isCurrent) { if (gate != null && !gate.isDone) false else step() }
        }
        override fun <T> callSync(token: RuntimeTaskSupervisor.Token, task: () -> T) = supervisor.callSync(token, task)
        override fun <T> compute(task: () -> T): CompletableFuture<T> {
            pendingComputations.incrementAndGet()
            val gate = computeGate
            val operation = nextOperation.getAndIncrement()
            return computeWorksiteTask(supervisor, { action ->
                attemptsByOperation.getOrPut(operation) { Collections.synchronizedList(mutableListOf()) } += Bukkit.getCurrentTick()
                val admission = if (rejectComputations) CompletableFuture.failedFuture(RejectedExecutionException("admission closed"))
                else worker.submit {
                    check(Thread.currentThread() !== nativeThread)
                    gate?.join()
                    workerCalls.incrementAndGet()
                    action()
                }
                admission.whenComplete { _, failure -> if (failure is RejectedExecutionException) rejectedAdmissions.incrementAndGet() }
            }, task).whenComplete { _, _ -> pendingComputations.decrementAndGet() }
        }
        override fun runSync(token: RuntimeTaskSupervisor.Token, task: () -> Unit) = supervisor.runSync(token, task) != null
        override fun runAsync(token: RuntimeTaskSupervisor.Token, task: () -> Unit) = supervisor.runAsync(token, task) != null
        override fun runLater(delayTicks: Long, task: () -> Unit) = supervisor.runLater(delayTicks, task) != null
        override fun runLater(token: RuntimeTaskSupervisor.Token, delayTicks: Long, task: () -> Unit) = supervisor.runLater(token, delayTicks, task) != null
    }
    private val realCodec = FarmMoleBurrowWorksiteSceneCodec(plugin, "mine_working")
    val owner = WorksitePreparedSceneOwner(
        plugin, "mine_working", object : WorksitePreparedSceneCodec by realCodec {
            override fun encode(records: List<WorksitePreparedSceneRecord>, world: String, chunkX: Int, chunkZ: Int, minHeight: Int, maxHeight: Int): ByteArray {
                check(Thread.currentThread() !== nativeThread)
                return realCodec.encode(records, world, chunkX, chunkZ, minHeight, maxHeight)
            }
            override fun decode(raw: ByteArray, world: String, chunkX: Int, chunkZ: Int, minHeight: Int, maxHeight: Int): List<WorksitePreparedSceneRecord> {
                if (Thread.currentThread() === nativeThread) nativeDecodeCalls.incrementAndGet()
                return realCodec.decode(raw, world, chunkX, chunkZ, minHeight, maxHeight)
            }
        },
        chunkRetention = { tickets.retain(it); AutoCloseable { tickets.release(it) } },
        blockDataDecoder = {
            check(Thread.currentThread() === nativeThread)
            nativeCalls++
            Bukkit.createBlockData(it)
        },
        tasks = tasks,
    )

    fun scene(size: Int): WorksitePreparedScene {
        val records = (0 until size).map { x ->
            world.getChunkAt(x shr 4, 0).load()
            world.getBlockAt(x, 64, 0).type = Material.STONE
            WorksitePreparedSceneRecord(world.name, "mine_zone", 1, 0, x, 64, 0, "minecraft:stone", "minecraft:air", "NONE", size)
        }
        return WorksitePreparedScene(world, "mine_zone", 1, 0,
            Location(world, 0.5, 65.0, 0.5), Location(world, 0.5, 64.0, 0.5), Location(world, 1.5, 64.0, 0.5), records)
    }

    fun writeJournal(records: List<WorksitePreparedSceneRecord>) {
        records.groupBy { it.x shr 4 to (it.z shr 4) }.forEach { (position, chunkRecords) ->
            val chunk = world.getChunkAt(position.first, position.second)
            chunk.load()
            chunkRecords.forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.AIR }
            chunk.persistentDataContainer.set(key, PersistentDataType.BYTE_ARRAY,
                realCodec.encode(chunkRecords, world.name, chunk.x, chunk.z, world.minHeight, world.maxHeight))
        }
    }

    fun recover(scene: WorksitePreparedScene) = owner.sceneAsync(world, scene.zoneId, scene.sequence, scene.sceneId,
        scene.surface, 0, scene.start, scene.end)

    fun <T> compute(task: () -> T) = tasks.compute(task)

    fun stopRuntime() = supervisor.close()

    fun ticketCount(x: Int, z: Int) = retained[x to z] ?: 0

    fun preloadLease(released: () -> Unit): AutoCloseable {
        val chunk = world.getChunkAt(0, 0)
        tickets.retain(chunk)
        return AutoCloseable { tickets.release(chunk); released() }
    }

    fun expedition(): Triple<MineExpeditionWorld, MineExpeditionSceneReceipt, MineExpeditionPlan> {
        val repository = MineExpeditionSceneRepository(object : MineExpeditionLedgerStorage {
            override fun load() = CompletableFuture.completedFuture(MineExpeditionSceneLedger())
            override fun save(ledger: MineExpeditionSceneLedger) = CompletableFuture.completedFuture(Unit)
            override fun shutdown() = Unit
        })
        val state = object : WorksiteStatePort {
            override fun traceBlockBreak(event: BlockBreakEvent, activity: ActivityKind, zone: String) = Unit
            override fun tracePlayerAction(player: Player, activity: ActivityKind, zone: String, action: String, block: Material?) = Unit
            override fun traceResult(activity: ActivityKind, zone: String, actor: Player?, phase: Any, progress: String?, result: EngineResult<*, *>) = Unit
            override fun persistAsync() = CompletableFuture.completedFuture(Unit)
            override fun log(level: Level, message: String, failure: Throwable?) = Unit
        }
        val expedition = MineExpeditionWorld(plugin, object : MineExpeditionWorldRegistry {
            override fun ensureWorld() = world
        }, tasks, state, tickets, { Bukkit.createBlockData(it) }, repository)
        expedition.initialize(prewarm = false, maintenance = true)
        expeditions += expedition
        val point = ExpeditionPoint(0, 0, 0)
        val plan = MineExpeditionPlan(MineExpeditionKind.LAST_DESCENT, 1, ExpeditionBounds(point, point),
            mapOf(point to "minecraft:air"), mapOf("entry" to point, "exit" to point), emptyMap(), emptyList())
        val receipt = MineExpeditionSceneReceipt("mine_zone", 1, 1, 1, plan.kind,
            MineExpeditionPlacement(world.name, 0, 64, 0, 1), world.name, 0.5, 65.0, 0.5, reserved = true)
        repository.commit(receipt).join()
        return Triple(expedition, receipt, plan)
    }

    fun tick(beforeTick: () -> Unit = {}, processOwner: Boolean = true) {
        nativeCalls = 0
        beforeTick()
        if (processOwner) {
            owner.process(NATIVE_STEP_BUDGET * 2) { true }
            expeditions.forEach { it.process() }
        }
        paper.performTicks(1)
        maxNativeCallsPerTick = maxOf(maxNativeCallsPerTick, nativeCalls)
        if (nativeCalls > 0) nativeTicks++
        Thread.sleep(2)
    }

    fun until(beforeTick: () -> Unit = {}, processOwner: Boolean = true, done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "Scene did not finish its bounded queue" }
            tick(beforeTick, processOwner)
        }
    }

    override fun close() {
        computeGate?.complete(Unit)
        blockNextCapture?.complete(Unit)
        expeditions.forEach { it.close() }
        owner.clearQueues()
        supervisor.close()
        worker.closeAsync().join()
        paper.close()
    }
}

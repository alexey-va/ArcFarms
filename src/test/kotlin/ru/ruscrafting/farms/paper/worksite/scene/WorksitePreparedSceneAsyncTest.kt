package ru.ruscrafting.farms.paper.worksite.scene

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BukkitTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorksiteSceneCodec
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

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
})

private const val NATIVE_STEP_BUDGET = 512

private class SceneHarness : AutoCloseable {
    private val nativeThread = Thread.currentThread()
    val paper = MockBukkitTestRuntime.open()
    val world = paper.server.addSimpleWorld("prepared_async")
    val plugin = paper.createSimplePlugin("PreparedAsyncTest")
    val key = NamespacedKey(plugin, "mine_working_v1")
    private val supervisor = RuntimeTaskSupervisor(BukkitTaskScheduler(plugin)).also { it.activate() }
    private val worker = BoundedAsyncExecutor("prepared-scene-test")
    val workerCalls = AtomicInteger()
    val nativeDecodeCalls = AtomicInteger()
    val pendingComputations = AtomicInteger()
    var computeGate: CompletableFuture<Unit>? = null
    var failNextCapture: Throwable? = null
    var captureSubmissions = 0
    private var nativeCalls = 0
    var maxNativeCallsPerTick = 0
    var nativeTicks = 0
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
            return supervisor.capture(token, isCurrent, step)
        }
        override fun <T> callSync(token: RuntimeTaskSupervisor.Token, task: () -> T) = supervisor.callSync(token, task)
        override fun <T> compute(task: () -> T): CompletableFuture<T> {
            pendingComputations.incrementAndGet()
            val gate = computeGate
            return worker.submit {
                check(Thread.currentThread() !== nativeThread)
                gate?.join()
                workerCalls.incrementAndGet()
                task()
            }.whenComplete { _, _ -> pendingComputations.decrementAndGet() }
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
        chunkRetention = { check(Thread.currentThread() === nativeThread); AutoCloseable {} },
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

    fun tick(beforeTick: () -> Unit = {}) {
        nativeCalls = 0
        beforeTick()
        owner.process(NATIVE_STEP_BUDGET * 2) { true }
        paper.performTicks(1)
        maxNativeCallsPerTick = maxOf(maxNativeCallsPerTick, nativeCalls)
        if (nativeCalls > 0) nativeTicks++
        Thread.sleep(2)
    }

    fun until(beforeTick: () -> Unit = {}, done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "Scene did not finish its bounded queue" }
            tick(beforeTick)
        }
    }

    override fun close() {
        computeGate?.complete(Unit)
        owner.clearQueues()
        supervisor.close()
        worker.closeAsync().join()
        paper.close()
    }
}

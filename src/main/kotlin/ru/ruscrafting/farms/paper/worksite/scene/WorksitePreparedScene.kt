package ru.ruscrafting.farms.paper.worksite.scene

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.worksite.captureLoadedChunks
import kotlinx.collections.immutable.persistentMapOf
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.floor

/** One durable block in a temporary scene. [originalData] is retained until retirement. */
internal data class WorksitePreparedSceneRecord(
    val world: String,
    val zoneId: String,
    val sequence: Long,
    val sceneId: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalData: String,
    val activeData: String,
    val marker: String,
    val totalRecords: Int,
)

internal data class WorksitePreparedScene(
    val world: World,
    val zoneId: String,
    val sequence: Long,
    val sceneId: Int,
    val surface: Location,
    val start: Location,
    val end: Location,
    val records: List<WorksitePreparedSceneRecord>,
) {
    internal var completionProbe: (() -> Boolean)? = null
    /** Production readiness is maintained by bounded native validation/build steps. */
    val ready: Boolean
        get() = completionProbe?.invoke() ?: (records.isNotEmpty() && records.all { record ->
            world.isChunkLoaded(record.x shr 4, record.z shr 4) &&
                world.getBlockAt(record.x, record.y, record.z).blockData.asString == record.activeData
        })
}

internal enum class WorksitePreparedSceneEnsureResult {
    BUILDING,
    READY,
    UNAVAILABLE,
}

/** Adapter preserving a worksite's existing chunk-PDC binary format. */
internal interface WorksitePreparedSceneCodec {
    fun decode(
        raw: ByteArray,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): List<WorksitePreparedSceneRecord>

    fun encode(
        records: List<WorksitePreparedSceneRecord>,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): ByteArray

    fun foreignJournalOverlaps(chunk: Chunk, positions: Collection<Triple<Int, Int, Int>>): Boolean

    /** Main captures any native PDC data; the returned operation only reads detached values. */
    fun captureForeignJournalCheck(chunk: Chunk, positions: Collection<Triple<Int, Int, Int>>): () -> Boolean {
        val overlaps = foreignJournalOverlaps(chunk, positions)
        return { overlaps }
    }
}

internal fun interface WorksitePreparedSceneChunkRetention {
    fun retain(chunk: Chunk): AutoCloseable
}

/** Platform adapter kept outside the neutral journal/queue owner. */
internal fun interface WorksitePreparedSceneBlockDataDecoder {
    fun decode(raw: String): BlockData
}

/**
 * Activity-neutral owner for a prepared block scene.
 *
 * Callers own geometry and domain projection. This owner owns the durable journal,
 * atomic reservation, bounded build/restore queues, chunk leases and reconstruction.
 */
internal class WorksitePreparedSceneOwner(
    plugin: Plugin,
    private val namespace: String,
    private val codec: WorksitePreparedSceneCodec,
    private val chunkRetention: WorksitePreparedSceneChunkRetention,
    private val blockDataDecoder: WorksitePreparedSceneBlockDataDecoder,
    private val logger: Logger = plugin.logger,
    private val preserveEdits: (WorksitePreparedSceneRecord) -> Boolean = { false },
    private val tasks: WorksiteTaskPort? = null,
) {
    private data class SceneKey(val world: String, val zoneId: String, val sequence: Long, val sceneId: Int)
    private data class RecordKey(val world: String, val x: Int, val y: Int, val z: Int)
    private data class ProjectionChunkKey(val scene: SceneKey, val chunkX: Int, val chunkZ: Int)
    private data class ProjectionChunkState(val records: Int, val expected: Int, val active: Boolean)

    private val journalKey = NamespacedKey(plugin, "${namespace}_v1")
    private val buildQueue = ArrayDeque<WorksitePreparedSceneRecord>()
    private val restoreQueue = ArrayDeque<WorksitePreparedSceneRecord>()
    private val queuedBuilds = linkedSetOf<RecordKey>()
    private val queuedRestores = linkedSetOf<RecordKey>()
    private val buildingKeys = linkedSetOf<RecordKey>()
    private val buildingSceneCounts = mutableMapOf<SceneKey, Int>()
    private val completedBuilds = linkedSetOf<SceneKey>()
    private val projectionChunks = linkedMapOf<ProjectionChunkKey, ProjectionChunkState>()
    private val preparations = linkedMapOf<SceneKey, ArrayDeque<List<WorksitePreparedSceneRecord>>>()
    private val rejectedPreparations = linkedSetOf<SceneKey>()
    private val ticketedChunks = linkedMapOf<Triple<String, Int, Int>, AutoCloseable>()
    private val scenes = mutableMapOf<SceneKey, WorksitePreparedScene>()
    private val scenePositions = mutableMapOf<SceneKey, Set<Triple<Int, Int, Int>>>()
    private var mutationEpoch = 0L
    private var cacheEpoch = 0L
    private var journalCache = persistentMapOf<Triple<String, Int, Int>, JournalSnapshot>()
    private val loadingJournals = mutableMapOf<Triple<String, Int, Int>, CompletableFuture<JournalSnapshot>>()
    private data class JournalSnapshot(
        val records: List<WorksitePreparedSceneRecord>,
        val byPosition: Map<RecordKey, WorksitePreparedSceneRecord>,
        val byScene: Map<SceneKey, List<WorksitePreparedSceneRecord>>,
        val worldId: java.util.UUID? = null,
    )
    private fun snapshot(records: List<WorksitePreparedSceneRecord>) =
        JournalSnapshot(records, records.associateBy { it.key() }, records.groupBy { it.sceneKey() })
    private val chunkRevisions = mutableMapOf<Triple<String, Int, Int>, Long>()
    private val sceneJournalCounts = mutableMapOf<Pair<java.util.UUID, SceneKey>, Int>()
    private val worldWarmups = mutableMapOf<java.util.UUID, CompletableFuture<Unit>>()
    private val warmedWorlds = mutableSetOf<java.util.UUID>()
    private val worldDiscoveryRevisions = mutableMapOf<java.util.UUID, Long>()
    private val sceneRecoveries = mutableMapOf<SceneKey, CompletableFuture<Unit>>()
    private val sceneChunkPositions = mutableMapOf<SceneKey, Set<Pair<Int, Int>>>()
    private val buildingAdmissions = mutableSetOf<SceneKey>()
    private data class RestoreKey(val world: String, val zone: String, val sequence: Long, val scene: Int?)
    private val restoreAdmissions = mutableMapOf<RestoreKey, Any>()
    private val admittedRestores = mutableSetOf<RestoreKey>()
    private var preparationAdmission: CompletableFuture<Unit>? = null
    private val preparationReceipts = mutableMapOf<SceneKey, CompletableFuture<Boolean>>()
    private val restoreCounts = mutableMapOf<String, Int>()
    private val retiringZones = mutableSetOf<String>()
    private var processing: CompletableFuture<Unit>? = null

    private fun uncount(snapshot: JournalSnapshot?) {
        val worldId = snapshot?.worldId ?: return
        snapshot.byScene.forEach { (scene, records) ->
            val key = worldId to scene
            val remaining = (sceneJournalCounts[key] ?: records.size) - records.size
            if (remaining == 0) sceneJournalCounts.remove(key) else sceneJournalCounts[key] = remaining
        }
    }
    private fun invalidateCache(key: Triple<String, Int, Int>) {
        uncount(journalCache[key])
        journalCache = journalCache.remove(key)
        chunkRevisions[key] = (chunkRevisions[key] ?: 0L) + 1
        loadingJournals.remove(key)?.cancel(false)
    }
    private fun cache(chunk: Chunk, snapshot: JournalSnapshot) {
        val key = Triple(chunk.world.name, chunk.x, chunk.z)
        uncount(journalCache[key])
        val worldId = chunk.world.uid
        snapshot.byScene.forEach { (scene, records) ->
            val countKey = worldId to scene
            sceneJournalCounts[countKey] = (sceneJournalCounts[countKey] ?: 0) + records.size
        }
        journalCache = journalCache.put(key, snapshot.copy(worldId = worldId))
        chunkRevisions[key] = (chunkRevisions[key] ?: 0L) + 1
    }
    private fun cache(chunk: Chunk, records: List<WorksitePreparedSceneRecord>) {
        // Call only for bounded compatibility writes; large snapshots are indexed by the worker.
        cache(chunk, snapshot(records))
    }
    private var asyncMutation: CompletableFuture<Boolean>? = null

    private data class DetachedChunk(
        val world: String, val x: Int, val z: Int, val minHeight: Int, val maxHeight: Int, val raw: ByteArray?,
        val foreignCheck: () -> Boolean = { false },
    )
    private data class Projection(
        val chunk: DetachedChunk, val records: List<WorksitePreparedSceneRecord>,
        val changed: List<WorksitePreparedSceneRecord>, val encoded: ByteArray,
        val indexed: JournalSnapshot,
    )

    /** The future completes only after the exact journal and native projection are both applied. */
    fun projectAsync(
        scene: WorksitePreparedScene,
        changes: Map<Triple<Int, Int, Int>, String>,
        isCurrent: () -> Boolean = { true },
    ): CompletableFuture<Boolean> {
        val work = tasks ?: return CompletableFuture.completedFuture(project(scene, changes))
        require(changes.size <= 1_024) { "Prepared scene projection is too large" }
        if (changes.isEmpty()) return CompletableFuture.completedFuture(true)
        val cached = scenes[scene.key()] ?: return CompletableFuture.completedFuture(false)
        val key = cached.key()
        if (asyncMutation != null || processing != null || key in preparations || key in buildingSceneCounts || !isCurrent()) {
            return CompletableFuture.completedFuture(false)
        }
        val positions = scenePositions[key] ?: return CompletableFuture.completedFuture(false)
        require(changes.keys.all { it in positions }) { "Prepared scene projection escapes its journal" }
        require(changes.values.all { it.length in 1..512 && '\n' !in it && '\r' !in it })
        val epoch = mutationEpoch
        val token = work.lifecycleToken()
        val world = cached.world
        val worldName = world.name
        val minHeight = world.minHeight
        val maxHeight = world.maxHeight
        val groups = changes.entries.groupBy { it.key.first shr 4 to (it.key.third shr 4) }
        val captured = mutableListOf<DetachedChunk>()
        val chunks = groups.keys.iterator()
        val result = CompletableFuture<Boolean>()
        asyncMutation = result
        fun current() = mutationEpoch == epoch && scenes[key] === cached && Bukkit.getWorld(worldName) === world && isCurrent()
        work.capture(token, ::current) {
            if (!chunks.hasNext()) true else {
                val (x, z) = chunks.next()
                check(world.isChunkLoaded(x, z)) { "Projection chunk is unloaded" }
                val chunk = world.getChunkAt(x, z)
                captured += DetachedChunk(worldName, x, z, minHeight, maxHeight,
                    chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY))
                !chunks.hasNext()
            }
        }.thenCompose {
            work.compute {
                captured.map { chunk ->
                    val records = chunk.raw?.let { codec.decode(it, chunk.world, chunk.x, chunk.z, chunk.minHeight, chunk.maxHeight) }.orEmpty()
                    val changeMap = groups.getValue(chunk.x to chunk.z).associate { it.key to it.value }
                    val owned = records.filter { it.zoneId == key.zoneId && it.sequence == key.sequence && it.sceneId == key.sceneId }
                        .associateBy { Triple(it.x, it.y, it.z) }
                    require(changeMap.keys.all(owned::containsKey)) { "Prepared scene projection journal identity is missing" }
                    val replacement = records.map { record -> changeMap[Triple(record.x, record.y, record.z)]
                        ?.let { record.copy(activeData = it) } ?: record }
                    val changed = replacement.filter { Triple(it.x, it.y, it.z) in changeMap }
                    Projection(chunk, replacement, changed,
                        codec.encode(replacement, chunk.world, chunk.x, chunk.z, chunk.minHeight, chunk.maxHeight), snapshot(replacement))
                }
            }
        }.thenCompose { projections ->
            work.callSync(token) {
                val palette = changes.values.distinct().iterator()
                work.capture(token, ::current) {
                    if (!palette.hasNext()) true else { blockDataDecoder.decode(palette.next()); !palette.hasNext() }
                }.thenCompose {
                    // Capture completion is on Paper; all owner writes are suspended until this mutation finishes.
                    val pending = projections.iterator()
                    work.capture(token, ::current) {
                        if (!pending.hasNext()) true else {
                            val projection = pending.next()
                            check(world.isChunkLoaded(projection.chunk.x, projection.chunk.z)) { "Projection chunk unloaded before journal commit" }
                            world.getChunkAt(projection.chunk.x, projection.chunk.z).persistentDataContainer
                                .set(journalKey, PersistentDataType.BYTE_ARRAY, projection.encoded)
                            cache(world.getChunkAt(projection.chunk.x, projection.chunk.z), projection.indexed)
                            !pending.hasNext()
                        }
                    }
                }.thenCompose {
                    val pending = projections.asSequence().flatMap { it.changed.asSequence() }.iterator()
                    work.capture(token, ::current) {
                        if (!pending.hasNext()) true else {
                            val record = pending.next()
                            check(apply(record, record.activeData)) { "Native scene projection failed" }
                            !pending.hasNext()
                        }
                    }
                }.thenApply {
                    completedBuilds += key
                    true
                }
            }.thenCompose { it }
        }.whenComplete { applied, failure ->
            work.callSync(token) {
                if (asyncMutation === result) asyncMutation = null
                if (failure == null) result.complete(applied)
                else {
                    logger.log(Level.WARNING, "Could not finish prepared scene projection ${cached.zoneId}/${cached.sequence}; journal remains recoverable", failure)
                    result.completeExceptionally(failure)
                }
            }.exceptionally { result.completeExceptionally(it); null }
        }
        return result
    }

    fun prepare(scenePlans: Collection<WorksitePreparedScene>): Boolean {
        require(scenePlans.isNotEmpty()) { "Prepared scene commit is empty" }
        if (tasks != null) {
            if (scenePlans.any { it.key() in scenes || restoring(it.zoneId) }) return false
            return scenePlans.all { prepareAsync(it, 1_024) }
        }
        if (!scenePlans.all { validateBlockData(it.records) }) return false
        if (!commit(scenePlans)) return false
        scenePlans.forEach { scene ->
            scenes[scene.key()] = scene
            scenePositions[scene.key()] = scene.records.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
            completedBuilds.remove(scene.key())
            ticket(scene)
            enqueueBuild(scene.records)
        }
        return true
    }

    /** Completes after every admitted scene has its exact journal committed and build work enqueued. */
    fun prepareAllAsync(scenePlans: Collection<WorksitePreparedScene>): CompletableFuture<Boolean> {
        require(scenePlans.isNotEmpty())
        if (tasks == null) return CompletableFuture.completedFuture(prepare(scenePlans))
        if (scenePlans.any { it.key() in scenes || restoring(it.zoneId) }) return CompletableFuture.completedFuture(false)
        val receipts = scenePlans.map { scene ->
            if (!prepareAsync(scene, 1_024)) CompletableFuture.completedFuture(false)
            else requireNotNull(preparationReceipts[scene.key()])
        }
        return CompletableFuture.allOf(*receipts.toTypedArray()).thenApply {
            val committed = receipts.all { it.join() }
            if (!committed) scenePlans.forEach { beginRestore(it.world, it.zoneId, it.sequence, it.sceneId) }
            committed
        }
    }

    /** Large scenes journal bounded slices before building them; partial journals recover through the same owner. */
    fun prepareIncrementally(scene: WorksitePreparedScene, recordsPerSlice: Int = 1_024): Boolean {
        require(recordsPerSlice in 1..4_096)
        require(scene.records.isNotEmpty())
        if (tasks != null) return prepareAsync(scene, recordsPerSlice)
        val key = scene.key()
        if (key in scenes || key in preparations || restoring(scene.zoneId)) return false
        require(scene.records.all {
            it.world == scene.world.name && it.zoneId == scene.zoneId && it.sequence == scene.sequence &&
                it.sceneId == scene.sceneId && it.totalRecords == scene.records.size
        })
        require(scene.records.map { it.key() }.toSet().size == scene.records.size) { "Prepared scene has duplicate positions" }
        if (!validateBlockData(scene.records)) return false
        scenes[key] = scene
        scenePositions[key] = scene.records.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
        completedBuilds.remove(key)
        rejectedPreparations.remove(key)
        ticket(scene)
        preparations[key] = ArrayDeque(scene.records.chunked(recordsPerSlice))
        return true
    }

    private data class ScenePreparation(
        val positions: Set<Triple<Int, Int, Int>>,
        val chunks: Set<Pair<Int, Int>>,
        val palette: Set<String>,
        val slices: List<List<WorksitePreparedSceneRecord>>,
    )

    private fun prepareAsync(scene: WorksitePreparedScene, recordsPerSlice: Int): Boolean {
        val work = requireNotNull(tasks)
        val key = scene.key()
        if (key in scenes || key in preparations || restoring(scene.zoneId)) return false
        val epoch = mutationEpoch
        val token = work.lifecycleToken()
        val worldName = scene.world.name
        val records = scene.records
        val zoneId = scene.zoneId
        val sequence = scene.sequence
        val sceneId = scene.sceneId
        scenes[key] = scene
        val receipt = CompletableFuture<Boolean>()
        preparationReceipts[key] = receipt
        scene.completionProbe = { isComplete(scene) }
        preparations[key] = ArrayDeque() // Admission is visible while immutable planning runs.
        completedBuilds.remove(key)
        rejectedPreparations.remove(key)
        fun current() = mutationEpoch == epoch && scenes[key] === scene && Bukkit.getWorld(worldName) === scene.world
        work.compute {
            require(records.all { it.world == worldName && it.zoneId == zoneId && it.sequence == sequence &&
                it.sceneId == sceneId && it.totalRecords == records.size })
            val positions = records.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
            require(positions.size == records.size) { "Prepared scene has duplicate positions" }
            ScenePreparation(positions, records.mapTo(linkedSetOf()) { it.x shr 4 to (it.z shr 4) },
                records.flatMapTo(linkedSetOf()) { listOf(it.originalData, it.activeData) }, records.chunked(recordsPerSlice))
        }.thenCompose { plan ->
            work.callSync(token) {
                check(current()) { "Scene preparation superseded" }
                scenePositions[key] = plan.positions
                sceneChunkPositions[key] = plan.chunks
                val palette = plan.palette.iterator()
                work.capture(token, ::current) {
                    if (!palette.hasNext()) true else { blockDataDecoder.decode(palette.next()); !palette.hasNext() }
                }.thenCompose {
                    val chunks = plan.chunks.iterator()
                    work.capture(token, ::current) {
                        if (!chunks.hasNext()) true else {
                            val (x, z) = chunks.next()
                            check(scene.world.isChunkLoaded(x, z)) { "Prepared scene chunk is unloaded" }
                            val chunkKey = Triple(worldName, x, z)
                            if (chunkKey !in ticketedChunks) ticketedChunks[chunkKey] = chunkRetention.retain(scene.world.getChunkAt(x, z))
                            !chunks.hasNext()
                        }
                    }
                }.thenApply { preparations[key] = ArrayDeque(plan.slices); Unit }
            }.thenCompose { it }
        }.whenComplete { _, failure ->
            if (failure != null) work.callSync(token) {
                receipt.completeExceptionally(failure)
                if (scenes[key] === scene) {
                    rejectedPreparations += key
                    beginRestore(scene.world, scene.zoneId, scene.sequence, scene.sceneId)
                    logger.log(Level.WARNING, "Prepared scene planning failed ${scene.zoneId}/${scene.sequence}", failure)
                }
            }.exceptionally { receipt.completeExceptionally(it); null }
        }
        return true
    }

    /** Serial owner mutation: captured chunk blobs are decoded/encoded only on the worker. */
    private fun commitAsync(scene: WorksitePreparedScene): CompletableFuture<Boolean> {
        val work = requireNotNull(tasks)
        if (asyncMutation != null) return CompletableFuture.completedFuture(false)
        val key = scene.key()
        val ownerScene = scenes[key] ?: return CompletableFuture.completedFuture(false)
        val token = work.lifecycleToken()
        val epoch = mutationEpoch
        val world = scene.world
        val worldName = world.name
        val minimum = world.minHeight
        val maximum = world.maxHeight
        val result = CompletableFuture<Boolean>()
        asyncMutation = result
        val records = scene.records
        val groups = records.groupBy { it.x shr 4 to (it.z shr 4) }
        val chunks = groups.keys.iterator()
        val captured = mutableListOf<DetachedChunk>()
        fun current() = mutationEpoch == epoch && scenes[key] === ownerScene && Bukkit.getWorld(worldName) === world
        work.capture(token, ::current) {
            if (!chunks.hasNext()) true else {
                val (x, z) = chunks.next()
                check(world.isChunkLoaded(x, z)) { "Prepared scene chunk is unloaded" }
                val chunk = world.getChunkAt(x, z)
                captured += DetachedChunk(worldName, x, z, minimum, maximum,
                    chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY),
                    codec.captureForeignJournalCheck(chunk, groups.getValue(x to z).map { Triple(it.x, it.y, it.z) }))
                !chunks.hasNext()
            }
        }.thenCompose {
            work.compute {
                captured.map { chunk ->
                    require(!chunk.foreignCheck()) { "Foreign temporary journal overlaps another scene" }
                    val existing = chunk.raw?.let { codec.decode(it, chunk.world, chunk.x, chunk.z, chunk.minHeight, chunk.maxHeight) }.orEmpty()
                    val additions = groups.getValue(chunk.x to chunk.z)
                    val positions = additions.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
                    require(existing.none { Triple(it.x, it.y, it.z) in positions }) { "Prepared scene journal overlaps another scene" }
                    val combined = existing + additions
                    Projection(chunk, combined, additions,
                        codec.encode(combined, chunk.world, chunk.x, chunk.z, chunk.minHeight, chunk.maxHeight), snapshot(combined))
                }
            }
        }.thenCompose { projections ->
            work.callSync(token) {
                run {
                    val pending = records.iterator()
                    work.capture(token, ::current) {
                        if (!pending.hasNext()) true else {
                            val record = pending.next()
                            check(world.isChunkLoaded(record.x shr 4, record.z shr 4))
                            require(world.getBlockAt(record.x, record.y, record.z).blockData.asString == record.originalData) {
                                "Prepared scene placement changed before commit"
                            }
                            !pending.hasNext()
                        }
                    }
                }.thenCompose {
                    val pending = projections.iterator()
                    work.capture(token, ::current) {
                        if (!pending.hasNext()) true else {
                            val projection = pending.next()
                            check(world.isChunkLoaded(projection.chunk.x, projection.chunk.z))
                            world.getChunkAt(projection.chunk.x, projection.chunk.z).persistentDataContainer
                                .set(journalKey, PersistentDataType.BYTE_ARRAY, projection.encoded)
                            cache(world.getChunkAt(projection.chunk.x, projection.chunk.z), projection.indexed)
                            !pending.hasNext()
                        }
                    }
                }.thenApply { true }
            }.thenCompose { it }
        }.whenComplete { success, failure ->
            work.callSync(token) {
                if (asyncMutation === result) asyncMutation = null
                if (failure == null) result.complete(success)
                else {
                    logger.log(Level.WARNING, "Prepared scene journal commit failed ${scene.zoneId}/${scene.sequence}", failure)
                    result.complete(false)
                }
            }.exceptionally { result.completeExceptionally(it); null }
        }
        return result
    }

    fun preparationFailed(zoneId: String, sequence: Long, sceneId: Int): Boolean =
        rejectedPreparations.any { it.zoneId == zoneId && it.sequence == sequence && it.sceneId == sceneId }

    /** Journal-first projection for a bounded set of scene-owned block changes. */
    fun project(scene: WorksitePreparedScene, changes: Map<Triple<Int, Int, Int>, String>): Boolean {
        require(changes.size <= 1_024) { "Prepared scene projection is too large" }
        if (changes.isEmpty()) return true
        val cached = scenes[scene.key()] ?: return false
        val key = cached.key()
        if (key in preparations || key in buildingSceneCounts) return false
        val positions = scenePositions.getOrPut(key) { cached.records.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) } }
        require(changes.keys.all { it in positions }) { "Prepared scene projection escapes its journal" }
        require(changes.values.all { it.length in 1..512 && '\n' !in it && '\r' !in it }) {
            "Prepared scene projection contains invalid BlockData"
        }
        val byChunk = changes.entries.groupBy { it.key.first shr 4 to (it.key.third shr 4) }
        val previous = linkedMapOf<Chunk, ByteArray?>()
        val projected = linkedMapOf<Chunk, List<WorksitePreparedSceneRecord>>()
        return try {
            byChunk.forEach { (chunkPosition, chunkChanges) ->
                require(cached.world.isChunkLoaded(chunkPosition.first, chunkPosition.second)) { "Projection chunk is unloaded" }
                val chunk = cached.world.getChunkAt(chunkPosition.first, chunkPosition.second)
                val current = read(chunk) ?: error("Prepared scene journal is unreadable")
                val changeMap = chunkChanges.associate { it.key to it.value }
                val owned = current.filter { it.sceneKey() == key }
                require(changeMap.keys.all { position -> owned.any { it.x == position.first && it.y == position.second && it.z == position.third } }) {
                    "Prepared scene projection journal identity is missing"
                }
                previous[chunk] = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY)
                val replacement = current.map { currentRecord ->
                    changeMap[Triple(currentRecord.x, currentRecord.y, currentRecord.z)]
                        ?.let { activeData -> currentRecord.copy(activeData = activeData) } ?: currentRecord
                }
                write(chunk, replacement)
                projected[chunk] = replacement.filter { Triple(it.x, it.y, it.z) in changeMap.keys && it.sceneKey() == key }
            }
            val failed = projected.values.flatten().filterNot { apply(it, it.activeData) }
            if (failed.isNotEmpty()) {
                enqueueBuild(failed)
                false
            } else {
                completedBuilds += key
                true
            }
        } catch (failure: Throwable) {
            previous.forEach { (chunk, payload) ->
                if (payload == null) chunk.persistentDataContainer.remove(journalKey)
                else chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, payload)
            }
            logger.log(Level.WARNING, "Could not project prepared scene ${cached.zoneId}/${cached.sequence}", failure)
            false
        }
    }

    fun ensurePreparedScene(
        world: World,
        zoneId: String,
        sequence: Long,
        sceneId: Int,
        surface: Location,
        recoveryRadius: Int,
        prepare: () -> WorksitePreparedScene?,
        start: Location? = null,
        end: Location? = null,
        startMarker: String? = null,
        endMarker: String? = null,
        shouldBuild: (WorksitePreparedScene) -> Boolean = { !isComplete(it) },
    ): Pair<WorksitePreparedSceneEnsureResult, WorksitePreparedScene?> {
        val existing = scene(
            world, zoneId, sequence, sceneId, surface, recoveryRadius,
            start, end, startMarker, endMarker,
        )
        if (existing != null) {
            val needsBuild = shouldBuild(existing)
            if (tasks == null) {
                ticket(existing)
                if (needsBuild) enqueueBuild(existing.records) else completedBuilds += existing.key()
            } else if (needsBuild && existing.key() !in preparations) enqueueBuildAsync(existing)
            return (if (!needsBuild) WorksitePreparedSceneEnsureResult.READY
            else WorksitePreparedSceneEnsureResult.BUILDING) to existing
        }
        if (tasks != null && (SceneKey(world.name, zoneId, sequence, sceneId) in sceneRecoveries || world.uid !in warmedWorlds)) {
            return WorksitePreparedSceneEnsureResult.BUILDING to null
        }
        if (hasLoadedSceneRecordsInternal(world, zoneId, sequence, sceneId)) {
            beginRestore(world, zoneId, sequence)
            return WorksitePreparedSceneEnsureResult.BUILDING to null
        }
        val plan = prepare() ?: return WorksitePreparedSceneEnsureResult.UNAVAILABLE to null
        require(plan.world === world && plan.zoneId == zoneId && plan.sequence == sequence && plan.sceneId == sceneId)
        if (!prepare(listOf(plan))) return WorksitePreparedSceneEnsureResult.UNAVAILABLE to null
        return WorksitePreparedSceneEnsureResult.BUILDING to plan
    }

    fun scene(
        world: World,
        zoneId: String,
        sequence: Long,
        sceneId: Int,
        surface: Location,
        recoveryRadius: Int,
        start: Location? = null,
        end: Location? = null,
        startMarker: String? = null,
        endMarker: String? = null,
    ): WorksitePreparedScene? {
        val key = SceneKey(world.name, zoneId, sequence, sceneId)
        scenes[key]?.let { cached ->
            if (isComplete(cached)) completedBuilds += key
            return cached
        }
        if (tasks != null) {
            recoverScene(world, key, surface, recoveryRadius, start, end, startMarker, endMarker)
            return scenes[key]
        }
        var records = loadedRecords(world, zoneId, sequence, sceneId)
        if (records.isEmpty()) return null
        val expected = records.first().totalRecords
        if (records.size != expected) {
            loadRecoveryChunks(world, surface, recoveryRadius)
            records = loadedRecords(world, zoneId, sequence, sceneId)
        }
        ticket(world, records)
        if (records.size != expected || records.any { it.totalRecords != expected }) return null
        if (!validateBlockData(records)) return null
        val resolvedStart = start ?: markerLocation(world, records, startMarker) ?: return null
        val resolvedEnd = end ?: markerLocation(world, records, endMarker) ?: return null
        return WorksitePreparedScene(world, zoneId, sequence, sceneId, surface, resolvedStart, resolvedEnd, records)
            .also {
                scenes[key] = it
                scenePositions[key] = it.records.mapTo(hashSetOf()) { record -> Triple(record.x, record.y, record.z) }
                if (isComplete(it)) completedBuilds += key
            }
    }

    /** O(1) completion signal maintained by the bounded build queue. */
    fun isComplete(scene: WorksitePreparedScene): Boolean = scene.key().let { key ->
        key in completedBuilds && key !in preparations && key !in buildingSceneCounts && key !in buildingAdmissions && processing == null
    }

    /** Convenience overload for callers that already resolved marker locations. */
    fun scene(
        world: World,
        zoneId: String,
        sequence: Long,
        sceneId: Int,
        surface: Location,
        start: Location,
        end: Location,
    ): WorksitePreparedScene? = scene(
        world = world,
        zoneId = zoneId,
        sequence = sequence,
        sceneId = sceneId,
        surface = surface,
        recoveryRadius = 0,
        start = start,
        end = end,
    )

    fun beginRestore(world: World, zoneId: String, sequence: Long) = beginRestoreInternal(world, zoneId, sequence, null)

    /** Narrow restore scope for owners that can retain multiple scenes in one shift sequence. */
    fun beginRestore(world: World, zoneId: String, sequence: Long, sceneId: Int) =
        beginRestoreInternal(world, zoneId, sequence, sceneId)

    private fun beginRestoreInternal(world: World, zoneId: String, sequence: Long, sceneId: Int?) {
        if (tasks != null) {
            beginRestoreAsync(world, zoneId, sequence, sceneId)
            return
        }
        mutationEpoch++
        preparations.keys.removeIf { it.world == world.name && it.zoneId == zoneId && it.sequence == sequence &&
            (sceneId == null || it.sceneId == sceneId) }
        scenes.keys.filter { it.world == world.name && it.zoneId == zoneId && it.sequence == sequence &&
            (sceneId == null || it.sceneId == sceneId) }
            .toList().forEach(scenes::remove)
        scenePositions.keys.removeIf { it.world == world.name && it.zoneId == zoneId && it.sequence == sequence &&
            (sceneId == null || it.sceneId == sceneId) }
        completedBuilds.removeIf { it.world == world.name && it.zoneId == zoneId && it.sequence == sequence &&
            (sceneId == null || it.sceneId == sceneId) }
        projectionChunks.keys.removeIf { it.scene.world == world.name && it.scene.zoneId == zoneId &&
            it.scene.sequence == sequence && (sceneId == null || it.scene.sceneId == sceneId) }
        val records = world.loadedChunks.asSequence().flatMap { read(it).orEmpty().asSequence() }
            .filter { it.zoneId == zoneId && it.sequence == sequence && (sceneId == null || it.sceneId == sceneId) }
            .toList()
        cancelBuild(records)
        enqueueRestore(records.sortedByDescending(WorksitePreparedSceneRecord::y))
    }

    fun hasPendingBlock(location: Location): Boolean {
        val key = RecordKey(location.world.name, location.blockX, location.blockY, location.blockZ)
        return key in queuedBuilds || key in queuedRestores
    }

    /** Bounded recovery probe; callers must ensure the relevant chunks are loaded first. */
    fun hasLoadedSceneRecords(world: World, zoneId: String, sequence: Long, sceneId: Int): Boolean =
        hasLoadedSceneRecordsInternal(world, zoneId, sequence, sceneId)

    fun hasZoneRecordsAsync(world: World, zoneId: String, sequence: Long): CompletableFuture<Boolean> {
        if (tasks == null) return CompletableFuture.completedFuture(world.loadedChunks.any { chunk ->
            read(chunk).orEmpty().any { it.zoneId == zoneId && it.sequence == sequence }
        })
        val worldId = world.uid
        val worldName = world.name
        return warmWorld(world).thenApply {
            // The codec admits scene ids only in 0..15.
            (0..15).any { (sceneJournalCounts[worldId to SceneKey(worldName, zoneId, sequence, it)] ?: 0) > 0 }
        }
    }

    /** True while a durable record still owns this position, including an in-flight queue. */
    fun protects(location: Location): Boolean {
        val key = RecordKey(location.world.name, location.blockX, location.blockY, location.blockZ)
        if (key in queuedBuilds || key in queuedRestores || scenePositions.any { (scene, positions) ->
            scene.world == location.world.name && Triple(location.blockX, location.blockY, location.blockZ) in positions
        }) return true
        if (!location.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return false
        val records = read(location.world.getChunkAt(location.blockX shr 4, location.blockZ shr 4))
        // Unknown journals protect the chunk until their worker validation finishes.
        if (records == null) return tasks != null
        val indexed = journalCache[Triple(location.world.name, location.blockX shr 4, location.blockZ shr 4)]
        return indexed?.byPosition?.containsKey(key) ?: records.any { it.key() == key }
    }

    fun restoring(zoneId: String): Boolean = (restoreCounts[zoneId] ?: 0) > 0 || zoneId in retiringZones ||
        restoreAdmissions.keys.any { it.zone == zoneId }

    /** O(1) lifecycle signal for callers waiting for baseline construction. */
    fun isBuilding(zoneId: String, sequence: Long, sceneId: Int? = null): Boolean =
        (buildingSceneCounts.keys + preparations.keys).any {
            it.zoneId == zoneId && it.sequence == sequence && (sceneId == null || it.sceneId == sceneId)
        }

    fun process(
        limit: Int,
        retryRejected: Boolean = true,
        allowed: (WorksitePreparedSceneRecord) -> Boolean,
    ): Int {
        require(limit >= 1) { "Prepared scene block budget must be positive" }
        if (tasks != null) {
            processAsync(limit, retryRejected, allowed)
            return 0
        }
        if (asyncMutation != null) return 0
        processPreparation()
        if (asyncMutation != null) return 0
        val restored = processQueue(
            restoreQueue,
            queuedRestores,
            limit,
            restore = true,
            allowed = allowed,
            retryRejected = retryRejected,
        )
        val remaining = limit - restored
        val built = if (remaining > 0) processQueue(
            buildQueue,
            queuedBuilds,
            remaining,
            restore = false,
            allowed = allowed,
            retryRejected = retryRejected,
        ) else 0
        return restored + built
    }

    private class RetiredChunk(
        val chunk: Chunk,
        val snapshot: JournalSnapshot,
        val records: MutableList<WorksitePreparedSceneRecord> = mutableListOf(),
        val keys: MutableSet<RecordKey> = hashSetOf(),
    )

    private fun processAsync(limit: Int, retryRejected: Boolean, allowed: (WorksitePreparedSceneRecord) -> Boolean) {
        val work = requireNotNull(tasks)
        if (processing != null || asyncMutation != null) return
        processPreparation()
        if (asyncMutation != null) return
        // Selection is immutable, at most one existing owner budget, and has no native reads.
        val selected = ArrayDeque<Pair<WorksitePreparedSceneRecord, Boolean>>()
        repeat(minOf(limit, restoreQueue.size)) {
            val record = restoreQueue.removeFirst()
            queuedRestores.remove(record.key())
            restoreCounts.compute(record.zoneId) { _, count -> ((count ?: 1) - 1).takeIf { it > 0 } }
            selected += record to true
        }
        repeat(minOf(limit - selected.size, buildQueue.size)) {
            val record = buildQueue.removeFirst()
            queuedBuilds.remove(record.key())
            selected += record to false
        }
        if (selected.isEmpty()) return
        val token = work.lifecycleToken()
        val epoch = cacheEpoch
        val retired = linkedMapOf<Triple<String, Int, Int>, RetiredChunk>()
        val result = CompletableFuture<Unit>()
        processing = result
        val zones = selected.asSequence().filter { it.second }.mapTo(hashSetOf()) { it.first.zoneId }
        retiringZones += zones
        fun current() = cacheEpoch == epoch && processing === result
        fun requeue(record: WorksitePreparedSceneRecord, restore: Boolean) {
            if (restore) enqueueRestore(listOf(record)) else enqueue(listOf(record), buildQueue, queuedBuilds)
        }
        work.capture(token, ::current) {
            val (pending, restore) = selected.removeFirst()
            val key = pending.key()
            if (!restore && (key !in buildingKeys || restoreRequested(pending))) {
                forgetBuild(pending, completed = false)
            } else {
                val world = Bukkit.getWorld(pending.world)
                val cx = pending.x shr 4
                val cz = pending.z shr 4
                if (world == null || !world.isChunkLoaded(cx, cz)) requeue(pending, restore)
                else {
                    val chunk = world.getChunkAt(cx, cz)
                    readAsync(chunk)
                    val journal = journalCache[Triple(pending.world, cx, cz)]
                    val record = journal?.byPosition?.get(key)
                    when {
                        journal == null -> requeue(pending, restore)
                        record == null -> if (!restore) forgetBuild(pending, completed = false)
                        !allowed(record) -> {
                            if (retryRejected) requeue(record, restore)
                            else if (!restore) forgetBuild(record, completed = false)
                        }
                        !apply(record, if (restore) record.originalData else record.activeData) -> requeue(record, restore)
                        restore -> retired.getOrPut(Triple(record.world, cx, cz)) { RetiredChunk(chunk, journal) }.let {
                            it.records += record
                            it.keys += key
                        }
                        else -> forgetBuild(record, completed = true)
                    }
                }
            }
            selected.isEmpty()
        }.thenCompose {
            // The worker encodes once per changed chunk, after all per-block native steps in this batch.
            var writes = CompletableFuture.completedFuture(Unit)
            retired.values.forEach { batch ->
                writes = writes.thenCompose { work.callSync(token) {
                    check(current()) { "Scene queue batch superseded" }
                    retireAsync(batch.chunk, batch.snapshot.records, batch.keys)
                        .handle { _, failure -> failure }
                        .thenCompose { failure -> work.callSync(token) {
                            if (current() && failure != null) {
                                batch.records.forEach { enqueueRestore(listOf(it)) }
                                logger.log(Level.WARNING, "Could not retire restored scene batch; journal retained", failure)
                            }
                            Unit
                        } }
                }.thenCompose { it } }
            }
            writes
        }.whenComplete { _, failure -> work.callSync(token) {
            if (current()) {
                // A failed capture may have consumed some records, but its durable ledger still owns them.
                selected.forEach { (record, restore) -> requeue(record, restore) }
                if (failure != null) retired.values.forEach { batch -> batch.records.forEach { enqueueRestore(listOf(it)) } }
                retiringZones.removeAll(zones)
                completeRestores()
                processing = null
            }
            if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
        }.exceptionally { result.completeExceptionally(it); null } }
    }

    fun onChunkLoad(
        chunk: Chunk,
        active: (String, Long) -> Boolean,
        isActiveScene: (WorksitePreparedSceneRecord) -> Boolean = { true },
    ) {
        if (tasks != null) {
            warmedWorlds.remove(chunk.world.uid)
            worldDiscoveryRevisions[chunk.world.uid] = (worldDiscoveryRevisions[chunk.world.uid] ?: 0L) + 1
            invalidateCache(Triple(chunk.world.name, chunk.x, chunk.z))
            reconcileChunkAsync(chunk, active, isActiveScene)
            return
        }
        val records = read(chunk) ?: return
        val activeRecords = records.filter { record ->
            active(record.zoneId, record.sequence) && isActiveScene(record)
        }
        val projectionStatus = activeRecords.associate { it.key() to (preserveEdits(it) || hasActiveProjection(it)) }
        activeRecords.groupBy { it.sceneKey() }.forEach { (sceneKey, sceneRecords) ->
            projectionChunks[ProjectionChunkKey(sceneKey, chunk.x, chunk.z)] = ProjectionChunkState(
                records = sceneRecords.size,
                expected = sceneRecords.first().totalRecords,
                active = sceneRecords.all { projectionStatus[it.key()] == true },
            )
            markProjectionComplete(sceneKey)
        }
        enqueueBuild(activeRecords.filter { record ->
            record.sceneKey() !in completedBuilds && projectionStatus[record.key()] != true
        })
        enqueueRestore(records.filterNot { it in activeRecords }.sortedByDescending(WorksitePreparedSceneRecord::y))
    }

    fun reconcileLoaded(
        active: (String, Long) -> Boolean,
        isActiveScene: (WorksitePreparedSceneRecord) -> Boolean = { true },
    ) {
        if (tasks != null) tasks.captureLoadedChunks { onChunkLoad(it, active, isActiveScene) }
        else Bukkit.getWorlds().forEach { world ->
            world.loadedChunks.forEach { onChunkLoad(it, active, isActiveScene) }
        }
    }

    private fun reconcileChunkAsync(
        chunk: Chunk,
        active: (String, Long) -> Boolean,
        isActiveScene: (WorksitePreparedSceneRecord) -> Boolean,
    ) {
        val work = requireNotNull(tasks)
        val token = work.lifecycleToken()
        val epoch = cacheEpoch
        val world = chunk.world
        val x = chunk.x
        val z = chunk.z
        val activeRecords = mutableListOf<WorksitePreparedSceneRecord>()
        val inactiveRecords = mutableListOf<WorksitePreparedSceneRecord>()
        val projectionStatus = mutableMapOf<RecordKey, Boolean>()
        var capturedJournal: JournalSnapshot? = null
        fun current() = cacheEpoch == epoch && Bukkit.getWorld(world.name) === world && world.isChunkLoaded(x, z) &&
            (capturedJournal == null || journalCache[Triple(world.name, x, z)] === capturedJournal)
        readAsync(chunk).thenCompose { journal ->
            capturedJournal = journal
            work.callSync(token) {
                val records = journal.records.iterator()
                work.capture(token, ::current) {
                    if (!records.hasNext()) true else {
                        val record = records.next()
                        if (!restoreRequested(record) && active(record.zoneId, record.sequence) && isActiveScene(record)) {
                            activeRecords += record
                            projectionStatus[record.key()] = preserveEdits(record) || hasActiveProjection(record)
                        } else inactiveRecords += record
                        !records.hasNext()
                    }
                }
            }.thenCompose { it }
        }.thenCompose {
            work.compute {
                val summaries = activeRecords.groupBy { it.sceneKey() }.mapValues { (_, records) ->
                    ProjectionChunkState(records.size, records.first().totalRecords, records.all { projectionStatus[it.key()] == true })
                }
                Triple(summaries, activeRecords.filter { projectionStatus[it.key()] != true },
                    inactiveRecords.sortedByDescending(WorksitePreparedSceneRecord::y))
            }
        }.thenCompose { (summaries, building, restoring) -> work.callSync(token) {
            check(current()) { "Scene chunk reconciliation superseded" }
            summaries.forEach { (key, value) -> projectionChunks[ProjectionChunkKey(key, x, z)] = value; markProjectionComplete(key) }
            val builds = building.iterator()
            val restores = restoring.iterator()
            work.capture(token, ::current) {
                if (builds.hasNext()) enqueueBuild(listOf(builds.next()))
                else if (restores.hasNext()) enqueueRestore(listOf(restores.next()))
                !builds.hasNext() && !restores.hasNext()
            }
        }.thenCompose { it } }.whenComplete { _, failure ->
            logLoadedSceneRecoveryFailure(logger, failure)
        }
    }

    fun clearQueues() {
        preparationReceipts.values.forEach { it.completeExceptionally(java.util.concurrent.CancellationException("Scene owner closed")) }
        preparationReceipts.clear()
        mutationEpoch++
        cacheEpoch++
        journalCache = persistentMapOf()
        loadingJournals.values.toList().forEach { it.cancel(false) }
        loadingJournals.clear()
        chunkRevisions.clear()
        sceneJournalCounts.clear()
        worldWarmups.clear()
        warmedWorlds.clear()
        worldDiscoveryRevisions.clear()
        sceneRecoveries.clear()
        sceneChunkPositions.clear()
        buildingAdmissions.clear()
        restoreAdmissions.clear()
        admittedRestores.clear()
        preparationAdmission = null
        restoreCounts.clear()
        retiringZones.clear()
        processing = null
        asyncMutation = null
        preparations.clear()
        rejectedPreparations.clear()
        buildQueue.clear()
        restoreQueue.clear()
        queuedBuilds.clear()
        queuedRestores.clear()
        buildingKeys.clear()
        buildingSceneCounts.clear()
        completedBuilds.clear()
        projectionChunks.clear()
        scenePositions.clear()
        scenes.clear()
        ticketedChunks.entries.toList().forEach { (key, _) -> releaseTicket(key) }
    }

    private fun processPreparation() {
        if (preparationAdmission != null) return
        val (key, pending) = preparations.entries.firstOrNull() ?: return
        val scene = scenes[key] ?: run { preparations.remove(key); return }
        if (pending.isEmpty()) return // Worker planning still owns the admitted scene.
        val records = pending.removeFirst()
        if (tasks != null) {
            val token = tasks.lifecycleToken()
            val admission = CompletableFuture<Unit>()
            preparationAdmission = admission
            commitAsync(scene.copy(records = records)).thenCompose { committed ->
                if (scenes[key] !== scene) return@thenCompose CompletableFuture.completedFuture(Unit)
                if (!committed) {
                    preparations.remove(key)
                    rejectedPreparations += key
                    preparationReceipts[key]?.complete(false)
                    beginRestore(scene.world, scene.zoneId, scene.sequence)
                    CompletableFuture.completedFuture(Unit)
                } else {
                    val iterator = records.iterator()
                    tasks.capture(token, { scenes[key] === scene }) {
                        if (iterator.hasNext()) enqueueBuild(listOf(iterator.next()))
                        !iterator.hasNext()
                    }.thenApply {
                        if (pending.isEmpty() && scenes[key] === scene) {
                            preparations.remove(key)
                            preparationReceipts[key]?.complete(true)
                        }
                        Unit
                    }
                }
            }.whenComplete { _, failure -> tasks.callSync(token) {
                if (preparationAdmission === admission) preparationAdmission = null
                if (failure == null) admission.complete(Unit) else {
                    preparationReceipts[key]?.completeExceptionally(failure)
                    admission.completeExceptionally(failure)
                }
            }.exceptionally { admission.completeExceptionally(it); null } }
            return
        }
        if (!commit(listOf(scene.copy(records = records)))) {
            preparations.remove(key)
            rejectedPreparations += key
            beginRestore(scene.world, scene.zoneId, scene.sequence)
            return
        }
        enqueueBuild(records)
        if (pending.isEmpty()) preparations.remove(key)
    }

    private fun commit(scenePlans: Collection<WorksitePreparedScene>): Boolean {
        val allRecords = scenePlans.flatMap(WorksitePreparedScene::records)
        require(allRecords.map { listOf(it.world, it.x, it.y, it.z) }.distinct().size == allRecords.size) {
            "Prepared scenes overlap"
        }
        val scene = scenePlans.first()
        require(scenePlans.all { it.world === scene.world && it.zoneId == scene.zoneId && it.sequence == scene.sequence }) {
            "Prepared scenes must share world, zone and sequence"
        }
        require(allRecords.all { it.world == scene.world.name }) { "Prepared scene record belongs to another world" }
        val groups = allRecords.groupBy { it.x shr 4 to (it.z shr 4) }
        val prepared = linkedMapOf<Chunk, Pair<ByteArray?, ByteArray>>()
        return runCatching {
            groups.forEach { (chunkPosition, additions) ->
                require(scene.world.isChunkLoaded(chunkPosition.first, chunkPosition.second)) { "Prepared scene chunk unloaded during commit" }
                val chunk = scene.world.getChunkAt(chunkPosition.first, chunkPosition.second)
                require(scene.world.isChunkLoaded(chunk.x, chunk.z)) { "Prepared scene chunk unloaded during commit" }
                val current = read(chunk) ?: error("Prepared scene journal is unreadable")
                val positions = additions.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
                require(current.none { Triple(it.x, it.y, it.z) in positions }) { "Prepared scene journal overlaps another scene" }
                require(!codec.foreignJournalOverlaps(chunk, positions)) { "Foreign temporary journal overlaps another scene" }
                additions.forEach { record ->
                    require(scene.world.getBlockAt(record.x, record.y, record.z).blockData.asString == record.originalData) {
                        "Prepared scene placement changed before commit"
                    }
                }
                val encoded = encode(chunk, current + additions)
                prepared[chunk] = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY) to encoded
            }
            prepared.forEach { (chunk, payloads) ->
                chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, payloads.second)
                journalCache = journalCache.remove(Triple(chunk.world.name, chunk.x, chunk.z))
            }
            true
        }.getOrElse { failure ->
            prepared.forEach { (chunk, payloads) ->
                val previous = payloads.first
                if (previous == null) chunk.persistentDataContainer.remove(journalKey)
                else chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, previous)
            }
            logger.log(Level.SEVERE, "Could not durably commit prepared scene ${scene.zoneId}/${scene.sequence}", failure)
            false
        }
    }

    private fun retireAsync(chunk: Chunk, current: List<WorksitePreparedSceneRecord>, retired: Set<RecordKey>): CompletableFuture<Boolean> {
        val work = requireNotNull(tasks)
        val world = chunk.world
        val key = Triple(world.name, chunk.x, chunk.z)
        val minimum = world.minHeight
        val maximum = world.maxHeight
        val epoch = mutationEpoch
        val token = work.lifecycleToken()
        val result = CompletableFuture<Boolean>()
        asyncMutation = result
        work.compute {
            val remaining = current.filterNot { it.key() in retired }
            snapshot(remaining) to if (remaining.isEmpty()) null else codec.encode(remaining, key.first, key.second, key.third, minimum, maximum)
        }.thenCompose { (remaining, encoded) -> work.callSync(token) {
            check(mutationEpoch == epoch && Bukkit.getWorld(key.first) === world && world.isChunkLoaded(key.second, key.third)) {
                "Scene retirement superseded"
            }
            if (encoded == null) chunk.persistentDataContainer.remove(journalKey)
            else chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, encoded)
            cache(chunk, remaining)
            if (remaining.records.isEmpty()) releaseTicket(chunk)
            true
        } }.whenComplete { applied, failure ->
            work.callSync(token) {
                if (asyncMutation === result) asyncMutation = null
                if (failure == null) result.complete(applied)
                else result.completeExceptionally(failure)
            }.exceptionally { result.completeExceptionally(it); null }
        }
        return result
    }

    private fun processQueue(
        queue: ArrayDeque<WorksitePreparedSceneRecord>,
        queued: MutableSet<RecordKey>,
        limit: Int,
        restore: Boolean,
        allowed: (WorksitePreparedSceneRecord) -> Boolean,
        retryRejected: Boolean,
    ): Int {
        val selected = buildList {
            repeat(minOf(limit, queue.size)) {
                val record = queue.removeFirst()
                queued.remove(record.key())
                if (restore) restoreCounts.compute(record.zoneId) { _, count -> ((count ?: 1) - 1).takeIf { it > 0 } }
                if (restore || record.key() in buildingKeys && !restoreRequested(record)) add(record)
                else forgetBuild(record, completed = false)
            }
        }
        var processed = 0
        selected.groupBy { Triple(it.world, it.x shr 4, it.z shr 4) }.forEach { (chunkKey, pending) ->
            if (asyncMutation != null) { enqueue(pending, queue, queued); return@forEach }
            val world = Bukkit.getWorld(chunkKey.first) ?: run {
                enqueue(pending, queue, queued)
                return@forEach
            }
            if (!world.isChunkLoaded(chunkKey.second, chunkKey.third)) {
                enqueue(pending, queue, queued)
                return@forEach
            }
            val chunk = world.getChunkAt(chunkKey.second, chunkKey.third)
            val current = read(chunk) ?: run {
                enqueue(pending, queue, queued)
                return@forEach
            }
            val pendingKeys = pending.mapTo(hashSetOf()) { it.key() }
            val indexed = journalCache[chunkKey]
            val owned = if (tasks != null && indexed != null) pendingKeys.mapNotNull(indexed.byPosition::get)
                else current.filter { it.key() in pendingKeys }
            val ownedKeys = owned.mapTo(hashSetOf()) { it.key() }
            if (!restore) {
                pending.filterNot { it.key() in ownedKeys }
                    .forEach { forgetBuild(it, completed = false) }
            }
            val (accepted, rejected) = owned.partition(allowed)
            if (rejected.isNotEmpty() && retryRejected) {
                enqueue(rejected, queue, queued)
            } else if (rejected.isNotEmpty()) {
                if (!restore) rejected.forEach { forgetBuild(it, completed = false) }
                logger.severe(
                    "Preserved ${rejected.size} out-of-scope prepared scene records in ${chunk.world.name}:${chunk.x},${chunk.z}",
                )
            }
            if (restore) {
                val repaired = accepted.filter { apply(it, it.originalData) }
                val failed = accepted.filterNot { it in repaired }
                if (failed.isNotEmpty()) enqueue(failed, queue, queued)
                if (repaired.isNotEmpty()) {
                    val repairedKeys = repaired.mapTo(hashSetOf()) { it.key() }
                    if (tasks != null) {
                        processed += repaired.size
                        val token = tasks.lifecycleToken()
                        val epoch = cacheEpoch
                        val zones = repaired.mapTo(hashSetOf()) { it.zoneId }
                        retiringZones += zones
                        retireAsync(chunk, current, repairedKeys).whenComplete { _, failure ->
                            tasks.callSync(token) {
                                if (cacheEpoch == epoch) {
                                    retiringZones.removeAll(zones)
                                    if (failure != null) {
                                        enqueue(repaired, queue, queued)
                                        logger.log(Level.WARNING, "Could not retire restored prepared scene records in $chunkKey; will retry", failure)
                                    }
                                }
                            }
                        }
                        return@forEach
                    }
                    val remaining = current.filterNot { it.key() in repairedKeys }
                    runCatching {
                        write(chunk, remaining)
                        if (remaining.isEmpty()) releaseTicket(chunk)
                        processed += repaired.size
                    }.onFailure { failure ->
                        // The blocks are already restored, but the durable ledger still
                        // owns them. Keep them queued until the ledger removal succeeds.
                        enqueue(repaired, queue, queued)
                        logger.log(
                            Level.WARNING,
                            "Could not retire restored prepared scene records in ${chunk.world.name}:${chunk.x},${chunk.z}; will retry",
                            failure,
                        )
                    }
                }
            } else {
                val applied = accepted.filter { apply(it, it.activeData) }
                enqueue(accepted.filterNot { it in applied }, queue, queued)
                applied.forEach { forgetBuild(it, completed = true) }
                processed += applied.size
            }
        }
        return processed
    }

    private fun apply(record: WorksitePreparedSceneRecord, raw: String): Boolean {
        val world = Bukkit.getWorld(record.world) ?: return false
        if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return false
        val block = world.getBlockAt(record.x, record.y, record.z)
        if (block.blockData.asString == raw) return true
        val data = runCatching { blockDataDecoder.decode(raw) }.getOrElse { failure ->
            logger.log(Level.SEVERE, "Could not decode prepared scene BlockData at ${record.world}:${record.x},${record.y},${record.z}", failure)
            return false
        }
        return runCatching {
            block.setBlockData(data, false)
            true
        }.getOrElse { failure ->
            logger.log(Level.WARNING, "Could not apply prepared scene BlockData at ${record.world}:${record.x},${record.y},${record.z}; will retry", failure)
            false
        }
    }

    private fun warmWorld(world: World): CompletableFuture<Unit> {
        val work = requireNotNull(tasks)
        val id = world.uid
        if (id in warmedWorlds) return CompletableFuture.completedFuture(Unit)
        worldWarmups[id]?.let { return it }
        val epoch = cacheEpoch
        val discoveryRevision = worldDiscoveryRevisions[id] ?: 0L
        val token = work.lifecycleToken()
        val chunks = world.loadedChunks.iterator()
        val reads = mutableListOf<CompletableFuture<JournalSnapshot>>()
        val result = CompletableFuture<Unit>()
        worldWarmups[id] = result
        work.capture(token, { cacheEpoch == epoch && Bukkit.getWorld(id) === world }) {
            if (chunks.hasNext()) {
                val chunk = chunks.next()
                if (world.isChunkLoaded(chunk.x, chunk.z)) reads += readAsync(chunk)
            }
            !chunks.hasNext()
        }.thenCompose { CompletableFuture.allOf(*reads.toTypedArray()) }
            .thenCompose { work.callSync(token) {
                check(cacheEpoch == epoch && Bukkit.getWorld(id) === world &&
                    (worldDiscoveryRevisions[id] ?: 0L) == discoveryRevision) { "Scene world discovery superseded" }
                warmedWorlds += id
                Unit
            } }.whenComplete { _, failure -> work.callSync(token) {
                worldWarmups.remove(id, result)
                if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
            }.exceptionally { result.completeExceptionally(it); null } }
        return result
    }

    private data class RecoveredScene(
        val records: List<WorksitePreparedSceneRecord>,
        val positions: Set<Triple<Int, Int, Int>>,
        val chunks: Set<Pair<Int, Int>>,
        val palette: Set<String>,
        val start: WorksitePreparedSceneRecord?,
        val end: WorksitePreparedSceneRecord?,
    )

    private fun recoverScene(
        world: World, key: SceneKey, surface: Location, recoveryRadius: Int,
        start: Location?, end: Location?, startMarker: String?, endMarker: String?,
    ) {
        if (key in sceneRecoveries || restoreAdmissions.keys.any { it.matches(key) }) return
        val work = requireNotNull(tasks)
        val token = work.lifecycleToken()
        val epoch = mutationEpoch
        val result = CompletableFuture<Unit>()
        sceneRecoveries[key] = result
        fun current() = mutationEpoch == epoch && sceneRecoveries[key] === result && Bukkit.getWorld(key.world) === world
        val worldId = world.uid
        var root = journalCache
        fun select(): CompletableFuture<RecoveredScene?> {
            root = journalCache
            return work.compute {
                val records = root.entries.asSequence().filter { it.key.first == key.world && it.value.worldId == worldId }
                    .flatMap { it.value.byScene[key].orEmpty().asSequence() }.toList()
                if (records.isNotEmpty()) require(records.all { it.totalRecords == records.first().totalRecords }) { "Inconsistent recovered scene size" }
                if (records.isEmpty()) null else RecoveredScene(records,
                    records.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) },
                    records.mapTo(linkedSetOf()) { it.x shr 4 to (it.z shr 4) },
                    records.flatMapTo(linkedSetOf()) { listOf(it.originalData, it.activeData) },
                    startMarker?.let { marker -> records.singleOrNull { it.marker == marker } },
                    endMarker?.let { marker -> records.singleOrNull { it.marker == marker } })
            }
        }
        warmWorld(world).thenCompose { select() }.thenCompose { recovered ->
            if (recovered == null || recovered.records.size == recovered.records.first().totalRecords || recoveryRadius == 0) {
                CompletableFuture.completedFuture(recovered)
            } else work.callSync(token) {
                check(current())
                loadRecoveryChunksAsync(world, surface, recoveryRadius, ::current)
            }.thenCompose { it }.thenCompose { work.callSync(token) { select() }.thenCompose { it } }
        }.thenCompose { recovered -> work.callSync(token) {
            check(current() && journalCache === root) { "Scene recovery snapshot changed" }
            if (recovered == null) return@callSync CompletableFuture.completedFuture(Unit)
            val records = recovered.records
            val expected = records.first().totalRecords
            if (records.size != expected) return@callSync CompletableFuture.completedFuture(Unit)
            val resolvedStart = start ?: recovered.start?.let { Location(world, it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
                ?: return@callSync CompletableFuture.completedFuture(Unit)
            val resolvedEnd = end ?: recovered.end?.let { Location(world, it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
                ?: return@callSync CompletableFuture.completedFuture(Unit)
            val palette = recovered.palette.iterator()
            work.capture(token, { current() && journalCache === root }) {
                if (palette.hasNext()) blockDataDecoder.decode(palette.next())
                !palette.hasNext()
            }.thenCompose {
                val chunks = recovered.chunks.iterator()
                work.capture(token, { current() && journalCache === root }) {
                    if (chunks.hasNext()) {
                        val (x, z) = chunks.next()
                        check(world.isChunkLoaded(x, z)) { "Recovered scene chunk unloaded" }
                        val chunkKey = Triple(key.world, x, z)
                        if (chunkKey !in ticketedChunks) ticketedChunks[chunkKey] = chunkRetention.retain(world.getChunkAt(x, z))
                    }
                    !chunks.hasNext()
                }
            }.thenRun {
                val scene = WorksitePreparedScene(world, key.zoneId, key.sequence, key.sceneId, surface, resolvedStart, resolvedEnd, records)
                scene.completionProbe = { isComplete(scene) }
                scenes[key] = scene
                scenePositions[key] = recovered.positions
                sceneChunkPositions[key] = recovered.chunks
            }.thenApply { Unit }
        }.thenCompose { it } }.whenComplete { _, failure -> work.callSync(token) {
            sceneRecoveries.remove(key, result)
            if (failure == null) result.complete(Unit) else {
                // A newer projection/cache revision is retried by the next owner tick.
                result.completeExceptionally(failure)
            }
        }.exceptionally { result.completeExceptionally(it); null } }
    }

    private fun loadRecoveryChunksAsync(
        world: World, surface: Location, radius: Int, current: () -> Boolean,
    ): CompletableFuture<Unit> {
        val work = requireNotNull(tasks)
        val token = work.lifecycleToken()
        val minX = (surface.blockX - radius) shr 4
        val maxX = (surface.blockX + radius) shr 4
        val minZ = (surface.blockZ - radius) shr 4
        val maxZ = (surface.blockZ + radius) shr 4
        var x = minX
        var z = minZ
        val result = CompletableFuture<Unit>()
        fun next() {
            if (!current()) { result.cancel(false); return }
            if (x > maxX) { result.complete(Unit); return }
            val chunkX = x
            val chunkZ = z
            if (++z > maxZ) { z = minZ; x++ }
            work.capture(token, current) { true }.thenCompose {
                world.getChunkAtAsync(chunkX, chunkZ, true)
            }.thenCompose { chunk -> work.callSync(token) {
                check(current())
                readAsync(chunk)
            }.thenCompose { it } }.whenComplete { _, failure -> work.callSync(token) {
                if (failure != null) result.completeExceptionally(failure) else next()
            }.exceptionally { result.completeExceptionally(it); null } }
        }
        next()
        return result
    }

    private fun enqueueBuildAsync(scene: WorksitePreparedScene) {
        val work = requireNotNull(tasks)
        val key = scene.key()
        if (key in buildingSceneCounts || !buildingAdmissions.add(key)) return
        val token = work.lifecycleToken()
        val records = scene.records.iterator()
        val epoch = cacheEpoch
        work.capture(token, { cacheEpoch == epoch && scenes[key] === scene && !restoring(scene.zoneId) }) {
            if (records.hasNext()) enqueueBuild(listOf(records.next()))
            !records.hasNext()
        }.whenComplete { _, _ -> work.callSync(token) { if (cacheEpoch == epoch) buildingAdmissions.remove(key) } }
    }

    private fun RestoreKey.matches(key: SceneKey): Boolean = world == key.world && zone == key.zoneId &&
        sequence == key.sequence && (scene == null || scene == key.sceneId)

    private fun restoreRequested(record: WorksitePreparedSceneRecord): Boolean =
        restoreAdmissions.keys.any { it.matches(record.sceneKey()) }

    private fun beginRestoreAsync(world: World, zoneId: String, sequence: Long, sceneId: Int?) {
        val work = requireNotNull(tasks)
        val restoreKey = RestoreKey(world.name, zoneId, sequence, sceneId)
        val receipts = preparationReceipts.entries.iterator()
        while (receipts.hasNext()) {
            val (key, receipt) = receipts.next()
            if (restoreKey.matches(key)) { receipts.remove(); receipt.complete(false) }
        }
        val worldId = world.uid
        if (restoreKey in restoreAdmissions) return
        val lease = Any()
        restoreAdmissions[restoreKey] = lease
        mutationEpoch++
        val epoch = cacheEpoch
        val token = work.lifecycleToken()
        preparations.keys.removeIf { restoreKey.matches(it) }
        scenes.keys.removeIf { restoreKey.matches(it) }
        scenePositions.keys.removeIf { restoreKey.matches(it) }
        sceneChunkPositions.keys.removeIf { restoreKey.matches(it) }
        completedBuilds.removeIf { restoreKey.matches(it) }
        fun current() = cacheEpoch == epoch && restoreAdmissions[restoreKey] === lease && Bukkit.getWorld(restoreKey.world) === world
        // Wait until any previously admitted projection has stopped before selecting its durable journal.
        (asyncMutation?.handle { _, _ -> Unit } ?: CompletableFuture.completedFuture(Unit))
            .thenCompose { work.callSync(token) { check(current()); warmWorld(world) }.thenCompose { it } }
            .thenCompose { work.callSync(token) { journalCache }.thenCompose { root -> work.compute {
                root.entries.asSequence().filter { it.key.first == restoreKey.world && it.value.worldId == worldId }
                    .flatMap { it.value.records.asSequence() }.filter { restoreKey.matches(it.sceneKey()) }
                    .sortedByDescending { it.y }.toList()
            } } }.thenCompose { records -> work.callSync(token) {
                val pending = records.iterator()
                work.capture(token, ::current) {
                    if (pending.hasNext()) {
                        val record = pending.next()
                        queuedBuilds.remove(record.key())
                        forgetBuild(record, completed = false)
                        enqueueRestore(listOf(record))
                    }
                    !pending.hasNext()
                }
            }.thenCompose { it } }.whenComplete { _, failure -> work.callSync(token) {
                if (current()) {
                    if (failure == null) {
                        admittedRestores += restoreKey
                        completeRestores()
                    } else logger.log(Level.SEVERE, "Prepared scene restoration admission failed; journal remains protected: $restoreKey", failure)
                }
            } }
    }

    private fun completeRestores() {
        val completed = admittedRestores.filter { key ->
            (restoreCounts[key.zone] ?: 0) == 0 && key.zone !in retiringZones &&
                sceneJournalCounts.none { (scene, count) -> count > 0 && key.matches(scene.second) }
        }
        completed.forEach { admittedRestores.remove(it); restoreAdmissions.remove(it) }
    }

    private fun loadedRecords(world: World, zoneId: String, sequence: Long, sceneId: Int): List<WorksitePreparedSceneRecord> =
        world.loadedChunks.asSequence().flatMap { read(it).orEmpty().asSequence() }
            .filter { it.zoneId == zoneId && it.sequence == sequence && it.sceneId == sceneId }
            .toList()

    private fun hasLoadedSceneRecordsInternal(world: World, zoneId: String, sequence: Long, sceneId: Int): Boolean {
        if (tasks != null) {
            warmWorld(world)
            return world.uid !in warmedWorlds || (sceneJournalCounts[world.uid to SceneKey(world.name, zoneId, sequence, sceneId)] ?: 0) > 0
        }
        return world.loadedChunks.any { chunk -> read(chunk).orEmpty().any {
            it.zoneId == zoneId && it.sequence == sequence && it.sceneId == sceneId
        } }
    }

    /** Decode each distinct palette value once before a recovered scene can become ready. */
    private fun validateBlockData(records: Collection<WorksitePreparedSceneRecord>): Boolean = runCatching {
        records.asSequence()
            .flatMap { sequenceOf(it.originalData, it.activeData) }
            .distinct()
            .forEach(blockDataDecoder::decode)
    }.onFailure { failure -> logger.log(Level.WARNING, "Could not validate prepared scene BlockData", failure) }
        .isSuccess

    private fun markerLocation(world: World, records: List<WorksitePreparedSceneRecord>, marker: String?): Location? =
        marker?.let { wanted -> records.singleOrNull { it.marker == wanted }?.let { Location(world, it.x + 0.5, it.y.toDouble(), it.z + 0.5) } }

    private fun enqueueBuild(records: Collection<WorksitePreparedSceneRecord>) {
        records.forEach { record ->
            val key = record.key()
            if (buildingKeys.add(key)) {
                completedBuilds.remove(record.sceneKey())
                queuedBuilds.add(key)
                buildQueue.addLast(record)
                val sceneKey = record.sceneKey()
                buildingSceneCounts[sceneKey] = (buildingSceneCounts[sceneKey] ?: 0) + 1
            }
        }
    }

    private fun markProjectionComplete(sceneKey: SceneKey) {
        if (sceneKey in completedBuilds) return
        val chunks = projectionChunks.filterKeys { it.scene == sceneKey }.values
        val expected = chunks.firstOrNull()?.expected ?: return
        if (chunks.sumOf(ProjectionChunkState::records) == expected && chunks.all(ProjectionChunkState::active)) {
            completedBuilds += sceneKey
        }
    }

    /** A chunk-load callback must not requeue blocks that already have the baseline. */
    private fun hasActiveProjection(record: WorksitePreparedSceneRecord): Boolean {
        val world = Bukkit.getWorld(record.world) ?: return false
        if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return false
        return world.getBlockAt(record.x, record.y, record.z).blockData.asString == record.activeData
    }

    private fun enqueueRestore(records: Collection<WorksitePreparedSceneRecord>) = enqueue(records, restoreQueue, queuedRestores)

    private fun cancelBuild(records: Collection<WorksitePreparedSceneRecord>) {
        val keys = records.mapTo(hashSetOf()) { it.key() }
        if (keys.isEmpty()) return
        queuedBuilds.removeAll(keys)
        records.forEach { forgetBuild(it, completed = false) }
    }

    private fun forgetBuild(record: WorksitePreparedSceneRecord, completed: Boolean) {
        if (!buildingKeys.remove(record.key())) return
        val sceneKey = record.sceneKey()
        val remaining = (buildingSceneCounts[sceneKey] ?: 1) - 1
        if (remaining <= 0) {
            buildingSceneCounts.remove(sceneKey)
            if (completed) completedBuilds += sceneKey
        } else {
            buildingSceneCounts[sceneKey] = remaining
        }
    }

    private fun ticket(scene: WorksitePreparedScene) = ticket(scene.world, scene.records)

    private fun ticket(world: World, records: Collection<WorksitePreparedSceneRecord>) {
        records.map { Triple(it.world, it.x shr 4, it.z shr 4) }.distinct().forEach { key ->
            if (key !in ticketedChunks && world.isChunkLoaded(key.second, key.third)) {
                ticketedChunks[key] = chunkRetention.retain(world.getChunkAt(key.second, key.third))
            }
        }
    }

    private fun loadRecoveryChunks(world: World, surface: Location, radius: Int) {
        val minChunkX = (floor(surface.x).toInt() - radius) shr 4
        val maxChunkX = (floor(surface.x).toInt() + radius) shr 4
        val minChunkZ = (floor(surface.z).toInt() - radius) shr 4
        val maxChunkZ = (floor(surface.z).toInt() + radius) shr 4
        for (chunkX in minChunkX..maxChunkX) for (chunkZ in minChunkZ..maxChunkZ) {
            runCatching { world.getChunkAt(chunkX, chunkZ) }.onFailure { failure ->
                logger.log(Level.WARNING, "Could not load prepared scene recovery chunk ${world.name}:$chunkX,$chunkZ", failure)
            }
        }
    }

    private fun releaseTicket(chunk: Chunk) = releaseTicket(Triple(chunk.world.name, chunk.x, chunk.z))

    private fun releaseTicket(key: Triple<String, Int, Int>) {
        ticketedChunks[key]?.let { lease ->
            runCatching(lease::close).onSuccess { ticketedChunks.remove(key, lease) }
                .onFailure { failure -> logger.log(Level.WARNING, "Could not release prepared scene chunk lease $key; will retry", failure) }
        }
    }

    private fun enqueue(records: Collection<WorksitePreparedSceneRecord>, queue: ArrayDeque<WorksitePreparedSceneRecord>, queued: MutableSet<RecordKey>) {
        records.forEach { record -> if (queued.add(record.key())) {
            queue.addLast(record)
            if (queue === restoreQueue) restoreCounts[record.zoneId] = (restoreCounts[record.zoneId] ?: 0) + 1
        } }
    }

    private fun readAsync(chunk: Chunk): CompletableFuture<JournalSnapshot> {
        val work = requireNotNull(tasks)
        val key = Triple(chunk.world.name, chunk.x, chunk.z)
        journalCache[key]?.let {
            if (it.worldId == chunk.world.uid) return CompletableFuture.completedFuture(it)
            invalidateCache(key)
        }
        loadingJournals[key]?.let { return it }
        val raw = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY)
        if (raw == null) {
            cache(chunk, snapshot(emptyList()))
            return CompletableFuture.completedFuture(journalCache.getValue(key))
        }
        val minimum = chunk.world.minHeight
        val maximum = chunk.world.maxHeight
        val world = chunk.world
        val epoch = cacheEpoch
        val revision = chunkRevisions[key] ?: 0L
        val token = work.lifecycleToken()
        val result = CompletableFuture<JournalSnapshot>()
        loadingJournals[key] = result
        work.compute { snapshot(codec.decode(raw, key.first, key.second, key.third, minimum, maximum)) }
            .thenCompose { decoded -> work.callSync(token) {
                check(cacheEpoch == epoch && Bukkit.getWorld(key.first) === world && world.isChunkLoaded(key.second, key.third) &&
                    ((chunkRevisions[key] ?: 0L) == revision || journalCache[key] != null)) {
                    "Prepared scene journal capture superseded"
                }
                // An admitted owner write always publishes its newer immutable snapshot.
                journalCache[key] ?: run { cache(chunk, decoded); journalCache.getValue(key) }
            } }.whenComplete { decoded, failure ->
                work.callSync(token) {
                    loadingJournals.remove(key, result)
                    if (failure == null) result.complete(decoded)
                    else {
                        logger.log(Level.SEVERE, "Could not decode prepared scene journal in $key", failure)
                        result.completeExceptionally(failure)
                    }
                }.exceptionally { result.completeExceptionally(it); null }
            }
        return result
    }

    private fun read(chunk: Chunk): List<WorksitePreparedSceneRecord>? {
        if (tasks != null) {
            readAsync(chunk)
            return journalCache[Triple(chunk.world.name, chunk.x, chunk.z)]?.records
        }
        val raw = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY) ?: return emptyList()
        return runCatching { codec.decode(raw, chunk.world.name, chunk.x, chunk.z, chunk.world.minHeight, chunk.world.maxHeight) }
            .getOrElse { failure ->
                logger.log(Level.SEVERE, "Could not decode prepared scene journal in ${chunk.world.name}:${chunk.x},${chunk.z}", failure)
                null
            }
    }

    private fun write(chunk: Chunk, records: List<WorksitePreparedSceneRecord>) {
        invalidateCache(Triple(chunk.world.name, chunk.x, chunk.z))
        if (records.isEmpty()) chunk.persistentDataContainer.remove(journalKey)
        else chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, encode(chunk, records))
    }

    private fun encode(chunk: Chunk, records: List<WorksitePreparedSceneRecord>): ByteArray =
        codec.encode(records, chunk.world.name, chunk.x, chunk.z, chunk.world.minHeight, chunk.world.maxHeight)

    private fun WorksitePreparedScene.key() = SceneKey(world.name, zoneId, sequence, sceneId)
    private fun WorksitePreparedSceneRecord.sceneKey() = SceneKey(world, zoneId, sequence, sceneId)
    private fun WorksitePreparedSceneRecord.key() = RecordKey(world, x, y, z)
}

internal fun logLoadedSceneRecoveryFailure(logger: Logger, failure: Throwable?) {
    var cause = failure
    while (cause is CompletionException && cause.cause != null) cause = cause.cause
    if (cause != null && cause !is CancellationException) {
        logger.log(Level.WARNING, "Could not recover loaded scene chunk", failure)
    }
}

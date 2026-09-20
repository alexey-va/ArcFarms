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
    /** Live readiness is deliberately derived from the journal projection. */
    val ready: Boolean
        get() = records.isNotEmpty() && records.all { record ->
            world.isChunkLoaded(record.x shr 4, record.z shr 4) &&
                world.getBlockAt(record.x, record.y, record.z).blockData.asString == record.activeData
        }
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

    fun prepare(scenePlans: Collection<WorksitePreparedScene>): Boolean {
        require(scenePlans.isNotEmpty()) { "Prepared scene commit is empty" }
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

    /** Large scenes journal bounded slices before building them; partial journals recover through the same owner. */
    fun prepareIncrementally(scene: WorksitePreparedScene, recordsPerSlice: Int = 1_024): Boolean {
        require(recordsPerSlice in 1..4_096)
        require(scene.records.isNotEmpty())
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
            ticket(existing)
            val needsBuild = shouldBuild(existing)
            if (needsBuild) enqueueBuild(existing.records) else completedBuilds += existing.key()
            return (if (!needsBuild) WorksitePreparedSceneEnsureResult.READY
            else WorksitePreparedSceneEnsureResult.BUILDING) to existing
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
        key in completedBuilds && key !in preparations && key !in buildingSceneCounts
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

    /** True while a durable record still owns this position, including an in-flight queue. */
    fun protects(location: Location): Boolean {
        val key = RecordKey(location.world.name, location.blockX, location.blockY, location.blockZ)
        if (key in queuedBuilds || key in queuedRestores || scenePositions.any { (scene, positions) ->
            scene.world == location.world.name && Triple(location.blockX, location.blockY, location.blockZ) in positions
        }) return true
        if (!location.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return false
        return read(location.world.getChunkAt(location.blockX shr 4, location.blockZ shr 4))
            .orEmpty()
            .any { it.key() == key }
    }

    fun restoring(zoneId: String): Boolean = restoreQueue.any { it.zoneId == zoneId }

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
        processPreparation()
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

    fun onChunkLoad(
        chunk: Chunk,
        active: (String, Long) -> Boolean,
        isActiveScene: (WorksitePreparedSceneRecord) -> Boolean = { true },
    ) {
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
        Bukkit.getWorlds().forEach { world ->
            world.loadedChunks.forEach { onChunkLoad(it, active, isActiveScene) }
        }
    }

    fun clearQueues() {
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
        val (key, pending) = preparations.entries.firstOrNull() ?: return
        val scene = scenes[key] ?: run { preparations.remove(key); return }
        val records = pending.removeFirst()
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
            prepared.forEach { (chunk, payloads) -> chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, payloads.second) }
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
                add(record)
            }
        }
        var processed = 0
        selected.groupBy { Triple(it.world, it.x shr 4, it.z shr 4) }.forEach { (chunkKey, pending) ->
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
            val owned = current.filter { it.key() in pendingKeys }
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

    private fun loadedRecords(world: World, zoneId: String, sequence: Long, sceneId: Int): List<WorksitePreparedSceneRecord> =
        world.loadedChunks.asSequence().flatMap { read(it).orEmpty().asSequence() }
            .filter { it.zoneId == zoneId && it.sequence == sequence && it.sceneId == sceneId }
            .toList()

    private fun hasLoadedSceneRecordsInternal(world: World, zoneId: String, sequence: Long, sceneId: Int): Boolean =
        world.loadedChunks.any { chunk -> read(chunk).orEmpty().any { it.zoneId == zoneId && it.sequence == sequence && it.sceneId == sceneId } }

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
        buildQueue.removeIf { it.key() in keys }
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
        records.forEach { record -> if (queued.add(record.key())) queue.addLast(record) }
    }

    private fun read(chunk: Chunk): List<WorksitePreparedSceneRecord>? {
        val raw = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY) ?: return emptyList()
        return runCatching { codec.decode(raw, chunk.world.name, chunk.x, chunk.z, chunk.world.minHeight, chunk.world.maxHeight) }
            .getOrElse { failure ->
                logger.log(Level.SEVERE, "Could not decode prepared scene journal in ${chunk.world.name}:${chunk.x},${chunk.z}", failure)
                null
            }
    }

    private fun write(chunk: Chunk, records: List<WorksitePreparedSceneRecord>) {
        if (records.isEmpty()) chunk.persistentDataContainer.remove(journalKey)
        else chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, encode(chunk, records))
    }

    private fun encode(chunk: Chunk, records: List<WorksitePreparedSceneRecord>): ByteArray =
        codec.encode(records, chunk.world.name, chunk.x, chunk.z, chunk.world.minHeight, chunk.world.maxHeight)

    private fun WorksitePreparedScene.key() = SceneKey(world.name, zoneId, sequence, sceneId)
    private fun WorksitePreparedSceneRecord.sceneKey() = SceneKey(world, zoneId, sequence, sceneId)
    private fun WorksitePreparedSceneRecord.key() = RecordKey(world, x, y, z)
}

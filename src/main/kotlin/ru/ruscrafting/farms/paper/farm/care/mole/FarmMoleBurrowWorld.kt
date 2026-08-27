package ru.ruscrafting.farms.paper.farm.care.mole

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Levelled
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.FarmMoleBurrowPlanner
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmRuntime
import java.util.ArrayDeque
import java.util.logging.Level
import kotlin.math.floor

internal data class FarmMoleBurrowScene(
    val world: World,
    val zoneId: String,
    val sequence: Long,
    val surface: Location,
    val start: Location,
    val lair: Location,
    val records: List<FarmMoleBurrowJournalRecord>,
) {
    private val tunnelPositions = records.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }

    val ready: Boolean get() = records.all { record ->
        if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return@all false
        val block = world.getBlockAt(record.x, record.y, record.z)
        block.blockData.asString == record.burrowData
    }

    fun contains(location: Location): Boolean {
        if (location.world !== world || records.isEmpty()) return false
        return Triple(location.blockX, location.blockY, location.blockZ) in tunnelPositions
    }
}

internal enum class FarmMoleBurrowEnsureResult {
    BUILDING,
    READY,
    UNAVAILABLE,
}

internal data class FarmMoleBurrowPreview(
    val scene: FarmMoleBurrowScene?,
    val layoutAttempts: Int,
    val rejections: Map<String, Int>,
) {
    fun rejectionSummary(): String = rejections.entries
        .sortedByDescending(Map.Entry<String, Int>::value)
        .joinToString(",") { (reason, count) -> "$reason:$count" }
        .ifEmpty { "none" }
}

/** Owns crash-safe tunnel mutation and bounded build/restore queues. */
internal class FarmMoleBurrowWorld(
    private val plugin: Plugin,
    private val debug: ArcFarmsDebug,
) {
    private data class SceneKey(val world: String, val zoneId: String, val sequence: Long)
    private data class RecordKey(val world: String, val x: Int, val y: Int, val z: Int)

    private val journalKey = NamespacedKey(plugin, "farm_mole_burrow_v1")
    private val logger = plugin.logger
    private val buildQueue = ArrayDeque<FarmMoleBurrowJournalRecord>()
    private val restoreQueue = ArrayDeque<FarmMoleBurrowJournalRecord>()
    private val queuedBuilds = linkedSetOf<RecordKey>()
    private val queuedRestores = linkedSetOf<RecordKey>()
    private val ticketedChunks = linkedSetOf<Triple<String, Int, Int>>()
    private val scenes = mutableMapOf<SceneKey, FarmMoleBurrowScene>()

    fun preview(runtime: FarmRuntime, surface: FarmPointPosition): FarmMoleBurrowScene? =
        previewDetailed(runtime, surface).scene

    fun previewDetailed(runtime: FarmRuntime, surface: FarmPointPosition): FarmMoleBurrowPreview {
        val world = runtime.region.world
        if (surface.world != world.name) return FarmMoleBurrowPreview(null, 0, mapOf("wrong_world" to 1))
        val settings = runtime.settings.moleBurrow
        val rejections = linkedMapOf<String, Int>()
        var layoutAttempts = 0
        fun reject(reason: String, count: Int = 1) {
            rejections[reason] = rejections.getOrDefault(reason, 0) + count
        }
        val layoutSeed = seed(runtime.state.sequence, floor(surface.x).toInt(), floor(surface.z).toInt())
        val raw = FarmMoleBurrowPlanner.plan(
            settings.cells,
            layoutSeed,
            settings.lightSpacing,
        )
        val firstRotation = Math.floorMod((runtime.state.sequence xor surface.x.toLong() xor surface.z.toLong()).toInt(), 4)
        val depthSpan = settings.maxDepth - settings.minDepth + 1
        val firstDepth = Math.floorMod(layoutSeed.toInt(), depthSpan)
        val surfaceLocation = Location(world, surface.x, surface.y, surface.z)
        layoutProbe@ for (attempt in 0 until minOf(MAX_LAYOUT_PROBES, depthSpan * 4)) {
            layoutAttempts++
            val layout = FarmMoleBurrowPlanner.rotate(raw, firstRotation + attempt)
            val depth = settings.minDepth + Math.floorMod(firstDepth + attempt * DEPTH_PROBE_STEP, depthSpan)
            val startX = floor(surface.x).toInt()
            val startZ = floor(surface.z).toInt()
            val originX = startX - layout.start.x
            val originZ = startZ - layout.start.z
            val feetY = floor(surface.y).toInt() - depth
            val planned = linkedMapOf<Triple<Int, Int, Int>, Pair<String, FarmMoleBurrowMarker>>()
            layout.passages.forEach { passage ->
                repeat(settings.tunnelHeight) { dy ->
                    planned[Triple(originX + passage.x, feetY + dy, originZ + passage.z)] =
                        AIR_DATA to FarmMoleBurrowMarker.NONE
                }
            }
            val lairX = originX + layout.lair.x
            val lairZ = originZ + layout.lair.z
            for (dx in -1..1) for (dz in -1..1) repeat(settings.tunnelHeight) { dy ->
                planned[Triple(lairX + dx, feetY + dy, lairZ + dz)] = AIR_DATA to FarmMoleBurrowMarker.NONE
            }
            val startPosition = Triple(startX, feetY, startZ)
            val lairPosition = Triple(lairX, feetY, lairZ)
            planned[startPosition] = AIR_DATA to FarmMoleBurrowMarker.START
            planned[lairPosition] = AIR_DATA to FarmMoleBurrowMarker.LAIR
            // The entrance is a real, journalled shaft. Its top two blocks may be
            // the crop and farmland of a managed bed; both are restored byte-for-
            // byte with the rest of the burrow after the expedition.
            val surfaceBlockY = floor(surface.y).toInt()
            val shaftPositions = (feetY..surfaceBlockY).mapTo(linkedSetOf()) { y ->
                Triple(startX, y, startZ).also { position ->
                    val marker = planned[position]?.second ?: FarmMoleBurrowMarker.NONE
                    // Keep an invisible safety floor below the click target. Players
                    // enter only after their crash-safe return point is committed.
                    planned[position] = (if (y == surfaceBlockY - 1) BARRIER_DATA else AIR_DATA) to marker
                }
            }
            val lightData = lightData(settings.lightLevel)
            layout.lights.forEach { passage ->
                val position = Triple(originX + passage.x, feetY + settings.tunnelHeight - 1, originZ + passage.z)
                val marker = planned[position]?.second ?: FarmMoleBurrowMarker.NONE
                planned[position] = lightData to marker
            }
            if (planned.size > FarmMoleBurrowJournalCodec.MAX_SCENE_RECORDS) {
                reject("scene_too_large")
                continue@layoutProbe
            }
            val chunks = planned.keys.map { (x, _, z) -> (x shr 4) to (z shr 4) }.distinct()
            if (chunks.any { (x, z) -> !world.isChunkLoaded(x, z) }) {
                reject("unloaded_chunk")
                continue@layoutProbe
            }
            val journals = chunks.map { (x, z) -> read(world.getChunkAt(x, z)) }
            if (journals.any { it == null }) {
                reject("journal_unreadable")
                continue@layoutProbe
            }
            if (journals.any { it.orEmpty().isNotEmpty() }) {
                reject("journal_occupied")
                continue@layoutProbe
            }
            val blocks = planned.keys.map { (x, y, z) -> world.getBlockAt(x, y, z) }
            val failures = viabilityFailures(
                runtime,
                blocks,
                shaftPositions,
                surfaceBlockY,
                settings.replaceableMaterials,
            )
            if (failures.isNotEmpty()) {
                failures.forEach { (reason, count) -> reject(reason, count) }
                continue@layoutProbe
            }
            val total = planned.size
            val records = planned.map { (position, active) ->
                val block = world.getBlockAt(position.first, position.second, position.third)
                FarmMoleBurrowJournalRecord(
                    world = world.name,
                    zoneId = runtime.settings.id,
                    sequence = runtime.state.sequence,
                    x = block.x,
                    y = block.y,
                    z = block.z,
                    originalData = block.blockData.asString,
                    burrowData = active.first,
                    marker = active.second,
                    totalRecords = total,
                )
            }
            return FarmMoleBurrowPreview(FarmMoleBurrowScene(
                world = world,
                zoneId = runtime.settings.id,
                sequence = runtime.state.sequence,
                surface = surfaceLocation,
                start = Location(world, startX + 0.5, feetY.toDouble(), startZ + 0.5),
                lair = Location(world, lairX + 0.5, feetY.toDouble(), lairZ + 0.5),
                records = records,
            ), layoutAttempts, rejections)
        }
        return FarmMoleBurrowPreview(null, layoutAttempts, rejections)
    }

    fun ensure(runtime: FarmRuntime, surface: FarmPointPosition): Pair<FarmMoleBurrowEnsureResult, FarmMoleBurrowScene?> {
        val stored = scene(
            runtime.region.world,
            runtime.settings.id,
            runtime.state.sequence,
            surface,
            runtime.settings.moleBurrow.cells * 2 + 1,
        )
        if (stored != null) {
            ticket(stored)
            enqueueBuild(stored.records)
            return (if (stored.ready) FarmMoleBurrowEnsureResult.READY else FarmMoleBurrowEnsureResult.BUILDING) to stored
        }
        if (hasLoadedSceneRecords(runtime.region.world, runtime.settings.id, runtime.state.sequence)) {
            beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)
            return FarmMoleBurrowEnsureResult.BUILDING to null
        }
        val preview = previewDetailed(runtime, surface)
        val plan = preview.scene ?: run {
            logger.warning(
                "Could not build mole burrow: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "surface=${surface.x},${surface.y},${surface.z} probes=${preview.layoutAttempts} " +
                    "rejections=${preview.rejectionSummary()}",
            )
            debug.event(
                "farm_mole_burrow_unavailable", "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence, "probes" to preview.layoutAttempts,
                "rejections" to preview.rejectionSummary(),
            )
            return FarmMoleBurrowEnsureResult.UNAVAILABLE to null
        }
        if (!commit(plan)) return FarmMoleBurrowEnsureResult.UNAVAILABLE to null
        scenes[SceneKey(plan.world.name, plan.zoneId, plan.sequence)] = plan
        ticket(plan)
        enqueueBuild(plan.records)
        debug.event(
            "farm_mole_burrow_committed",
            "zone" to plan.zoneId,
            "sequence" to plan.sequence,
            "blocks" to plan.records.size,
            "chunks" to plan.records.map { (it.x shr 4) to (it.z shr 4) }.distinct().size,
        )
        return FarmMoleBurrowEnsureResult.BUILDING to plan
    }

    fun scene(runtime: FarmRuntime): FarmMoleBurrowScene? {
        val surface = runtime.state.careTargets.firstOrNull()?.position ?: return null
        return scene(
            runtime.region.world,
            runtime.settings.id,
            runtime.state.sequence,
            surface,
            runtime.settings.moleBurrow.cells * 2 + 1,
        )
    }

    fun beginRestore(world: World, zoneId: String, sequence: Long) {
        scenes.remove(SceneKey(world.name, zoneId, sequence))
        val records = world.loadedChunks.asSequence().flatMap { read(it).orEmpty().asSequence() }
            .filter { it.zoneId == zoneId && it.sequence == sequence }
            .toList()
        cancelBuild(records)
        // Restore the visible entrance first. This prevents ordinary field
        // maintenance from briefly rebuilding a generic bed before the exact
        // journalled crop age and farmland moisture are applied.
        enqueueRestore(records.sortedByDescending(FarmMoleBurrowJournalRecord::y))
    }

    fun process(limit: Int, allowed: (FarmMoleBurrowJournalRecord) -> Boolean): Int {
        require(limit >= 1) { "Mole burrow block budget must be positive" }
        val restored = processQueue(restoreQueue, queuedRestores, limit, restore = true, allowed = allowed)
        val remaining = limit - restored
        val built = if (remaining > 0) {
            processQueue(buildQueue, queuedBuilds, remaining, restore = false, allowed = allowed)
        } else 0
        return restored + built
    }

    fun onChunkLoad(chunk: Chunk, active: (String, Long) -> Boolean) {
        val records = read(chunk) ?: return
        enqueueBuild(records.filter { active(it.zoneId, it.sequence) })
        enqueueRestore(
            records.filterNot { active(it.zoneId, it.sequence) }
                .sortedByDescending(FarmMoleBurrowJournalRecord::y),
        )
    }

    fun reconcileLoaded(active: (String, Long) -> Boolean) {
        Bukkit.getWorlds().forEach { world -> world.loadedChunks.forEach { onChunkLoad(it, active) } }
    }

    fun clearQueues() {
        buildQueue.clear()
        restoreQueue.clear()
        queuedBuilds.clear()
        queuedRestores.clear()
        scenes.clear()
        ticketedChunks.toList().forEach { (worldName, x, z) ->
            Bukkit.getWorld(worldName)?.takeIf { it.isChunkLoaded(x, z) }?.getChunkAt(x, z)?.removePluginChunkTicket(plugin)
        }
        ticketedChunks.clear()
    }

    private fun viabilityFailures(
        runtime: FarmRuntime,
        blocks: List<Block>,
        shaftPositions: Set<Triple<Int, Int, Int>>,
        surfaceBlockY: Int,
        replaceable: Set<String>,
    ): Map<String, Int> {
        if (blocks.isEmpty()) return mapOf("empty_plan" to 1)
        val failures = linkedMapOf<String, Int>()
        fun reject(reason: String) {
            failures[reason] = failures.getOrDefault(reason, 0) + 1
        }
        val footprintY = surfaceBlockY.coerceIn(runtime.region.bounds.minY, runtime.region.bounds.maxY)
        blocks.forEach { block ->
            val footprintProbe = Location(block.world, block.x + 0.5, footprintY.toDouble(), block.z + 0.5)
            if (!runtime.region.contains(footprintProbe)) reject("outside_farm_footprint")
            if (!replaceableForBurrow(runtime, block, shaftPositions, surfaceBlockY, replaceable)) {
                reject("material:${block.type.name}")
            }
            if (block.y <= block.world.minHeight + 1 || block.y >= block.world.maxHeight - 1) reject("world_height")
        }
        return failures
    }

    private fun replaceableForBurrow(
        runtime: FarmRuntime,
        block: Block,
        shaftPositions: Set<Triple<Int, Int, Int>>,
        surfaceBlockY: Int,
        replaceable: Set<String>,
    ): Boolean {
        if (block.type.isAir || block.type.name in replaceable) return true
        val position = Triple(block.x, block.y, block.z)
        if (position !in shaftPositions || block.y !in surfaceBlockY - 1..surfaceBlockY) return false
        if (block.y == surfaceBlockY) {
            return block.type.isAir || FarmBlockPolicy.isOpenBedContent(block.type, runtime.settings.crops)
        }
        val above = block.getRelative(org.bukkit.block.BlockFace.UP)
        return FarmBlockPolicy.isSelectableBed(block.type, above.type, runtime.settings.crops)
    }

    private fun commit(scene: FarmMoleBurrowScene): Boolean {
        val groups = scene.records.groupBy { (it.x shr 4) to (it.z shr 4) }
        val prepared = linkedMapOf<Chunk, Pair<ByteArray?, ByteArray>>()
        return runCatching {
            groups.forEach { (chunkPosition, additions) ->
                val chunk = scene.world.getChunkAt(chunkPosition.first, chunkPosition.second)
                require(scene.world.isChunkLoaded(chunk.x, chunk.z)) { "Mole burrow chunk unloaded during commit" }
                val current = read(chunk) ?: error("Mole burrow journal is unreadable")
                val positions = additions.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
                require(current.none { Triple(it.x, it.y, it.z) in positions }) { "Mole burrow journal overlaps another scene" }
                additions.forEach { record ->
                    require(scene.world.getBlockAt(record.x, record.y, record.z).blockData.asString == record.originalData) {
                        "Mole burrow placement changed before commit"
                    }
                }
                val encoded = encode(chunk, current + additions)
                prepared[chunk] = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY) to encoded
            }
            prepared.forEach { (chunk, payloads) ->
                chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, payloads.second)
            }
            true
        }.getOrElse { failure ->
            prepared.forEach { (chunk, payloads) ->
                val previous = payloads.first
                if (previous == null) chunk.persistentDataContainer.remove(journalKey)
                else chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, previous)
            }
            logger.log(Level.SEVERE, "Could not durably commit mole burrow ${scene.zoneId}/${scene.sequence}", failure)
            false
        }
    }

    private fun processQueue(
        queue: ArrayDeque<FarmMoleBurrowJournalRecord>,
        queued: MutableSet<RecordKey>,
        limit: Int,
        restore: Boolean,
        allowed: (FarmMoleBurrowJournalRecord) -> Boolean,
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
            val world = Bukkit.getWorld(chunkKey.first) ?: return@forEach
            if (!world.isChunkLoaded(chunkKey.second, chunkKey.third)) return@forEach
            val chunk = world.getChunkAt(chunkKey.second, chunkKey.third)
            val current = read(chunk) ?: return@forEach
            val pendingKeys = pending.mapTo(hashSetOf()) { it.key() }
            val owned = current.filter { it.key() in pendingKeys }
            val (accepted, rejected) = owned.partition(allowed)
            if (rejected.isNotEmpty()) {
                logger.severe(
                    "Preserved ${rejected.size} out-of-zone mole burrow records without applying them in " +
                        "${chunk.world.name}:${chunk.x},${chunk.z}",
                )
            }
            if (restore) {
                val repaired = accepted.filter { record -> apply(record, record.originalData) }
                if (repaired.isNotEmpty()) {
                    val repairedKeys = repaired.mapTo(hashSetOf()) { it.key() }
                    val remaining = current.filterNot { it.key() in repairedKeys }
                    write(chunk, remaining)
                    if (remaining.isEmpty()) releaseTicket(chunk)
                    processed += repaired.size
                }
            } else {
                processed += accepted.count { record -> apply(record, record.burrowData) }
            }
        }
        return processed
    }

    private fun apply(record: FarmMoleBurrowJournalRecord, raw: String): Boolean {
        val world = Bukkit.getWorld(record.world) ?: return false
        if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return false
        val data = runCatching { Bukkit.createBlockData(raw) }.getOrElse { failure ->
            logger.log(Level.SEVERE, "Could not decode mole burrow BlockData at ${record.world}:${record.x},${record.y},${record.z}", failure)
            return false
        }
        world.getBlockAt(record.x, record.y, record.z).setBlockData(data, false)
        return true
    }

    private fun scene(
        world: World,
        zoneId: String,
        sequence: Long,
        surface: FarmPointPosition,
        recoveryRadius: Int,
    ): FarmMoleBurrowScene? {
        val key = SceneKey(world.name, zoneId, sequence)
        scenes[key]?.let { return it }
        var records = world.loadedChunks.asSequence().flatMap { read(it).orEmpty().asSequence() }
            .filter { it.zoneId == zoneId && it.sequence == sequence }
            .toList()
        if (records.isEmpty()) return null
        val expected = records.first().totalRecords
        if (records.size != expected) {
            loadRecoveryChunks(world, surface, recoveryRadius)
            records = world.loadedChunks.asSequence().flatMap { read(it).orEmpty().asSequence() }
                .filter { it.zoneId == zoneId && it.sequence == sequence }
                .toList()
        }
        ticket(world, records)
        if (records.size != expected || records.any { it.totalRecords != expected }) return null
        val start = records.singleOrNull { it.marker == FarmMoleBurrowMarker.START } ?: return null
        val lair = records.singleOrNull { it.marker == FarmMoleBurrowMarker.LAIR } ?: return null
        return FarmMoleBurrowScene(
            world,
            zoneId,
            sequence,
            Location(world, surface.x, surface.y, surface.z),
            Location(world, start.x + 0.5, start.y.toDouble(), start.z + 0.5),
            Location(world, lair.x + 0.5, lair.y.toDouble(), lair.z + 0.5),
            records,
        ).also { scenes[key] = it }
    }

    private fun hasLoadedSceneRecords(world: World, zoneId: String, sequence: Long): Boolean =
        world.loadedChunks.any { chunk -> read(chunk).orEmpty().any { it.zoneId == zoneId && it.sequence == sequence } }

    private fun enqueueBuild(records: Collection<FarmMoleBurrowJournalRecord>) = enqueue(records, buildQueue, queuedBuilds)
    private fun enqueueRestore(records: Collection<FarmMoleBurrowJournalRecord>) = enqueue(records, restoreQueue, queuedRestores)

    private fun cancelBuild(records: Collection<FarmMoleBurrowJournalRecord>) {
        val keys = records.mapTo(hashSetOf()) { it.key() }
        if (keys.isEmpty()) return
        buildQueue.removeIf { it.key() in keys }
        queuedBuilds.removeAll(keys)
    }

    private fun ticket(scene: FarmMoleBurrowScene) {
        ticket(scene.world, scene.records)
    }

    private fun ticket(world: World, records: Collection<FarmMoleBurrowJournalRecord>) {
        records.map { Triple(it.world, it.x shr 4, it.z shr 4) }.distinct().forEach { key ->
            if (key !in ticketedChunks && world.isChunkLoaded(key.second, key.third) &&
                world.getChunkAt(key.second, key.third).addPluginChunkTicket(plugin)
            ) ticketedChunks += key
        }
    }

    private fun loadRecoveryChunks(world: World, surface: FarmPointPosition, radius: Int) {
        val minChunkX = (floor(surface.x).toInt() - radius) shr 4
        val maxChunkX = (floor(surface.x).toInt() + radius) shr 4
        val minChunkZ = (floor(surface.z).toInt() - radius) shr 4
        val maxChunkZ = (floor(surface.z).toInt() + radius) shr 4
        for (chunkX in minChunkX..maxChunkX) for (chunkZ in minChunkZ..maxChunkZ) {
            runCatching { world.getChunkAt(chunkX, chunkZ) }.onFailure { failure ->
                logger.log(Level.WARNING, "Could not load mole burrow recovery chunk ${world.name}:$chunkX,$chunkZ", failure)
            }
        }
    }

    private fun releaseTicket(chunk: Chunk) {
        val key = Triple(chunk.world.name, chunk.x, chunk.z)
        if (ticketedChunks.remove(key)) chunk.removePluginChunkTicket(plugin)
    }

    private fun enqueue(
        records: Collection<FarmMoleBurrowJournalRecord>,
        queue: ArrayDeque<FarmMoleBurrowJournalRecord>,
        queued: MutableSet<RecordKey>,
    ) {
        records.forEach { record -> if (queued.add(record.key())) queue.addLast(record) }
    }

    private fun read(chunk: Chunk): List<FarmMoleBurrowJournalRecord>? {
        val raw = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY) ?: return emptyList()
        return runCatching {
            FarmMoleBurrowJournalCodec.decode(raw, chunk.world.name, chunk.x, chunk.z, chunk.world.minHeight, chunk.world.maxHeight)
        }.getOrElse { failure ->
            logger.log(Level.SEVERE, "Could not decode mole burrow journal in ${chunk.world.name}:${chunk.x},${chunk.z}", failure)
            null
        }
    }

    private fun write(chunk: Chunk, records: List<FarmMoleBurrowJournalRecord>) {
        if (records.isEmpty()) chunk.persistentDataContainer.remove(journalKey)
        else chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, encode(chunk, records))
    }

    private fun encode(chunk: Chunk, records: List<FarmMoleBurrowJournalRecord>): ByteArray =
        FarmMoleBurrowJournalCodec.encode(records, chunk.world.name, chunk.x, chunk.z, chunk.world.minHeight, chunk.world.maxHeight)

    private fun FarmMoleBurrowJournalRecord.key() = RecordKey(world, x, y, z)

    private fun lightData(level: Int): String = Material.LIGHT.createBlockData().also { data ->
        (data as Levelled).level = level
    }.asString

    private fun seed(sequence: Long, x: Int, z: Int): Long = sequence * 0x9E3779B97F4A7C15UL.toLong() xor
        x.toLong() * 0xBF58476D1CE4E5B9UL.toLong() xor z.toLong() * 0x94D049BB133111EBUL.toLong()

    private companion object {
        const val MAX_LAYOUT_PROBES = 4
        const val DEPTH_PROBE_STEP = 5
        val AIR_DATA: String = Material.AIR.createBlockData().asString
        val BARRIER_DATA: String = Material.BARRIER.createBlockData().asString
    }
}

package ru.ruscrafting.farms.paper.mine.incident.rescue

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.plugin.Plugin
import org.bukkit.persistence.PersistentDataType
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.paper.platform.FarmBlockDataDecoder
import ru.ruscrafting.farms.paper.platform.PaperFarmBlockDataDecoder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

internal enum class MineLostMinerMazeEnsureResult {
    BUILDING,
    READY,
    UNAVAILABLE,
}

internal fun interface MineLostMinerMazeChunkLease : AutoCloseable {
    override fun close()
}

internal fun interface MineLostMinerMazeChunkRetention {
    fun retain(chunk: Chunk): MineLostMinerMazeChunkLease
}

internal class PaperMineLostMinerMazeChunkRetention(private val tickets: MineChunkTicket) : MineLostMinerMazeChunkRetention {
    override fun retain(chunk: Chunk): MineLostMinerMazeChunkLease {
        val retained = tickets.retain(chunk)
        return object : MineLostMinerMazeChunkLease {
            private var closed = false

            @Synchronized
            override fun close() {
                if (closed) return
                if (retained) tickets.release(chunk)
                closed = true
            }
        }
    }
}

internal data class MineLostMinerMazeScene(
    val world: World,
    val zoneId: String,
    val sequence: Long,
    /** The indexed miner anchor inside the mine; it is the only public entry marker. */
    val surface: Location,
    val start: Location,
    val target: Location,
    val records: List<MineLostMinerMazeJournalRecord>,
) {
    private val walkY = start.blockY
    private val walkable = records.asSequence()
        .filter { it.y == walkY && it.mazeData == AIR_DATA }
        .mapTo(hashSetOf()) { it.x to it.z }
    private val air = records.asSequence()
        .filter { it.mazeData == AIR_DATA || it.mazeData.startsWith("minecraft:lantern[") }
        .mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
    private val pathDistances = distances(target.blockX to target.blockZ)

    val ready: Boolean
        get() = records.all { record ->
            world.isChunkLoaded(record.x shr 4, record.z shr 4) &&
                world.getBlockAt(record.x, record.y, record.z).blockData.asString == record.mazeData
        }

    fun contains(location: Location): Boolean =
        location.world === world &&
            location.blockX to location.blockZ in walkable &&
            Triple(location.blockX, location.blockY, location.blockZ) in air &&
            Triple(location.blockX, location.blockY + 1, location.blockZ) in air

    fun owns(location: Location): Boolean =
        location.world === world && records.any { it.x == location.blockX && it.y == location.blockY && it.z == location.blockZ }

    fun pathDistanceToTarget(location: Location): Int? =
        if (!contains(location)) null else pathDistances[location.blockX to location.blockZ]

    fun targetPosition(): WorksitePosition = WorksitePosition(world.name, target.blockX, target.blockY - 1, target.blockZ)

    private fun distances(origin: Pair<Int, Int>): Map<Pair<Int, Int>, Int> {
        val result = linkedMapOf(origin to 0)
        val queue = ArrayDeque<Pair<Int, Int>>().apply { addLast(origin) }
        while (queue.isNotEmpty()) {
            val (x, z) = queue.removeFirst()
            val distance = result.getValue(x to z) + 1
            listOf(x + 1 to z, x - 1 to z, x to z + 1, x to z - 1)
                .filter { it in walkable && it !in result }
                .forEach { result[it] = distance; queue.addLast(it) }
        }
        return result
    }

    private companion object {
        const val AIR_DATA = "minecraft:air"
    }
}

/**
 * Crash-safe temporary maze owner. It never scans the world: a site is derived
 * from the mine bounds, then only the bounded candidate footprint is validated.
 */
internal class MineLostMinerMazeWorld(
    private val plugin: Plugin,
    private val debug: ArcFarmsDebug,
    private val chunkRetention: MineLostMinerMazeChunkRetention,
    private val blockDataDecoder: FarmBlockDataDecoder = PaperFarmBlockDataDecoder,
) {
    private data class RecordKey(val world: String, val x: Int, val y: Int, val z: Int)
    private data class ChunkKey(val world: String, val x: Int, val z: Int)

    private val journalKey = NamespacedKey(plugin, "mine_lost_miner_maze_v1")
    private val scenes = mutableMapOf<String, MineLostMinerMazeScene>()
    private val builds = ArrayDeque<MineLostMinerMazeJournalRecord>()
    private val restores = ArrayDeque<MineLostMinerMazeJournalRecord>()
    private val queuedBuilds = linkedSetOf<RecordKey>()
    private val queuedRestores = linkedSetOf<RecordKey>()
    private val tickets = linkedMapOf<ChunkKey, MineLostMinerMazeChunkLease>()
    private val requestedChunks = ConcurrentHashMap.newKeySet<ChunkKey>()
    /** Populated by bounded candidate reads and chunk-load callbacks, never by a per-tick world scan. */
    private val journalRecords = mutableMapOf<String, MutableMap<RecordKey, MineLostMinerMazeJournalRecord>>()
    private val readyScenes = mutableSetOf<String>()
    private val retiringScenes = mutableSetOf<String>()

    private data class Capture(val key: String, val world: World, val zone: String, val sequence: Long,
        val surface: Location, val start: Location, val target: Location,
        val entries: List<Map.Entry<Triple<Int,Int,Int>, Pair<String,MineLostMinerMazeMarker>>>,
        val records: MutableList<MineLostMinerMazeJournalRecord> = mutableListOf(), var cursor: Int = 0)
    private val captures = linkedMapOf<String, Capture>()
    private val failedCaptures = mutableSetOf<String>()
    private val decodedData = mutableMapOf<String,String>()

    fun ensure(runtime: MineRuntime, target: WorksitePosition): Pair<MineLostMinerMazeEnsureResult, MineLostMinerMazeScene?> {
        val key = sceneKey(runtime)
        if (failedCaptures.remove(key)) return MineLostMinerMazeEnsureResult.UNAVAILABLE to null
        if (key in captures) return MineLostMinerMazeEnsureResult.BUILDING to null
        if (key in retiringScenes) return MineLostMinerMazeEnsureResult.BUILDING to null
        scenes[key]?.let { scene ->
            if (key in readyScenes) return MineLostMinerMazeEnsureResult.READY to scene
            ticket(scene.world, scene.records)
            enqueueBuild(scene.records)
            val ready = scene.ready
            if (ready) readyScenes += key
            return (if (ready) MineLostMinerMazeEnsureResult.READY else MineLostMinerMazeEnsureResult.BUILDING) to scene
        }
        val layout = MineLostMinerMazePlanner.plan(MAZE_CELLS, seed(runtime, target))
        val candidates = MineLostMinerMazeSitePlanner.candidates(runtime.region.bounds, target, MAZE_CELLS)
            .sortedBy { position ->
                ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed.positionScore(seed(runtime, target),
                    ru.ruscrafting.farms.domain.placement.WorksitePlacementPoint(position.world, position.x.toDouble(), position.y.toDouble(), position.z.toDouble()))
            }
        val candidateChunks = candidates.flatMapTo(linkedSetOf()) { candidateChunkKeys(runtime.region.world, it, layout) }
        if (!requestChunks(runtime.region.world, candidateChunks)) {
            return MineLostMinerMazeEnsureResult.BUILDING to null
        }
        candidateChunks.forEach { chunkKey ->
            index(read(runtime.region.world.getChunkAt(chunkKey.x, chunkKey.z)).orEmpty())
        }
        val recovered = records(runtime.settings.id, runtime.state.sequence)
        if (recovered.isNotEmpty()) {
            val recoveredScene = sceneFromRecords(runtime.region.world, runtime.settings.id, runtime.state.sequence, target, recovered)
            if (recoveredScene == null) {
                debug.event("mine_lost_miner_maze_recovery_incomplete", "zone" to runtime.settings.id, "sequence" to runtime.state.sequence)
                return MineLostMinerMazeEnsureResult.UNAVAILABLE to null
            }
            scenes[key] = recoveredScene
            ticket(recoveredScene.world, recoveredScene.records)
            enqueueBuild(recoveredScene.records)
            val ready = recoveredScene.ready
            if (ready) readyScenes += key
            return (if (ready) MineLostMinerMazeEnsureResult.READY else MineLostMinerMazeEnsureResult.BUILDING) to recoveredScene
        }
        var scene: MineLostMinerMazeScene? = null
        for (anchor in candidates) {
            scene = preview(runtime, anchor, layout, target)
            if (key in captures) return MineLostMinerMazeEnsureResult.BUILDING to null
            if (scene != null) break
        }
        if (scene == null) {
            debug.event("mine_lost_miner_maze_unavailable", "zone" to runtime.settings.id, "sequence" to runtime.state.sequence)
            return MineLostMinerMazeEnsureResult.UNAVAILABLE to null
        }
        if (!commit(scene)) return MineLostMinerMazeEnsureResult.UNAVAILABLE to null
        scenes[key] = scene
        ticket(scene.world, scene.records)
        enqueueBuild(scene.records)
        debug.event(
            "mine_lost_miner_maze_committed",
            "zone" to scene.zoneId,
            "sequence" to scene.sequence,
            "blocks" to scene.records.size,
            "chunks" to scene.records.map { it.x shr 4 to (it.z shr 4) }.distinct().size,
        )
        return MineLostMinerMazeEnsureResult.BUILDING to scene
    }

    fun scene(runtime: MineRuntime): MineLostMinerMazeScene? = scenes[sceneKey(runtime)]

    fun isRestoring(runtime: MineRuntime): Boolean = sceneKey(runtime) in retiringScenes

    fun owns(location: Location): Boolean {
        val key = RecordKey(location.world.name, location.blockX, location.blockY, location.blockZ)
        return key in queuedBuilds || key in queuedRestores || scenes.values.any { it.owns(location) }
    }

    fun rebindTarget(runtime: MineRuntime): WorksitePosition? = scene(runtime)?.targetPosition()

    fun beginRestore(world: World, zoneId: String, sequence: Long) {
        val key = "$zoneId:$sequence"
        captures.remove(key)
        readyScenes.remove(key)
        val all = scenes.remove(key)?.records ?: records(zoneId, sequence)
        if (all.isNotEmpty()) retiringScenes += key
        cancelBuild(all)
        enqueueRestore(all)
    }

    fun process(limit: Int, active: (MineLostMinerMazeJournalRecord) -> Boolean = { true }): Int {
        require(limit >= 1) { "Lost-miner maze block budget must be positive" }
        val captured = captureSlice(limit)
        if (captured > 0) return captured
        val restored = processQueue(restores, queuedRestores, limit, restore = true, active)
        return restored + processQueue(builds, queuedBuilds, limit - restored, restore = false, active)
    }

    fun onChunkLoad(chunk: Chunk, active: (String, Long) -> Boolean) {
        val records = read(chunk) ?: return
        index(records)
        enqueueBuild(records.filter { active(it.zoneId, it.sequence) })
        enqueueRestore(records.filterNot { active(it.zoneId, it.sequence) })
    }

    fun reconcileLoaded(active: (String, Long) -> Boolean) =
        Bukkit.getWorlds().forEach { world -> world.loadedChunks.forEach { onChunkLoad(it, active) } }

    fun clearQueues() {
        captures.clear(); failedCaptures.clear(); decodedData.clear()
        builds.clear(); restores.clear(); queuedBuilds.clear(); queuedRestores.clear(); scenes.clear()
        readyScenes.clear()
        retiringScenes.clear()
        journalRecords.clear()
        requestedChunks.clear()
        tickets.entries.toList().forEach { (key, lease) -> releaseTicket(key, lease) }
    }

    private fun preview(
        runtime: MineRuntime,
        anchor: WorksitePosition,
        layout: MineLostMinerMazeLayout,
        surfaceTarget: WorksitePosition,
    ): MineLostMinerMazeScene? {
        val world = runtime.region.world
        if (anchor.world != world.name) return null
        val originX = anchor.x - layout.start.x
        val originZ = anchor.z - layout.start.z
        val baseY = anchor.y
        if (baseY <= world.minHeight || baseY + 6 >= world.maxHeight) return null
        val layoutSeed = seed(runtime, surfaceTarget)
        val translatedAnchor = anchorPoint(layout, anchor)
        val chambers = MineLostMinerMazePlanner.chamberCells(layout, layoutSeed).mapTo(hashSetOf()) { point ->
            MineLostMinerMazePoint(translatedAnchor.x + point.x - layout.start.x, translatedAnchor.z + point.z - layout.start.z)
        }
        val lamps = MineLostMinerMazePlanner.lampCells(layout).mapTo(hashSetOf()) {
            originX + it.x to originZ + it.z
        }
        val planned = linkedMapOf<Triple<Int, Int, Int>, Pair<String, MineLostMinerMazeMarker>>()
        val floorMaterial = MaterialRules.material(runtime.settings.baseMaterial)
        // The rescue scene is temporary, but its wall must read as natural
        // rock. The configured temporary material used to make a cobble maze.
        val wallMaterial = Material.DEEPSLATE
        if (!floorMaterial.isSolid || floorMaterial.hasGravity()) return null
        if (!wallMaterial.isSolid || wallMaterial.hasGravity()) return null
        val floorData = floorMaterial.createBlockData().asString
        val wallData = wallMaterial.createBlockData().asString
        val lightData = "minecraft:lantern[hanging=true,waterlogged=false]"
        val underfloorLightData = Material.OCHRE_FROGLIGHT.createBlockData().asString
        val postData = "minecraft:stripped_spruce_log[axis=y]"
        val beamData = "minecraft:stripped_spruce_log[axis=x]"
        val chamberSet = chambers.mapTo(hashSetOf()) { it.x to it.z }
        val start = MineLostMinerMazePoint(anchor.x, anchor.z)
        val mazeTarget = MineLostMinerMazePoint(
            originX + layout.target.x,
            originZ + layout.target.z,
        )
        for (x in originX until originX + layout.width) for (z in originZ until originZ + layout.height) {
            val passage = (x to z) in chamberSet
            val lit = passage && (x to z) in lamps
            val ceiling = MineLostMinerMazePlanner.chamberCeiling(layoutSeed, MineLostMinerMazePoint(x - originX + layout.start.x, z - originZ + layout.start.z))
            planned[Triple(x, baseY, z)] = floorData to MineLostMinerMazeMarker.NONE
            if (lit) planned[Triple(x, baseY - 1, z)] = underfloorLightData to MineLostMinerMazeMarker.NONE
            val marker = when (x to z) {
                start.x to start.z -> MineLostMinerMazeMarker.START
                mazeTarget.x to mazeTarget.z -> MineLostMinerMazeMarker.TARGET
                else -> MineLostMinerMazeMarker.NONE
            }
            for (up in 1..5) {
                planned[Triple(x, baseY + up, z)] =
                    (if (passage && up <= ceiling) AIR_DATA else wallData) to
                        (if (up == 1) marker else MineLostMinerMazeMarker.NONE)
            }
            planned[Triple(x, baseY + ceiling + 1, z)] = wallData to MineLostMinerMazeMarker.NONE
            if (lit) planned[Triple(x, baseY + ceiling, z)] = lightData to MineLostMinerMazeMarker.NONE
            if (passage && ((x - originX) * 13 + (z - originZ) * 7) % MAZE_SUPPORT_SPACING == 0) {
                if ((x - 1 to z) !in chamberSet) for (y in baseY + 1..baseY + ceiling) {
                    planned[Triple(x - 1, y, z)] = postData to MineLostMinerMazeMarker.NONE
                }
                if ((x + 1 to z) !in chamberSet) for (y in baseY + 1..baseY + ceiling) {
                    planned[Triple(x + 1, y, z)] = postData to MineLostMinerMazeMarker.NONE
                }
                for (beamX in x - 1..x + 1) {
                    if ((beamX to z) !in chamberSet || beamX == x) {
                        planned[Triple(beamX, baseY + ceiling + 1, z)] = beamData to MineLostMinerMazeMarker.NONE
                    }
                }
                if (lit) planned[Triple(x, baseY + ceiling, z)] = lightData to MineLostMinerMazeMarker.NONE
            }
        }
        if (planned.size > MAX_SCENE_RECORDS) return null
        if (planned.keys.any { (x, _, z) ->
                x in runtime.region.bounds.minX..runtime.region.bounds.maxX && z in runtime.region.bounds.minZ..runtime.region.bounds.maxZ
            }) return null
        val chunks = planned.keys.map { (x, _, z) -> x shr 4 to (z shr 4) }.distinct()
        if (chunks.any { (x, z) -> !world.isChunkLoaded(x, z) }) return null
        if (chunks.any { (x, z) -> read(world.getChunkAt(x, z)) == null }) return null
        val key = sceneKey(runtime)
        captures[key] = Capture(key,world,runtime.settings.id,runtime.state.sequence,
            Location(world,surfaceTarget.x+.5,surfaceTarget.y+1.0,surfaceTarget.z+.5),
            Location(world,start.x+.5,baseY+1.0,start.z+.5),
            Location(world,mazeTarget.x+.5,baseY+1.0,mazeTarget.z+.5),planned.entries.toList())
        return null
    }

    /** Snapshot and validate at most one small slice; never scan a full cave on the event command. */
    private fun captureSlice(limit: Int): Int {
        val capture = captures.values.firstOrNull() ?: return 0
        var count = 0
        while(capture.cursor < capture.entries.size && count < limit) {
            val (p,active) = capture.entries[capture.cursor++]
            if (!capture.world.isChunkLoaded(p.first shr 4,p.third shr 4)) { captures.remove(capture.key); failedCaptures+=capture.key; return count }
            val block=capture.world.getBlockAt(p.first,p.second,p.third)
            if (block.isLiquid || block.type.hasGravity() || block.state is TileState ||
                block.y <= capture.world.minHeight || block.y >= capture.world.maxHeight-1) {
                captures.remove(capture.key); failedCaptures+=capture.key; return count
            }
            val data=decodedData.getOrPut(active.first) { blockDataDecoder.decode(active.first).asString }
            capture.records += MineLostMinerMazeJournalRecord(capture.world.name,capture.zone,capture.sequence,
                p.first,p.second,p.third,block.blockData.asString,data,active.second,capture.entries.size)
            count++
        }
        if(capture.cursor == capture.entries.size) {
            captures.remove(capture.key)
            val scene=MineLostMinerMazeScene(capture.world,capture.zone,capture.sequence,capture.surface,capture.start,capture.target,capture.records)
            if(commit(scene)) { scenes[capture.key]=scene; ticket(scene.world,scene.records); enqueueBuild(scene.records) }
            else failedCaptures+=capture.key
        }
        return count
    }

    private fun commit(scene: MineLostMinerMazeScene): Boolean {
        val grouped = scene.records.groupBy { it.x shr 4 to (it.z shr 4) }
        val previous = linkedMapOf<Chunk, ByteArray?>()
        return runCatching {
            grouped.forEach { (position, additions) ->
                val chunk = scene.world.getChunkAt(position.first, position.second)
                require(scene.world.isChunkLoaded(chunk.x, chunk.z)) { "Lost-miner maze chunk unloaded during commit" }
                val current = read(chunk) ?: error("Lost-miner maze journal is unreadable")
                require(current.none { existing -> additions.any { it.x == existing.x && it.y == existing.y && it.z == existing.z } }) {
                    "Lost-miner maze journal overlaps another scene"
                }
                additions.forEach { record ->
                    require(scene.world.getBlockAt(record.x, record.y, record.z).blockData.asString == record.originalData) {
                        "Lost-miner maze placement changed before commit"
                    }
                }
                previous[chunk] = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY)
                chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY,
                    MineLostMinerMazeJournalCodec.encode(current + additions, chunk.world.name, chunk.x, chunk.z,
                        chunk.world.minHeight, chunk.world.maxHeight))
            }
            index(scene.records)
            true
        }.getOrElse { error ->
            previous.forEach { (chunk, raw) ->
                if (raw == null) chunk.persistentDataContainer.remove(journalKey)
                else chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, raw)
            }
            plugin.logger.log(Level.SEVERE, "Could not durably commit lost-miner maze ${scene.zoneId}/${scene.sequence}", error)
            false
        }
    }

    private fun processQueue(
        queue: ArrayDeque<MineLostMinerMazeJournalRecord>, queued: MutableSet<RecordKey>, limit: Int,
        restore: Boolean, active: (MineLostMinerMazeJournalRecord) -> Boolean,
    ): Int {
        if (limit <= 0) return 0
        val selected = buildList { repeat(minOf(limit, queue.size)) { queue.removeFirst().also { queued.remove(it.key()); add(it) } } }
        var processed = 0
        selected.groupBy { Triple(it.world, it.x shr 4, it.z shr 4) }.forEach { (chunkKey, pending) ->
            val world = Bukkit.getWorld(chunkKey.first) ?: return@forEach
            if (!world.isChunkLoaded(chunkKey.second, chunkKey.third)) {
                enqueue(if (restore) restores else builds, if (restore) queuedRestores else queuedBuilds, pending)
                return@forEach
            }
            val chunk = world.getChunkAt(chunkKey.second, chunkKey.third)
            val current = read(chunk) ?: run {
                enqueue(if (restore) restores else builds, if (restore) queuedRestores else queuedBuilds, pending)
                return@forEach
            }
            val owned = current.filter { existing ->
                pending.any { it.key() == existing.key() } &&
                    if (restore) sceneKey(existing) in retiringScenes || !active(existing) else active(existing)
            }
            val changed = owned.filter { record ->
                val block = world.getBlockAt(record.x, record.y, record.z)
                val currentData = block.blockData.asString
                val desired = if (restore) record.originalData else record.mazeData
                val allowed = if (restore) currentData == record.mazeData || currentData == record.originalData
                else currentData == record.originalData || currentData == record.mazeData
                allowed && apply(record, desired)
            }
            val changedKeys = changed.mapTo(hashSetOf()) { it.key() }
            val failed = owned.filterNot { it.key() in changedKeys }
            if (failed.isNotEmpty()) enqueue(if (restore) restores else builds, if (restore) queuedRestores else queuedBuilds, failed)
            if (restore && changed.isNotEmpty()) {
                write(chunk, current.filterNot { it.key() in changedKeys })
                changed.forEach(::forget)
                if (current.size == changed.size) releaseTicket(chunk)
            }
            processed += changed.size
        }
        return processed
    }

    private fun apply(record: MineLostMinerMazeJournalRecord, serialized: String): Boolean = runCatching {
        val world = Bukkit.getWorld(record.world) ?: return false
        if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return false
        world.getBlockAt(record.x, record.y, record.z).setBlockData(blockDataDecoder.decode(serialized), false)
        true
    }.getOrElse { error ->
        plugin.logger.log(Level.SEVERE, "Could not apply lost-miner maze BlockData at ${record.world}:${record.x},${record.y},${record.z}", error)
        false
    }

    private fun sceneFromRecords(
        world: World,
        zoneId: String,
        sequence: Long,
        surfaceTarget: WorksitePosition,
        records: List<MineLostMinerMazeJournalRecord>,
    ): MineLostMinerMazeScene? {
        val total = records.firstOrNull()?.totalRecords ?: return null
        if (records.size != total || records.any { it.totalRecords != total }) return null
        val start = records.singleOrNull { it.marker == MineLostMinerMazeMarker.START } ?: return null
        val target = records.singleOrNull { it.marker == MineLostMinerMazeMarker.TARGET } ?: return null
        return MineLostMinerMazeScene(
            world, zoneId, sequence,
            Location(world, surfaceTarget.x + 0.5, surfaceTarget.y + 1.0, surfaceTarget.z + 0.5),
            Location(world, start.x + 0.5, start.y.toDouble(), start.z + 0.5),
            Location(world, target.x + 0.5, target.y.toDouble(), target.z + 0.5), records,
        )
    }

    private fun records(zoneId: String, sequence: Long): List<MineLostMinerMazeJournalRecord> =
        journalRecords["$zoneId:$sequence"]?.values?.toList().orEmpty()

    private fun index(records: Collection<MineLostMinerMazeJournalRecord>) {
        records.forEach { record ->
            journalRecords.getOrPut("${record.zoneId}:${record.sequence}") { linkedMapOf() }[record.key()] = record
        }
    }

    private fun forget(record: MineLostMinerMazeJournalRecord) {
        val sceneKey = "${record.zoneId}:${record.sequence}"
        journalRecords[sceneKey]?.let { indexed ->
            indexed.remove(record.key())
            if (indexed.isEmpty()) {
                journalRecords.remove(sceneKey)
                retiringScenes.remove(sceneKey)
            }
        }
    }

    private fun read(chunk: Chunk): List<MineLostMinerMazeJournalRecord>? {
        val raw = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY) ?: return emptyList()
        return runCatching {
            MineLostMinerMazeJournalCodec.decode(raw, chunk.world.name, chunk.x, chunk.z, chunk.world.minHeight, chunk.world.maxHeight)
        }.getOrElse { error ->
            plugin.logger.log(Level.SEVERE, "Could not decode lost-miner maze journal in ${chunk.world.name}:${chunk.x},${chunk.z}", error)
            null
        }
    }

    private fun write(chunk: Chunk, records: List<MineLostMinerMazeJournalRecord>) {
        if (records.isEmpty()) chunk.persistentDataContainer.remove(journalKey)
        else chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY,
            MineLostMinerMazeJournalCodec.encode(records, chunk.world.name, chunk.x, chunk.z, chunk.world.minHeight, chunk.world.maxHeight))
    }

    private fun ticket(world: World, records: Collection<MineLostMinerMazeJournalRecord>) {
        records.map { ChunkKey(it.world, it.x shr 4, it.z shr 4) }.distinct().forEach { key ->
            if (key !in tickets && world.isChunkLoaded(key.x, key.z)) tickets[key] = chunkRetention.retain(world.getChunkAt(key.x, key.z))
        }
    }

    private fun releaseTicket(chunk: Chunk) = releaseTicket(ChunkKey(chunk.world.name, chunk.x, chunk.z), tickets[ChunkKey(chunk.world.name, chunk.x, chunk.z)])

    private fun releaseTicket(key: ChunkKey, lease: MineLostMinerMazeChunkLease?) {
        if (lease == null) return
        runCatching { lease.close() }.onSuccess { tickets.remove(key, lease) }
            .onFailure { plugin.logger.log(Level.WARNING, "Could not release lost-miner maze chunk lease $key", it) }
    }

    private fun enqueueBuild(records: Collection<MineLostMinerMazeJournalRecord>) = enqueue(builds, queuedBuilds, records)
    private fun enqueueRestore(records: Collection<MineLostMinerMazeJournalRecord>) = enqueue(restores, queuedRestores, records)
    private fun enqueue(queue: ArrayDeque<MineLostMinerMazeJournalRecord>, keys: MutableSet<RecordKey>, records: Collection<MineLostMinerMazeJournalRecord>) {
        records.forEach { if (keys.add(it.key())) queue.addLast(it) }
    }
    private fun cancelBuild(records: Collection<MineLostMinerMazeJournalRecord>) {
        val keys = records.mapTo(hashSetOf()) { it.key() }
        builds.removeIf { it.key() in keys }; queuedBuilds.removeAll(keys)
    }
    private fun MineLostMinerMazeJournalRecord.key() = RecordKey(world, x, y, z)
    private fun sceneKey(record: MineLostMinerMazeJournalRecord) = "${record.zoneId}:${record.sequence}"
    private fun sceneKey(runtime: MineRuntime) = "${runtime.settings.id}:${runtime.state.sequence}"
    private fun seed(runtime: MineRuntime, target: WorksitePosition): Long =
        ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed.positionScore(
            ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed.derive(runtime.state.sequence, MAZE_SALT),
            target.world, target.x, target.y, target.z,
        )
    private fun anchorPoint(layout: MineLostMinerMazeLayout, anchor: WorksitePosition) =
        MineLostMinerMazePoint(anchor.x, anchor.z)

    private fun candidateChunkKeys(
        world: World,
        anchor: WorksitePosition,
        layout: MineLostMinerMazeLayout,
    ): Set<ChunkKey> {
        val originX = anchor.x - layout.start.x
        val originZ = anchor.z - layout.start.z
        return buildSet {
            for (x in originX until originX + layout.width) for (z in originZ until originZ + layout.height) {
                add(ChunkKey(world.name, x shr 4, z shr 4))
            }
        }
    }

    /** Requests only the bounded deterministic candidate footprints; never scans all loaded chunks. */
    private fun requestChunks(world: World, chunks: Collection<ChunkKey>): Boolean {
        var ready = true
        chunks.forEach { key ->
            if (world.isChunkLoaded(key.x, key.z)) return@forEach
            ready = false
            if (requestedChunks.add(key)) {
                runCatching { world.getChunkAtAsync(key.x, key.z, true) }
                    .onSuccess { future -> future.whenComplete { _, _ -> requestedChunks.remove(key) } }
                    .onFailure { error ->
                        requestedChunks.remove(key)
                        plugin.logger.log(
                            Level.WARNING,
                            "Could not request lost-miner maze chunk ${world.name}:${key.x},${key.z}",
                            error,
                        )
                    }
            }
        }
        return ready
    }

    private companion object {
        const val MAZE_CELLS = 18
        const val MAZE_SALT = 0x4c4f53544d415a45L
        const val MAX_SCENE_RECORDS = 16_384
        const val MAZE_SUPPORT_SPACING = 11
        const val AIR_DATA = "minecraft:air"
    }
}

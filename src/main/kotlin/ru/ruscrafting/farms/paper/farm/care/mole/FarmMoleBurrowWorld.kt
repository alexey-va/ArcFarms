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
import ru.ruscrafting.farms.config.FarmMoleBurrowSettings
import ru.ruscrafting.farms.domain.FarmMoleBurrowDecorationPlanner
import ru.ruscrafting.farms.domain.FarmMoleBurrowPlanner
import ru.ruscrafting.farms.domain.FarmMolePassage
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.platform.FarmBlockDataDecoder
import ru.ruscrafting.farms.paper.farm.incident.greenhouse.FarmHellRiftRoom
import java.util.ArrayDeque
import java.util.logging.Level
import kotlin.math.floor

internal data class FarmMoleBurrowScene(
    val world: World,
    val zoneId: String,
    val sequence: Long,
    val burrowId: Int,
    val surface: Location,
    val start: Location,
    val lair: Location,
    val records: List<FarmMoleBurrowJournalRecord>,
) {
    private val tunnelPositions = records.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
    private val pathDistanceToLair: Map<Pair<Int, Int>, Int> = buildPathDistances()
    val maxPathDistance: Int = pathDistanceToLair.values.maxOrNull() ?: 0
    private var verifiedReady = false

    val ready: Boolean get() {
        if (verifiedReady) return true
        verifiedReady = records.all { record ->
            if (!world.isChunkLoaded(record.x shr 4, record.z shr 4)) return@all false
            val block = world.getBlockAt(record.x, record.y, record.z)
            block.blockData.asString == record.burrowData
        }
        return verifiedReady
    }

    fun contains(location: Location): Boolean {
        if (location.world !== world || records.isEmpty()) return false
        return Triple(location.blockX, location.blockY, location.blockZ) in tunnelPositions
    }

    fun pathDistanceToLair(location: Location): Int? {
        if (location.world !== world || location.blockY != start.blockY) return null
        return pathDistanceToLair[location.blockX to location.blockZ]
    }

    private fun buildPathDistances(): Map<Pair<Int, Int>, Int> {
        val floorY = start.blockY
        val walkable = records.asSequence()
            .filter { it.y == floorY && (it.burrowData == MOLE_AIR_DATA || it.burrowData.startsWith("minecraft:light")) }
            .mapTo(hashSetOf()) { it.x to it.z }
        val origin = lair.blockX to lair.blockZ
        if (origin !in walkable) return emptyMap()
        val distances = mutableMapOf(origin to 0)
        val queue = ArrayDeque<Pair<Int, Int>>().apply { add(origin) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val nextDistance = distances.getValue(current) + 1
            listOf(
                current.first + 1 to current.second,
                current.first - 1 to current.second,
                current.first to current.second + 1,
                current.first to current.second - 1,
            ).forEach { next ->
                if (next in walkable && distances.putIfAbsent(next, nextDistance) == null) queue.add(next)
            }
        }
        return distances
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

/** The farm owns tunnel columns by X/Z even when its WorldGuard region only covers the surface. */
internal fun FarmRuntime.ownsMoleBurrowRecord(record: FarmMoleBurrowJournalRecord): Boolean {
    if (region.world.name != record.world) return false
    return region.contains(
        Location(region.world, record.x + 0.5, region.bounds.minY.toDouble(), record.z + 0.5),
    )
}

/** Owns crash-safe tunnel mutation and bounded build/restore queues. */
internal class FarmMoleBurrowWorld(
    private val plugin: Plugin,
    private val debug: ArcFarmsDebug,
    private val chunkRetention: MoleBurrowChunkRetention,
    private val blockDataDecoder: FarmBlockDataDecoder,
    private val journalNamespace: String = "farm_mole_burrow",
) {
    private data class SceneKey(val world: String, val zoneId: String, val sequence: Long, val burrowId: Int)
    private data class RecordKey(val world: String, val x: Int, val y: Int, val z: Int)

    private val journalKey = NamespacedKey(plugin, "${journalNamespace}_v1")
    private val logger = plugin.logger
    private val buildQueue = ArrayDeque<FarmMoleBurrowJournalRecord>()
    private val restoreQueue = ArrayDeque<FarmMoleBurrowJournalRecord>()
    private val queuedBuilds = linkedSetOf<RecordKey>()
    private val queuedRestores = linkedSetOf<RecordKey>()
    private val ticketedChunks = linkedMapOf<Triple<String, Int, Int>, MoleBurrowChunkLease>()
    private val scenes = mutableMapOf<SceneKey, FarmMoleBurrowScene>()

    fun preview(runtime: FarmRuntime, surface: FarmPointPosition): FarmMoleBurrowScene? =
        previewDetailed(runtime, surface, runtime.state.placementSequence).scene

    fun previewDetailed(
        runtime: FarmRuntime,
        surface: FarmPointPosition,
        placementSequence: Long = runtime.state.placementSequence,
        burrowId: Int = 0,
    ): FarmMoleBurrowPreview {
        val world = runtime.region.world
        if (surface.world != world.name) return FarmMoleBurrowPreview(null, 0, mapOf("wrong_world" to 1))
        val settings = runtime.settings.moleBurrow
        val rejections = linkedMapOf<String, Int>()
        var layoutAttempts = 0
        fun reject(reason: String, count: Int = 1) {
            rejections[reason] = rejections.getOrDefault(reason, 0) + count
        }
        val layoutSeed = seed(placementSequence xor (burrowId.toLong() shl 40), floor(surface.x).toInt(), floor(surface.z).toInt())
        val raw = FarmMoleBurrowPlanner.plan(
            settings.cells,
            layoutSeed,
            settings.lightSpacing,
            settings.chamberCount,
        )
        val firstRotation = Math.floorMod((placementSequence xor surface.x.toLong() xor surface.z.toLong()).toInt(), 4)
        val depthSpan = settings.maxDepth - settings.minDepth + 1
        val firstDepth = Math.floorMod(layoutSeed.toInt(), depthSpan)
        val surfaceLocation = Location(world, surface.x, surface.y, surface.z)
        layoutProbe@ for (attempt in 0 until minOf(MAX_LAYOUT_PROBES, depthSpan * 4)) {
            layoutAttempts++
            val layout = FarmMoleBurrowPlanner.widen(
                FarmMoleBurrowPlanner.rotate(raw, firstRotation + attempt),
                settings.tunnelWidth,
            )
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
            for (dx in -2..2) for (dz in -2..2) repeat(settings.tunnelHeight) { dy ->
                planned[Triple(lairX + dx, feetY + dy, lairZ + dz)] = AIR_DATA to FarmMoleBurrowMarker.NONE
            }
            layout.chambers.forEach { chamber ->
                val chamberX = originX + chamber.x
                val chamberZ = originZ + chamber.z
                for (dx in -1..1) for (dz in -1..1) repeat(settings.tunnelHeight) { dy ->
                    planned[Triple(chamberX + dx, feetY + dy, chamberZ + dz)] = AIR_DATA to FarmMoleBurrowMarker.NONE
                }
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
            decorate(
                plan = planned,
                feetY = feetY,
                startX = startX,
                startZ = startZ,
                lairX = lairX,
                lairZ = lairZ,
                chamberCenters = layout.chambers.mapTo(linkedSetOf()) { chamber ->
                    FarmMolePassage(originX + chamber.x, originZ + chamber.z)
                },
                tunnelHeight = settings.tunnelHeight,
                seed = layoutSeed,
                percent = settings.decorationPercent,
            )
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
                    burrowId = burrowId,
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
                burrowId = burrowId,
                surface = surfaceLocation,
                start = Location(world, startX + 0.5, feetY.toDouble(), startZ + 0.5),
                lair = Location(world, lairX + 0.5, feetY.toDouble(), lairZ + 0.5),
                records = records,
            ), layoutAttempts, rejections)
        }
        return FarmMoleBurrowPreview(null, layoutAttempts, rejections)
    }

    /**
     * Durably reserves every burrow before the care state becomes visible to players.
     * This keeps the event atomic: either all requested mazes can be restored after a
     * crash, or the care transition is refused and no entrance marker is exposed.
     */
    fun prepare(
        runtime: FarmRuntime,
        targets: List<ru.ruscrafting.farms.domain.FarmCareTarget>,
        placementSequence: Long,
    ): Boolean {
        val moleTargets = targets.filter { it.role == ru.ruscrafting.farms.domain.FarmCareRole.MOLE_MOUND }
        if (moleTargets.isEmpty()) return false
        if (hasLoadedSceneRecords(runtime.region.world, runtime.settings.id, runtime.state.sequence)) {
            logger.warning(
                "Could not prepare mole burrows: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "reason=existing_journal",
            )
            return false
        }
        val plans = moleTargets.mapNotNull { target ->
            previewDetailed(runtime, target.position, placementSequence, target.id).scene
        }
        if (plans.size != moleTargets.size) {
            logger.warning(
                "Could not prepare mole burrows: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "reason=layout_unavailable requested=${moleTargets.size} planned=${plans.size}",
            )
            return false
        }
        if (!commit(plans)) return false
        plans.forEach { plan ->
            scenes[SceneKey(plan.world.name, plan.zoneId, plan.sequence, plan.burrowId)] = plan
            ticket(plan)
            enqueueBuild(plan.records)
        }
        debug.event(
            "farm_mole_burrows_prepared",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "burrows" to plans.size,
            "blocks" to plans.sumOf { it.records.size },
        )
        return true
    }

    fun ensure(
        runtime: FarmRuntime,
        surface: FarmPointPosition,
        burrowId: Int = 0,
    ): Pair<FarmMoleBurrowEnsureResult, FarmMoleBurrowScene?> {
        val stored = scene(
            runtime.region.world,
            runtime.settings.id,
            runtime.state.sequence,
            burrowId,
            surface,
            recoveryRadius(runtime.settings.moleBurrow),
        )
        if (stored != null) {
            ticket(stored)
            enqueueBuild(stored.records)
            return (if (stored.ready) FarmMoleBurrowEnsureResult.READY else FarmMoleBurrowEnsureResult.BUILDING) to stored
        }
        if (hasLoadedSceneRecords(runtime.region.world, runtime.settings.id, runtime.state.sequence, burrowId)) {
            beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)
            return FarmMoleBurrowEnsureResult.BUILDING to null
        }
        val preview = previewDetailed(runtime, surface, burrowId = burrowId)
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
        if (!commit(listOf(plan))) return FarmMoleBurrowEnsureResult.UNAVAILABLE to null
        scenes[SceneKey(plan.world.name, plan.zoneId, plan.sequence, plan.burrowId)] = plan
        ticket(plan)
        enqueueBuild(plan.records)
        debug.event(
            "farm_mole_burrow_committed",
            "zone" to plan.zoneId,
            "sequence" to plan.sequence,
            "burrow" to plan.burrowId,
            "blocks" to plan.records.size,
            "chunks" to plan.records.map { (it.x shr 4) to (it.z shr 4) }.distinct().size,
        )
        return FarmMoleBurrowEnsureResult.BUILDING to plan
    }

    /** Prepares the bounded underground room used by greenhouse scenes. */
    fun previewGreenhouseChamber(
        runtime: FarmRuntime,
        surface: FarmPointPosition,
    ): FarmMoleBurrowScene? {
        val world = runtime.region.world
        if (surface.world != world.name) return null
        val settings = runtime.settings.moleBurrow
        val depthSpan = settings.maxDepth - settings.minDepth + 1
        if (depthSpan <= 0) return null
        val depth = settings.minDepth + Math.floorMod(
            seed(runtime.state.placementSequence, floor(surface.x).toInt(), floor(surface.z).toInt()).toInt(),
            depthSpan,
        )
        val centerX = floor(surface.x).toInt()
        val centerZ = floor(surface.z).toInt()
        val feetY = floor(surface.y).toInt() - depth
        if (feetY - 1 <= world.minHeight || feetY + FarmHellRiftRoom.CEILING >= world.maxHeight || feetY + FarmHellRiftRoom.CEILING >= floor(surface.y).toInt()) return null
        val planned = ru.ruscrafting.farms.paper.farm.incident.greenhouse.FarmHellRiftRoom.blocks(centerX, feetY, centerZ)
        val positions = planned.keys
        val chunks = positions.map { (x, _, z) -> (x shr 4) to (z shr 4) }.distinct()
        if (chunks.any { (x, z) -> !world.isChunkLoaded(x, z) }) return null
        if (chunks.any { (x, z) ->
                val chunk = world.getChunkAt(x, z)
                read(chunk) == null || foreignJournalOverlaps(chunk, positions)
            }) return null
        val blocks = positions.map { (x, y, z) -> world.getBlockAt(x, y, z) }
        if (viabilityFailures(runtime, blocks, emptySet(), floor(surface.y).toInt()).isNotEmpty()) return null
        val total = planned.size
        val records = planned.map { (position, active) ->
            val block = world.getBlockAt(position.first, position.second, position.third)
            FarmMoleBurrowJournalRecord(
                world = world.name,
                zoneId = runtime.settings.id,
                sequence = runtime.state.sequence,
                burrowId = FarmHellRiftRoom.BURROW_ID,
                x = position.first,
                y = position.second,
                z = position.third,
                originalData = block.blockData.asString,
                burrowData = active.first,
                marker = active.second,
                totalRecords = total,
            )
        }
        return FarmMoleBurrowScene(
            world,
            runtime.settings.id,
            runtime.state.sequence,
            FarmHellRiftRoom.BURROW_ID,
            Location(world, surface.x, surface.y, surface.z),
            Location(world, centerX + 0.5, feetY.toDouble(), centerZ + 0.5),
            Location(world, centerX + 0.5, feetY.toDouble(), centerZ + 1.5),
            records,
        )
    }

    fun previewGreenhouseChamberDetailed(
        runtime: FarmRuntime,
        surface: FarmPointPosition,
    ): FarmMoleBurrowPreview {
        val scene = previewGreenhouseChamber(runtime, surface)
        if (scene != null) return FarmMoleBurrowPreview(scene, 1, emptyMap())
        if (surface.world != runtime.region.world.name) {
            return FarmMoleBurrowPreview(null, 0, mapOf("wrong_world" to 1))
        }
        val settings = runtime.settings.moleBurrow
        if (settings.minDepth > settings.maxDepth) {
            return FarmMoleBurrowPreview(null, 0, mapOf("invalid_depth_range" to 1))
        }
        val x = floor(surface.x).toInt()
        val z = floor(surface.z).toInt()
        val depth = settings.minDepth + Math.floorMod(
            seed(runtime.state.placementSequence, x, z).toInt(), settings.maxDepth - settings.minDepth + 1)
        val feetY = floor(surface.y).toInt() - depth
        if (feetY - 1 <= runtime.region.world.minHeight ||
            feetY + FarmHellRiftRoom.CEILING >= runtime.region.world.maxHeight ||
            feetY + FarmHellRiftRoom.CEILING >= floor(surface.y).toInt()
        ) return FarmMoleBurrowPreview(null, 1, mapOf("world_height" to 1))
        val positions = FarmHellRiftRoom.blocks(x, feetY, z).keys.toList()
        val chunks = positions.map { (px, _, pz) -> (px shr 4) to (pz shr 4) }.distinct()
        if (chunks.any { (cx, cz) -> !runtime.region.world.isChunkLoaded(cx, cz) }) {
            return FarmMoleBurrowPreview(null, 1, mapOf("unloaded_chunk" to 1))
        }
        if (chunks.any { (cx, cz) -> foreignJournalOverlaps(runtime.region.world.getChunkAt(cx, cz), positions) }) {
            return FarmMoleBurrowPreview(null, 1, mapOf("journal_collision" to 1))
        }
        val failures = viabilityFailures(
            runtime,
            positions.map { (px, py, pz) -> runtime.region.world.getBlockAt(px, py, pz) },
            emptySet(),
            floor(surface.y).toInt(),
        )
        return FarmMoleBurrowPreview(null, 1, failures.ifEmpty { mapOf("unavailable" to 1) })
    }

    fun ensureGreenhouseChamber(
        runtime: FarmRuntime,
        surface: FarmPointPosition,
    ): Pair<FarmMoleBurrowEnsureResult, FarmMoleBurrowScene?> {
        val existing = scene(
            runtime.region.world,
            runtime.settings.id,
            runtime.state.sequence,
            FarmHellRiftRoom.BURROW_ID,
            surface,
            recoveryRadius(runtime.settings.moleBurrow),
        )
        if (existing != null) {
            ticket(existing)
            enqueueBuild(existing.records)
            return (if (existing.ready) FarmMoleBurrowEnsureResult.READY else FarmMoleBurrowEnsureResult.BUILDING) to existing
        }
        if (hasLoadedSceneRecords(runtime.region.world, runtime.settings.id, runtime.state.sequence, FarmHellRiftRoom.BURROW_ID)) {
            beginRestore(runtime.region.world, runtime.settings.id, runtime.state.sequence)
            return FarmMoleBurrowEnsureResult.BUILDING to null
        }
        val plan = previewGreenhouseChamber(runtime, surface) ?: return FarmMoleBurrowEnsureResult.UNAVAILABLE to null
        if (!commit(listOf(plan))) return FarmMoleBurrowEnsureResult.UNAVAILABLE to null
        scenes[SceneKey(plan.world.name, plan.zoneId, plan.sequence, plan.burrowId)] = plan
        ticket(plan)
        enqueueBuild(plan.records)
        return FarmMoleBurrowEnsureResult.BUILDING to plan
    }

    fun scene(runtime: FarmRuntime, burrowId: Int): FarmMoleBurrowScene? {
        val surface = runtime.state.careTargets.firstOrNull { it.id == burrowId }?.position ?: return null
        return scene(
            runtime.region.world,
            runtime.settings.id,
            runtime.state.sequence,
            burrowId,
            surface,
            recoveryRadius(runtime.settings.moleBurrow),
        )
    }

    fun scenes(runtime: FarmRuntime): List<FarmMoleBurrowScene> = runtime.state.careTargets.asSequence()
        .filter { it.role == ru.ruscrafting.farms.domain.FarmCareRole.MOLE_MOUND }
        .mapNotNull { target -> scene(runtime, target.id) }
        .toList()

    fun beginRestore(world: World, zoneId: String, sequence: Long) {
        scenes.keys.filter { it.world == world.name && it.zoneId == zoneId && it.sequence == sequence }
            .toList().forEach(scenes::remove)
        val records = world.loadedChunks.asSequence().flatMap { read(it).orEmpty().asSequence() }
            .filter { it.zoneId == zoneId && it.sequence == sequence }
            .toList()
        cancelBuild(records)
        // Restore the visible entrance first. This prevents ordinary field
        // maintenance from briefly rebuilding a generic bed before the exact
        // journalled crop age and farmland moisture are applied.
        enqueueRestore(records.sortedByDescending(FarmMoleBurrowJournalRecord::y))
    }

    fun restoring(zoneId: String): Boolean = restoreQueue.any { it.zoneId == zoneId }

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
        enqueueBuild(records.filter { active(it.zoneId, it.sequence) && (journalNamespace != "farm_greenhouse" || it.burrowId == FarmHellRiftRoom.BURROW_ID) })
        enqueueRestore(
            records.filterNot { active(it.zoneId, it.sequence) && (journalNamespace != "farm_greenhouse" || it.burrowId == FarmHellRiftRoom.BURROW_ID) }
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
        ticketedChunks.entries.toList().forEach { (key, lease) -> releaseTicket(key, lease) }
    }

    private fun viabilityFailures(
        runtime: FarmRuntime,
        blocks: List<Block>,
        shaftPositions: Set<Triple<Int, Int, Int>>,
        surfaceBlockY: Int,
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
            if (!replaceableForBurrow(runtime, block, shaftPositions, surfaceBlockY)) {
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
    ): Boolean {
        // The burrow is journalled and restored byte-for-byte, so an allow-list of
        // underground "safe" rock only creates false negatives. Any ordinary block
        // or existing cave is valid. Liquids and gravity blocks are the only hard
        // exclusions because opening them can flood or collapse the active tunnel.
        if (!block.isLiquid && !block.type.hasGravity()) return true
        val position = Triple(block.x, block.y, block.z)
        if (position !in shaftPositions || block.y !in surfaceBlockY - 1..surfaceBlockY) return false
        if (block.y == surfaceBlockY) {
            return block.type.isAir || FarmBlockPolicy.isOpenBedContent(block.type, runtime.settings.crops)
        }
        val above = block.getRelative(org.bukkit.block.BlockFace.UP)
        return FarmBlockPolicy.isSelectableBed(block.type, above.type, runtime.settings.crops)
    }

    private fun commit(scenePlans: Collection<FarmMoleBurrowScene>): Boolean {
        require(scenePlans.isNotEmpty()) { "Mole burrow commit is empty" }
        val allRecords = scenePlans.flatMap(FarmMoleBurrowScene::records)
        require(allRecords.map { Triple(it.x, it.y, it.z) }.distinct().size == allRecords.size) {
            "Mole burrow scenes overlap"
        }
        val scene = scenePlans.first()
        val groups = allRecords.groupBy { (it.x shr 4) to (it.z shr 4) }
        val prepared = linkedMapOf<Chunk, Pair<ByteArray?, ByteArray>>()
        return runCatching {
            groups.forEach { (chunkPosition, additions) ->
                val chunk = scene.world.getChunkAt(chunkPosition.first, chunkPosition.second)
                require(scene.world.isChunkLoaded(chunk.x, chunk.z)) { "Mole burrow chunk unloaded during commit" }
                val current = read(chunk) ?: error("Mole burrow journal is unreadable")
                val positions = additions.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
                require(current.none { Triple(it.x, it.y, it.z) in positions }) { "Mole burrow journal overlaps another scene" }
                require(!foreignJournalOverlaps(chunk, positions)) { "Foreign temporary journal overlaps another scene" }
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
            logger.log(Level.SEVERE, "Could not durably commit mole burrows ${scene.zoneId}/${scene.sequence}", failure)
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
            val world = Bukkit.getWorld(chunkKey.first) ?: run {
                enqueue(pending, queue, queued)
                return@forEach
            }
            if (!world.isChunkLoaded(chunkKey.second, chunkKey.third)) {
                // Chunk availability is transient. Dropping these entries used to leave a
                // committed scene permanently half-built (or half-restored) after one
                // unlucky unload between selection and mutation.
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
        val data = runCatching { blockDataDecoder.decode(raw) }.getOrElse { failure ->
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
        burrowId: Int,
        surface: FarmPointPosition,
        recoveryRadius: Int,
    ): FarmMoleBurrowScene? {
        val key = SceneKey(world.name, zoneId, sequence, burrowId)
        scenes[key]?.let { return it }
        var records = world.loadedChunks.asSequence().flatMap { read(it).orEmpty().asSequence() }
            .filter { it.zoneId == zoneId && it.sequence == sequence && it.burrowId == burrowId }
            .toList()
        if (records.isEmpty()) return null
        val expected = records.first().totalRecords
        if (records.size != expected) {
            loadRecoveryChunks(world, surface, recoveryRadius)
            records = world.loadedChunks.asSequence().flatMap { read(it).orEmpty().asSequence() }
                .filter { it.zoneId == zoneId && it.sequence == sequence && it.burrowId == burrowId }
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
            burrowId,
            Location(world, surface.x, surface.y, surface.z),
            Location(world, start.x + 0.5, start.y.toDouble(), start.z + 0.5),
            Location(world, lair.x + 0.5, lair.y.toDouble(), lair.z + 0.5),
            records,
        ).also { scenes[key] = it }
    }

    private fun hasLoadedSceneRecords(world: World, zoneId: String, sequence: Long, burrowId: Int): Boolean =
        world.loadedChunks.any { chunk ->
            read(chunk).orEmpty().any { it.zoneId == zoneId && it.sequence == sequence && it.burrowId == burrowId }
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
            if (key !in ticketedChunks && world.isChunkLoaded(key.second, key.third)) {
                ticketedChunks[key] = chunkRetention.retain(world.getChunkAt(key.second, key.third))
            }
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
        ticketedChunks[key]?.let { lease -> releaseTicket(key, lease) }
    }

    private fun releaseTicket(
        key: Triple<String, Int, Int>,
        lease: MoleBurrowChunkLease,
    ) {
        runCatching(lease::close)
            .onSuccess { ticketedChunks.remove(key, lease) }
            .onFailure { failure ->
                logger.log(
                    Level.WARNING,
                    "Could not release mole burrow chunk lease ${key.first}:${key.second},${key.third}; will retry",
                    failure,
                )
            }
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

    private fun foreignJournalOverlaps(
        chunk: Chunk,
        positions: Collection<Triple<Int, Int, Int>>,
    ): Boolean {
        val wanted = positions.toHashSet()
        return FOREIGN_JOURNAL_NAMESPACES
            .asSequence()
            .filter { it != journalNamespace }
            .map { NamespacedKey(plugin, "${it}_v1") }
            .any { key ->
                val raw = chunk.persistentDataContainer.get(key, PersistentDataType.BYTE_ARRAY) ?: return@any false
                runCatching {
                    FarmMoleBurrowJournalCodec.decode(
                        raw,
                        chunk.world.name,
                        chunk.x,
                        chunk.z,
                        chunk.world.minHeight,
                        chunk.world.maxHeight,
                    ).any { Triple(it.x, it.y, it.z) in wanted }
                }.getOrElse { true }
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

    private fun decorate(
        plan: MutableMap<Triple<Int, Int, Int>, Pair<String, FarmMoleBurrowMarker>>,
        feetY: Int,
        startX: Int,
        startZ: Int,
        lairX: Int,
        lairZ: Int,
        chamberCenters: Set<FarmMolePassage>,
        tunnelHeight: Int,
        seed: Long,
        percent: Int,
    ) {
        val openFloor = plan.keys.asSequence()
            .filter { it.second == feetY }
            .mapTo(linkedSetOf()) { FarmMolePassage(it.first, it.third) }
        FarmMoleBurrowDecorationPlanner.plan(
            openFloor = openFloor,
            start = FarmMolePassage(startX, startZ),
            lair = FarmMolePassage(lairX, lairZ),
            chambers = chamberCenters,
            tunnelHeight = tunnelHeight,
            seed = seed,
            accentPercent = percent,
        ).forEach { decoration ->
            val position = Triple(decoration.position.x, feetY + decoration.yOffset, decoration.position.z)
            val previous = plan[position]
            if (decoration.yOffset == 0 && previous?.second in setOf(
                    FarmMoleBurrowMarker.START,
                    FarmMoleBurrowMarker.LAIR,
                )
            ) return@forEach
            val material = Material.valueOf(decoration.material)
            // Domain planning already keeps solid accents on room walls. This is a
            // second boundary guard against any future decoration replacing walkable
            // feet/head air and recreating an impassable tunnel.
            if (decoration.yOffset in 0..1 && decoration.position in openFloor && material.isSolid) return@forEach
            val marker = previous?.second ?: FarmMoleBurrowMarker.NONE
            plan[position] = material.createBlockData().asString to marker
        }
    }

    private fun recoveryRadius(settings: FarmMoleBurrowSettings): Int =
        settings.cells * 2 * settings.tunnelWidth + 2

    private fun seed(sequence: Long, x: Int, z: Int): Long = sequence * 0x9E3779B97F4A7C15UL.toLong() xor
        x.toLong() * 0xBF58476D1CE4E5B9UL.toLong() xor z.toLong() * 0x94D049BB133111EBUL.toLong()

    private companion object {
        const val MAX_LAYOUT_PROBES = 64
        const val DEPTH_PROBE_STEP = 5
        val AIR_DATA: String = Material.AIR.createBlockData().asString
        val BARRIER_DATA: String = Material.BARRIER.createBlockData().asString
        val FOREIGN_JOURNAL_NAMESPACES = setOf("farm_mole_burrow", "farm_greenhouse")
    }
}

private val MOLE_AIR_DATA: String = Material.AIR.createBlockData().asString

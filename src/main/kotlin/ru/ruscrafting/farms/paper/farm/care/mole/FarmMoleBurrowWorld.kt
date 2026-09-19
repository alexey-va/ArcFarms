package ru.ruscrafting.farms.paper.farm.care.mole

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
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.platform.FarmBlockDataDecoder
import ru.ruscrafting.farms.paper.farm.incident.greenhouse.FarmHellRiftRoom
import java.util.logging.Level
import kotlin.math.floor
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneBlockDataDecoder
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneChunkRetention
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneEnsureResult
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneOwner
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneRecord
import java.util.ArrayDeque

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

private fun FarmMoleBurrowJournalRecord.toPreparedRecord(): WorksitePreparedSceneRecord = WorksitePreparedSceneRecord(
    world = world,
    zoneId = zoneId,
    sequence = sequence,
    sceneId = burrowId,
    x = x,
    y = y,
    z = z,
    originalData = originalData,
    activeData = burrowData,
    marker = marker.name,
    totalRecords = totalRecords,
)

private fun WorksitePreparedSceneRecord.toFarmRecord(): FarmMoleBurrowJournalRecord = FarmMoleBurrowJournalRecord(
    world = world,
    zoneId = zoneId,
    sequence = sequence,
    burrowId = sceneId,
    x = x,
    y = y,
    z = z,
    originalData = originalData,
    burrowData = activeData,
    marker = FarmMoleBurrowMarker.valueOf(marker),
    totalRecords = totalRecords,
)

private fun FarmMoleBurrowScene.toPreparedScene(): WorksitePreparedScene = WorksitePreparedScene(
    world = world,
    zoneId = zoneId,
    sequence = sequence,
    sceneId = burrowId,
    surface = surface,
    start = start,
    end = lair,
    records = records.map(FarmMoleBurrowJournalRecord::toPreparedRecord),
)

private fun WorksitePreparedScene.toFarmScene(): FarmMoleBurrowScene = FarmMoleBurrowScene(
    world = world,
    zoneId = zoneId,
    sequence = sequence,
    burrowId = sceneId,
    surface = surface,
    start = start,
    lair = end,
    records = records.map(WorksitePreparedSceneRecord::toFarmRecord),
)

private fun WorksitePreparedSceneEnsureResult.toFarmEnsureResult(): FarmMoleBurrowEnsureResult = when (this) {
    WorksitePreparedSceneEnsureResult.BUILDING -> FarmMoleBurrowEnsureResult.BUILDING
    WorksitePreparedSceneEnsureResult.READY -> FarmMoleBurrowEnsureResult.READY
    WorksitePreparedSceneEnsureResult.UNAVAILABLE -> FarmMoleBurrowEnsureResult.UNAVAILABLE
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
    private val logger = plugin.logger
    private val journalKey = NamespacedKey(plugin, "${journalNamespace}_v1")
    private val preparedSceneCodec = FarmMoleBurrowWorksiteSceneCodec(plugin, journalNamespace)
    private val preparedSceneOwner = WorksitePreparedSceneOwner(
        plugin = plugin,
        namespace = journalNamespace,
        codec = preparedSceneCodec,
        chunkRetention = WorksitePreparedSceneChunkRetention { chunk -> chunkRetention.retain(chunk) },
        blockDataDecoder = WorksitePreparedSceneBlockDataDecoder { raw -> blockDataDecoder.decode(raw) },
        logger = logger,
    )

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
        val layoutSeed = WorksiteDeterministicSeed.gridScore(
            placementSequence xor (burrowId.toLong() shl 40),
            floor(surface.x).toInt(),
            floor(surface.z).toInt(),
        )
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
        if (!preparedSceneOwner.prepare(plans.map(FarmMoleBurrowScene::toPreparedScene))) return false
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
        var preview: FarmMoleBurrowPreview? = null
        val (status, prepared) = preparedSceneOwner.ensurePreparedScene(
            world = runtime.region.world,
            zoneId = runtime.settings.id,
            sequence = runtime.state.sequence,
            sceneId = burrowId,
            surface = Location(runtime.region.world, surface.x, surface.y, surface.z),
            recoveryRadius = recoveryRadius(runtime.settings.moleBurrow),
            prepare = {
                preview = previewDetailed(runtime, surface, burrowId = burrowId)
                preview?.scene?.toPreparedScene()
            },
            startMarker = FarmMoleBurrowMarker.START.name,
            endMarker = FarmMoleBurrowMarker.LAIR.name,
        )
        if (status == WorksitePreparedSceneEnsureResult.UNAVAILABLE) {
            val unavailable = preview ?: previewDetailed(runtime, surface, burrowId = burrowId)
            logger.warning(
                "Could not build mole burrow: zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                    "surface=${surface.x},${surface.y},${surface.z} probes=${unavailable.layoutAttempts} " +
                    "rejections=${unavailable.rejectionSummary()}",
            )
            debug.event(
                "farm_mole_burrow_unavailable", "zone" to runtime.settings.id,
                "sequence" to runtime.state.sequence, "probes" to unavailable.layoutAttempts,
                "rejections" to unavailable.rejectionSummary(),
            )
            return FarmMoleBurrowEnsureResult.UNAVAILABLE to null
        }
        val plan = prepared ?: return FarmMoleBurrowEnsureResult.BUILDING to null
        val farmPlan = plan.toFarmScene()
        if (preview != null) {
            debug.event(
                "farm_mole_burrow_committed",
                "zone" to farmPlan.zoneId,
                "sequence" to farmPlan.sequence,
                "burrow" to farmPlan.burrowId,
                "blocks" to farmPlan.records.size,
                "chunks" to farmPlan.records.map { (it.x shr 4) to (it.z shr 4) }.distinct().size,
            )
        }
        return status.toFarmEnsureResult() to farmPlan
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
            WorksiteDeterministicSeed.gridScore(
                runtime.state.placementSequence,
                floor(surface.x).toInt(),
                floor(surface.z).toInt(),
            ).toInt(),
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
            WorksiteDeterministicSeed.gridScore(runtime.state.placementSequence, x, z).toInt(),
            settings.maxDepth - settings.minDepth + 1,
        )
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
        val (status, prepared) = preparedSceneOwner.ensurePreparedScene(
            world = runtime.region.world,
            zoneId = runtime.settings.id,
            sequence = runtime.state.sequence,
            sceneId = FarmHellRiftRoom.BURROW_ID,
            surface = Location(runtime.region.world, surface.x, surface.y, surface.z),
            recoveryRadius = recoveryRadius(runtime.settings.moleBurrow),
            prepare = { previewGreenhouseChamber(runtime, surface)?.toPreparedScene() },
            startMarker = FarmMoleBurrowMarker.START.name,
            endMarker = FarmMoleBurrowMarker.LAIR.name,
        )
        return status.toFarmEnsureResult() to prepared?.toFarmScene()
    }

    /** Uses the same journal, build budget and restoration path for every temporary worksite room. */
    fun ensurePreparedScene(
        world: World,
        zoneId: String,
        sequence: Long,
        sceneId: Int,
        surface: FarmPointPosition,
        recoveryRadius: Int,
        prepare: () -> FarmMoleBurrowScene?,
    ): Pair<FarmMoleBurrowEnsureResult, FarmMoleBurrowScene?> {
        val (status, scene) = preparedSceneOwner.ensurePreparedScene(
            world = world,
            zoneId = zoneId,
            sequence = sequence,
            sceneId = sceneId,
            surface = Location(world, surface.x, surface.y, surface.z),
            recoveryRadius = recoveryRadius,
            prepare = { prepare()?.toPreparedScene() },
            startMarker = FarmMoleBurrowMarker.START.name,
            endMarker = FarmMoleBurrowMarker.LAIR.name,
        )
        return status.toFarmEnsureResult() to scene?.toFarmScene()
    }

    fun scene(runtime: FarmRuntime, burrowId: Int): FarmMoleBurrowScene? {
        val surface = runtime.state.careTargets.firstOrNull { it.id == burrowId }?.position ?: return null
        return preparedSceneOwner.scene(
            runtime.region.world,
            runtime.settings.id,
            runtime.state.sequence,
            burrowId,
            Location(runtime.region.world, surface.x, surface.y, surface.z),
            recoveryRadius(runtime.settings.moleBurrow),
            startMarker = FarmMoleBurrowMarker.START.name,
            endMarker = FarmMoleBurrowMarker.LAIR.name,
        )?.toFarmScene()
    }

    fun scenes(runtime: FarmRuntime): List<FarmMoleBurrowScene> = runtime.state.careTargets.asSequence()
        .filter { it.role == ru.ruscrafting.farms.domain.FarmCareRole.MOLE_MOUND }
        .mapNotNull { target -> scene(runtime, target.id) }
        .toList()

    fun beginRestore(world: World, zoneId: String, sequence: Long) {
        preparedSceneOwner.beginRestore(world, zoneId, sequence)
    }

    fun hasPendingBlock(location: Location): Boolean {
        return preparedSceneOwner.hasPendingBlock(location)
    }

    fun protects(location: Location): Boolean = preparedSceneOwner.protects(location)

    fun restoring(zoneId: String): Boolean = preparedSceneOwner.restoring(zoneId)

    fun process(limit: Int, allowed: (FarmMoleBurrowJournalRecord) -> Boolean): Int {
        return preparedSceneOwner.process(limit, retryRejected = false) { record -> allowed(record.toFarmRecord()) }
    }

    fun onChunkLoad(chunk: Chunk, active: (String, Long) -> Boolean) {
        preparedSceneOwner.onChunkLoad(chunk, active)
    }

    fun reconcileLoaded(active: (String, Long) -> Boolean) {
        preparedSceneOwner.reconcileLoaded(active)
    }

    fun clearQueues() {
        preparedSceneOwner.clearQueues()
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

    private fun read(chunk: Chunk): List<FarmMoleBurrowJournalRecord>? {
        val raw = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY) ?: return emptyList()
        return runCatching {
            FarmMoleBurrowJournalCodec.decode(
                raw,
                chunk.world.name,
                chunk.x,
                chunk.z,
                chunk.world.minHeight,
                chunk.world.maxHeight,
            )
        }.getOrElse { failure ->
            logger.log(Level.SEVERE, "Could not decode mole burrow journal in ${chunk.world.name}:${chunk.x},${chunk.z}", failure)
            null
        }
    }

    private fun hasLoadedSceneRecords(world: World, zoneId: String, sequence: Long): Boolean =
        world.loadedChunks.any { chunk -> read(chunk).orEmpty().any { it.zoneId == zoneId && it.sequence == sequence } }

    private fun foreignJournalOverlaps(
        chunk: Chunk,
        positions: Collection<Triple<Int, Int, Int>>,
    ): Boolean = preparedSceneCodec.foreignJournalOverlaps(chunk, positions)

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

    private companion object {
        const val MAX_LAYOUT_PROBES = 64
        const val DEPTH_PROBE_STEP = 5
        val AIR_DATA: String = Material.AIR.createBlockData().asString
        val BARRIER_DATA: String = Material.BARRIER.createBlockData().asString
    }
}

private val MOLE_AIR_DATA: String = Material.AIR.createBlockData().asString

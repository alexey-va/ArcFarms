package ru.ruscrafting.farms.paper.farm.care.mole

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.Levelled
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.FarmMoleBurrowDecorationPlanner
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import ru.ruscrafting.farms.paper.FarmBlockPolicy
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.incident.greenhouse.FarmHellRiftRoom
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import java.util.concurrent.CompletableFuture
import kotlin.math.floor

/** Geometry/journal work stays detached; the existing runtime queue owns all block and PDC capture. */
internal class FarmMoleAsyncScenes(
    private val tasks: WorksiteTaskPort,
    plugin: Plugin,
    namespace: String,
    private val codec: FarmMoleBurrowWorksiteSceneCodec,
) {
    private data class Key(val zone: String, val sequence: Long, val placement: Long,
        val surface: FarmPointPosition, val id: Int, val greenhouse: Boolean)
    private data class ChunkCapture(val x: Int, val z: Int, val raw: ByteArray?, val foreign: () -> Boolean)
    private val journalKey = NamespacedKey(plugin, "${namespace}_v1")
    private var epoch = 0L
    private val previews = mutableMapOf<Key, CompletableFuture<FarmMoleBurrowPreview>>()
    private val converted = java.util.IdentityHashMap<WorksitePreparedScene, FarmMoleBurrowScene>()
    private val conversions = java.util.IdentityHashMap<WorksitePreparedScene, CompletableFuture<FarmMoleBurrowScene>>()

    fun clear() {
        epoch++
        previews.clear()
        converted.clear()
        conversions.clear()
    }

    fun convert(scene: WorksitePreparedScene): FarmMoleBurrowScene? {
        converted[scene]?.let { return it }
        if (conversions.containsKey(scene)) return null
        val source = epoch
        val token = tasks.lifecycleToken()
        val floorY = scene.start.blockY
        val lairX = scene.end.blockX
        val lairZ = scene.end.blockZ
        val result = tasks.compute {
            val records = scene.records.map { it.toFarmRecord() }
            records to moleSceneIndex(records, floorY, lairX, lairZ)
        }.thenCompose { (records, index) -> tasks.callSync(token) {
            check(source == epoch) { "Farm scene conversion superseded" }
            FarmMoleBurrowScene(scene.world, scene.zoneId, scene.sequence, scene.sceneId,
                scene.surface, scene.start, scene.end, records, index).also {
                it.completionProbe = { scene.ready }
                converted[scene] = it
            }
        } }
        conversions[scene] = result
        result.whenComplete { _, _ -> tasks.callSync(token) { conversions.remove(scene, result) } }
        return converted[scene]
    }

    fun prepareView(scene: FarmMoleBurrowScene): CompletableFuture<WorksitePreparedScene> {
        val source = epoch
        val token = tasks.lifecycleToken()
        return tasks.compute { scene.records.map { it.toPreparedRecord() } }.thenCompose { records -> tasks.callSync(token) {
            check(source == epoch)
            WorksitePreparedScene(scene.world, scene.zoneId, scene.sequence, scene.burrowId,
                scene.surface, scene.start, scene.lair, records).also {
                converted[it] = scene
                scene.completionProbe = { it.ready }
            }
        } }
    }

    fun preview(runtime: FarmRuntime, surface: FarmPointPosition, placementSequence: Long, burrowId: Int,
        greenhouse: Boolean = false): CompletableFuture<FarmMoleBurrowPreview> {
        val world = runtime.region.world
        val worldName = world.name
        if (surface.world != worldName) return CompletableFuture.completedFuture(FarmMoleBurrowPreview(null, 0, mapOf("wrong_world" to 1)))
        val key = Key(runtime.settings.id, runtime.state.sequence, placementSequence, surface, burrowId, greenhouse)
        previews[key]?.let { return it }
        // Old revisions cannot be selected again, and must not retain every historical maze.
        previews.keys.removeIf { it.zone == key.zone && (it.sequence != key.sequence || it.placement != key.placement) }
        val result = CompletableFuture<FarmMoleBurrowPreview>()
        previews[key] = result
        val token = tasks.lifecycleToken()
        val source = epoch
        val settings = runtime.settings.moleBurrow
        val sourcePlacement = runtime.state.placementSequence
        val minimum = world.minHeight
        val maximum = world.maxHeight
        val surfaceY = floor(surface.y).toInt()
        val footprintY = surfaceY.coerceIn(runtime.region.bounds.minY, runtime.region.bounds.maxY)
        fun current() = source == epoch && runtime.state.sequence == key.sequence &&
            runtime.state.placementSequence == sourcePlacement && runtime.settings.moleBurrow === settings && Bukkit.getWorld(worldName) === world
        val palette = linkedMapOf<String, MolePaletteEntry>()
        val materials = (if (greenhouse) FarmHellRiftRoom.materials.map { it.name }.toSet()
            else FarmMoleBurrowDecorationPlanner.materials + setOf("AIR", "BARRIER", "LIGHT")).iterator()
        val rejectionTotals = linkedMapOf<String, Int>()
        fun reject(reason: String, count: Int = 1) { rejectionTotals[reason] = rejectionTotals.getOrDefault(reason, 0) + count }
        val maximumAttempts = if (greenhouse) 1 else minOf(64, (settings.maxDepth - settings.minDepth + 1) * 4)
        fun finish(scene: FarmMoleBurrowScene?, attempts: Int) = FarmMoleBurrowPreview(scene, attempts, rejectionTotals.toMap())
        fun attempt(index: Int): CompletableFuture<FarmMoleBurrowPreview> {
            if (index >= maximumAttempts) return CompletableFuture.completedFuture(finish(null, index))
            return tasks.compute {
                if (!greenhouse) moleGeometry(surface, settings, placementSequence, burrowId, index, palette)
                else {
                    val x = floor(surface.x).toInt()
                    val z = floor(surface.z).toInt()
                    val span = settings.maxDepth - settings.minDepth + 1
                    require(span > 0) { "invalid_depth_range" }
                    val depth = settings.minDepth + Math.floorMod(WorksiteDeterministicSeed.gridScore(placementSequence, x, z).toInt(), span)
                    val y = surfaceY - depth
                    if (y - 1 <= minimum || y + FarmHellRiftRoom.CEILING >= maximum || y + FarmHellRiftRoom.CEILING >= surfaceY) null
                    else MoleGeometry(FarmHellRiftRoom.blocks(x, y, z) { palette.getValue(it.name).data }, emptySet(),
                        Triple(x, y, z), Triple(x, y, z + 1))
                }
            }.thenCompose { geometry -> tasks.callSync(token) {
                check(current()) { "Farm maze planning superseded" }
                if (geometry == null || geometry.blocks.size > FarmMoleBurrowJournalCodec.MAX_SCENE_RECORDS) {
                    reject(if (geometry == null) "world_height" else "scene_too_large")
                    return@callSync attempt(index + 1)
                }
                val chunks = geometry.chunks.entries.iterator()
                val captured = mutableListOf<ChunkCapture>()
                var unloaded = false
                tasks.capture(token, ::current) {
                    if (chunks.hasNext()) {
                        val (position, positions) = chunks.next()
                        val (x, z) = position
                        if (!world.isChunkLoaded(x, z)) unloaded = true
                        else {
                            val chunk = world.getChunkAt(x, z)
                            captured += ChunkCapture(x, z, chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY),
                                if (greenhouse) codec.captureForeignJournalCheck(chunk, positions) else { { false } })
                        }
                    }
                    unloaded || !chunks.hasNext()
                }.thenCompose { tasks.compute {
                    when {
                        unloaded -> "unloaded_chunk"
                        captured.any { c -> c.raw?.let { runCatching { FarmMoleBurrowJournalCodec.decode(it, worldName, c.x, c.z, minimum, maximum) }.isFailure } == true } -> "journal_unreadable"
                        !greenhouse && captured.any { c -> c.raw?.let { FarmMoleBurrowJournalCodec.decode(it, worldName, c.x, c.z, minimum, maximum).isNotEmpty() } == true } -> "journal_occupied"
                        greenhouse && captured.any { it.foreign() } -> "journal_collision"
                        else -> null
                    }
                } }.thenCompose { journalFailure -> tasks.callSync(token) {
                    if (journalFailure != null) {
                        reject(journalFailure)
                        return@callSync attempt(index + 1)
                    }
                    val blocks = geometry.blocks.entries.iterator()
                    val records = mutableListOf<FarmMoleBurrowJournalRecord>()
                    val failures = linkedMapOf<String, Int>()
                    fun fail(reason: String) { failures[reason] = failures.getOrDefault(reason, 0) + 1 }
                    tasks.capture(token, ::current) {
                        if (blocks.hasNext()) {
                            val (position, active) = blocks.next()
                            val (x, y, z) = position
                            check(world.isChunkLoaded(x shr 4, z shr 4)) { "Farm maze chunk unloaded during capture" }
                            if (y <= minimum + 1 || y >= maximum - 1) fail("world_height")
                            else {
                                val block = world.getBlockAt(x, y, z)
                                if (!runtime.region.contains(Location(world, x + .5, footprintY.toDouble(), z + .5))) fail("outside_farm_footprint")
                                val replaceable = if (!block.isLiquid && !block.type.hasGravity()) true
                                    else if (position !in geometry.shaft || y !in surfaceY - 1..surfaceY) false
                                    else if (y == surfaceY) block.type.isAir || FarmBlockPolicy.isOpenBedContent(block.type, runtime.settings.crops)
                                    else FarmBlockPolicy.isSelectableBed(block.type, block.getRelative(0, 1, 0).type, runtime.settings.crops)
                                if (!replaceable) fail("material:${block.type.name}")
                                records += FarmMoleBurrowJournalRecord(worldName, key.zone, key.sequence, burrowId,
                                    x, y, z, block.blockData.asString, active.first, active.second, geometry.blocks.size)
                            }
                        }
                        !blocks.hasNext()
                    }.thenCompose {
                        if (failures.isNotEmpty()) {
                            failures.forEach { (reason, count) -> reject(reason, count) }
                            attempt(index + 1)
                        } else tasks.compute {
                            val frozen = records.toList()
                            frozen to moleSceneIndex(frozen, geometry.start.second, geometry.lair.first, geometry.lair.third)
                        }.thenCompose { (frozen, sceneIndex) -> tasks.callSync(token) {
                            check(current())
                            finish(FarmMoleBurrowScene(world, key.zone, key.sequence, burrowId,
                                Location(world, surface.x, surface.y, surface.z),
                                Location(world, geometry.start.first + .5, geometry.start.second.toDouble(), geometry.start.third + .5),
                                Location(world, geometry.lair.first + .5, geometry.lair.second.toDouble(), geometry.lair.third + .5),
                                frozen, sceneIndex), index + 1)
                        } }
                    }
                }.thenCompose { it } }
            }.thenCompose { it } }
        }
        tasks.capture(token, ::current) {
            if (materials.hasNext()) {
                val name = materials.next()
                val material = Material.valueOf(name)
                val data = material.createBlockData().also { if (name == "LIGHT") (it as Levelled).level = settings.lightLevel }
                palette[name] = MolePaletteEntry(data.asString, material.isSolid)
            }
            !materials.hasNext()
        }.thenCompose { attempt(0) }.whenComplete { preview, failure -> tasks.callSync(token) {
            if (failure == null) result.complete(preview) else { previews.remove(key, result); result.completeExceptionally(failure) }
        }.exceptionally { result.completeExceptionally(it); null } }
        return result
    }
}

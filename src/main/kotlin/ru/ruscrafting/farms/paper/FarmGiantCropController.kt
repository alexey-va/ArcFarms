package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.FarmGiantCropBlueprint
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import java.util.ArrayDeque
import java.util.logging.Level

internal data class FarmGiantCropSync(
    val totalBlocks: Int,
    val brokenBlocks: Int,
)

/**
 * Owns physical giant-crop blocks and a chunk-PDC recovery journal. Every
 * original block state is durable before the sculpture mutates the world.
 */
internal class FarmGiantCropController(private val plugin: Plugin) {
    private data class SceneKey(val world: String, val zoneId: String, val sequence: Long)

    private val journalKey = NamespacedKey(plugin, "farm_giant_crop_blocks_v1")
    private val restoreQueue = ArrayDeque<FarmGiantCropJournalRecord>()
    private val queuedRestores = linkedSetOf<SceneKey>()

    fun canPlace(
        region: ActivityRegion,
        anchor: Block,
        crop: String,
        configuredCrops: Set<String>,
    ): Boolean = placementIssue(region, anchor, crop, configuredCrops) == null

    fun placementIssue(
        region: ActivityRegion,
        anchor: Block,
        crop: String,
        configuredCrops: Set<String>,
    ): String? {
        if (!FarmGiantCropBlueprint.supports(crop)) return "unsupported_crop"
        if (anchor.world !== region.world) return "wrong_world"
        val targets = targetBlocks(anchor, crop)
        if (targets.any { !it.world.isChunkLoaded(it.x shr 4, it.z shr 4) }) return "unloaded_chunk"
        if (targets.any { !region.contains(it.location) }) return "outside_region"
        val covered = targets.groupBy { it.x to it.z }.values.any { column ->
            !FarmSurfacePolicy.isOpenAbove(column.maxBy(Block::getY))
        }
        if (covered) return "covered_target"
        if (targets.map(Block::getChunk).distinctBy { it.x to it.z }.any { read(it)?.isNotEmpty() != false }) {
            return "existing_journal"
        }
        val blocked = targets.firstOrNull { block ->
            !block.type.isAir && !block.isReplaceable && block.type.name !in configuredCrops
        }
        return blocked?.let { "blocked_by_${it.type.name.lowercase()}" }
    }

    fun ensure(
        zoneId: String,
        sequence: Long,
        region: ActivityRegion,
        anchor: Block,
        crop: String,
        configuredCrops: Set<String>,
        persistedProgress: Int,
    ): FarmGiantCropSync? {
        require(zoneId.matches(ZONE_ID)) { "Invalid giant crop zone id" }
        require(sequence >= 0) { "Invalid giant crop sequence" }
        val targets = targetBlocks(anchor, crop)
        val targetPositions = targets.mapTo(linkedSetOf()) { Triple(it.x, it.y, it.z) }
        val chunks = targets.map(Block::getChunk).distinctBy { it.x to it.z }
        val stored = chunks.flatMap { chunk -> read(chunk) ?: return null }
            .filter { it.zoneId == zoneId && it.sequence == sequence && Triple(it.x, it.y, it.z) in targetPositions }
        if (stored.size == targets.size && stored.map { Triple(it.x, it.y, it.z) }.toSet() == targetPositions) {
            if (!stored.all(::reconcile)) return null
            return FarmGiantCropSync(stored.size, stored.count(FarmGiantCropJournalRecord::broken))
        }
        if (stored.isNotEmpty()) {
            restoreImmediately(stored)
            return null
        }
        if (!canPlace(region, anchor, crop, configuredCrops)) return null

        val brokenCount = persistedProgress.coerceIn(0, targets.size)
        val records = FarmGiantCropBlueprint.voxels(crop).zip(targets).mapIndexed { index, (voxel, block) ->
            FarmGiantCropJournalRecord(
                world = block.world.name,
                zoneId = zoneId,
                sequence = sequence,
                x = block.x,
                y = block.y,
                z = block.z,
                originalData = block.blockData.asString,
                incidentData = incidentBlockData(voxel.material),
                broken = index < brokenCount,
            )
        }
        records.groupBy { it.chunkKey() }.forEach { (chunkKey, additions) ->
            val chunk = requireNotNull(chunkKey.chunk())
            val current = read(chunk) ?: return null
            if (current.any { existing ->
                    additions.any { added -> existing.x == added.x && existing.y == added.y && existing.z == added.z }
                }
            ) return null
            write(chunk, current + additions)
        }
        return if (records.all(::reconcile)) FarmGiantCropSync(records.size, brokenCount) else null
    }

    fun owns(block: Block, zoneId: String, sequence: Long): Boolean = read(block.chunk)?.any { record ->
        record.zoneId == zoneId && record.sequence == sequence && !record.broken &&
            record.x == block.x && record.y == block.y && record.z == block.z
    } == true

    /** Journals the hit before replacing the owned block with air. */
    fun breakBlock(block: Block, zoneId: String, sequence: Long): Boolean {
        val records = read(block.chunk) ?: return false
        val index = records.indexOfFirst { record ->
            record.zoneId == zoneId && record.sequence == sequence && !record.broken &&
                record.x == block.x && record.y == block.y && record.z == block.z
        }
        if (index < 0) return false
        val updated = records.toMutableList().also { it[index] = it[index].copy(broken = true) }
        write(block.chunk, updated)
        block.setType(Material.AIR, false)
        return true
    }

    fun beginRestore(world: org.bukkit.World, zoneId: String, sequence: Long) {
        val key = SceneKey(world.name, zoneId, sequence)
        if (!queuedRestores.add(key)) return
        world.loadedChunks.asSequence().flatMap { chunk -> read(chunk).orEmpty().asSequence() }
            .filter { it.zoneId == zoneId && it.sequence == sequence }
            .forEach(restoreQueue::addLast)
    }

    /** Immediate bounded admin recovery for all loaded journals owned by one farm. */
    fun restoreZoneNow(world: org.bukkit.World, zoneId: String): Set<Chunk> {
        require(zoneId.matches(ZONE_ID)) { "Invalid giant crop zone id" }
        val touched = linkedSetOf<Chunk>()
        world.loadedChunks.forEach { chunk ->
            val current = read(chunk) ?: return@forEach
            val owned = current.filter { it.zoneId == zoneId }
            if (owned.isEmpty()) return@forEach
            val restored = owned.filter(::restore).toSet()
            write(chunk, current - restored)
            if (restored.isNotEmpty()) touched += chunk
        }
        restoreQueue.removeIf { it.world == world.name && it.zoneId == zoneId }
        queuedRestores.removeIf { it.world == world.name && it.zoneId == zoneId }
        return touched
    }

    fun processRestores(limit: Int): Set<Chunk> {
        require(limit >= 1) { "Giant crop restore limit must be positive" }
        val selected = buildList {
            repeat(minOf(limit, restoreQueue.size)) { add(restoreQueue.removeFirst()) }
        }
        val touched = linkedSetOf<Chunk>()
        selected.groupBy { record ->
            record.chunkKey() to SceneKey(record.world, record.zoneId, record.sequence)
        }.forEach { (group, pending) ->
            val chunk = group.first.chunk() ?: return@forEach
            val current = read(chunk) ?: return@forEach
            val pendingPositions = pending.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
            val restored = current.filter { record ->
                record.zoneId == pending.first().zoneId && record.sequence == pending.first().sequence &&
                    Triple(record.x, record.y, record.z) in pendingPositions
            }.filter(::restore).toSet()
            write(chunk, current - restored)
            if (restored.isNotEmpty()) touched += chunk
        }
        if (restoreQueue.isEmpty()) queuedRestores.clear()
        return touched
    }

    fun onChunkLoad(chunk: Chunk, active: (String, Long) -> Boolean) {
        val records = read(chunk) ?: return
        val stale = records.filterNot { active(it.zoneId, it.sequence) }
        records.filter { active(it.zoneId, it.sequence) }.forEach(::reconcile)
        if (stale.isNotEmpty()) {
            val restored = stale.filter(::restore).toSet()
            write(chunk, records - restored)
        }
    }

    fun restoreLoadedAll(reason: String) {
        var restored = 0
        Bukkit.getWorlds().forEach { world ->
            world.loadedChunks.forEach { chunk ->
                val records = read(chunk) ?: return@forEach
                val repaired = records.filter(::restore).toSet()
                if (repaired.isNotEmpty()) {
                    write(chunk, records - repaired)
                    restored += repaired.size
                }
            }
        }
        restoreQueue.clear()
        queuedRestores.clear()
        if (restored > 0) plugin.logger.info("Restored $restored giant farm blocks during $reason")
    }

    private fun restoreImmediately(records: List<FarmGiantCropJournalRecord>) {
        records.groupBy { it.chunkKey() }.forEach { (chunkKey, sceneRecords) ->
            val chunk = chunkKey.chunk() ?: return@forEach
            val current = read(chunk) ?: return@forEach
            val restored = sceneRecords.filter(::restore)
            val positions = restored.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
            write(chunk, current.filterNot { Triple(it.x, it.y, it.z) in positions })
        }
    }

    private fun reconcile(record: FarmGiantCropJournalRecord): Boolean {
        val block = record.block() ?: return false
        if (record.broken) {
            if (!block.type.isAir) block.setType(Material.AIR, false)
        } else {
            val data = blockData(record, record.incidentData, "incident") ?: return false
            if (!block.blockData.matches(data)) block.setBlockData(data, false)
        }
        return true
    }

    private fun restore(record: FarmGiantCropJournalRecord): Boolean {
        val block = record.block() ?: return false
        val data = blockData(record, record.originalData, "original") ?: return false
        block.setBlockData(data, false)
        return true
    }

    private fun blockData(record: FarmGiantCropJournalRecord, raw: String, kind: String) =
        runCatching { Bukkit.createBlockData(raw) }.getOrElse { failure ->
            plugin.logger.log(
                Level.SEVERE,
                "Could not decode $kind giant crop BlockData at ${record.world}:${record.x},${record.y},${record.z}; journal retained",
                failure,
            )
            null
        }

    private fun targetBlocks(anchor: Block, crop: String): List<Block> = FarmGiantCropBlueprint.voxels(crop).map { voxel ->
        anchor.world.getBlockAt(anchor.x + voxel.dx, anchor.y + voxel.dy, anchor.z + voxel.dz)
    }

    private fun incidentBlockData(materialName: String): String =
        MaterialRules.material(materialName).createBlockData().also { data ->
            if (data is Leaves) data.setPersistent(true)
        }.asString

    private fun read(chunk: Chunk): List<FarmGiantCropJournalRecord>? {
        val raw = chunk.persistentDataContainer.get(journalKey, PersistentDataType.BYTE_ARRAY) ?: return emptyList()
        return runCatching {
            FarmGiantCropJournalCodec.decode(
                raw,
                chunk.world.name,
                chunk.x,
                chunk.z,
                chunk.world.minHeight,
                chunk.world.maxHeight,
            )
        }.getOrElse { failure ->
            plugin.logger.log(Level.SEVERE, "Could not decode giant crop journal in ${chunk.world.name}:${chunk.x},${chunk.z}", failure)
            null
        }
    }

    private fun write(chunk: Chunk, records: List<FarmGiantCropJournalRecord>) {
        if (records.isEmpty()) {
            chunk.persistentDataContainer.remove(journalKey)
            return
        }
        val raw = FarmGiantCropJournalCodec.encode(
            records,
            chunk.world.name,
            chunk.x,
            chunk.z,
            chunk.world.minHeight,
            chunk.world.maxHeight,
        )
        chunk.persistentDataContainer.set(journalKey, PersistentDataType.BYTE_ARRAY, raw)
    }

    private fun FarmGiantCropJournalRecord.block(): Block? {
        val world = Bukkit.getWorld(chunkKey().world) ?: return null
        if (!world.isChunkLoaded(x shr 4, z shr 4)) return null
        return world.getBlockAt(x, y, z)
    }

    private fun FarmGiantCropJournalRecord.chunkKey(): ChunkKey = ChunkKey(world, x shr 4, z shr 4)

    private data class ChunkKey(val world: String, val x: Int, val z: Int) {
        fun chunk(): Chunk? = Bukkit.getWorld(world)?.takeIf { it.isChunkLoaded(x, z) }?.getChunkAt(x, z)
    }

    private companion object {
        val ZONE_ID = Regex("[a-z0-9_-]{1,48}")
    }
}

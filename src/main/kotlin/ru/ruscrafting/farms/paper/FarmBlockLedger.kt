package ru.ruscrafting.farms.paper

import com.google.gson.Gson
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.FarmPlotPosition
import java.util.LinkedHashMap
import java.util.logging.Level

internal data class ManagedFarmBlockRecord(
    val zoneId: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalSoilData: String,
    val originalCropData: String?,
    val activeCropData: String?,
    val indexed: Boolean = false,
    val temporaryMutation: String? = null,
    val temporaryRestoreAt: Long? = null,
)

internal data class ManagedFarmFixedCropRecord(
    val zoneId: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalBlockData: String,
    val restoreAt: Long?,
) {
    fun position(world: String): FarmFixedCropPosition = FarmFixedCropPosition(world, x, y, z)
}

internal data class ManagedFarmOrchardLeafRecord(
    val zoneId: String,
    val x: Int,
    val y: Int,
    val z: Int,
)

private class ManagedFarmBlockSnapshot(source: List<ManagedFarmBlockRecord>) {
    val records: List<ManagedFarmBlockRecord> = source.toList()
    private val sortedRecords = records.sortedBy { coordinateKey(it.x, it.y, it.z) }

    fun record(x: Int, y: Int, z: Int): ManagedFarmBlockRecord? {
        val expected = coordinateKey(x, y, z)
        var low = 0
        var high = sortedRecords.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val candidate = sortedRecords[middle]
            val comparison = coordinateKey(candidate.x, candidate.y, candidate.z).compareTo(expected)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return candidate
            }
        }
        return null
    }

    companion object {
        fun coordinateKey(x: Int, y: Int, z: Int): Long =
            (y.toLong() shl 8) or ((x and 15).toLong() shl 4) or (z and 15).toLong()
    }
}

/**
 * Ordinary blocks cannot carry PDC. Paper chunks can, so every entry is keyed by
 * exact block coordinates inside the owning chunk and survives an abrupt stop.
 */
internal class FarmBlockLedger(plugin: Plugin) {
    private val gson = Gson()
    private val key = NamespacedKey(plugin, "farm_managed_blocks_v1")
    private val fixedCropKey = NamespacedKey(plugin, "farm_fixed_crops_v1")
    private val orchardLeafKey = NamespacedKey(plugin, "farm_orchard_leaves_v1")
    private val logger = plugin.logger
    private val blockCache = boundedBlockChunkCache()
    private val fixedCropCache = boundedChunkCache<ManagedFarmFixedCropRecord>()
    private val orchardCache = boundedChunkCache<ManagedFarmOrchardLeafRecord>()
    /** Main-thread scratch retained across the compatibility bulk lookup. */
    private val lookupSnapshots = HashMap<Chunk, ManagedFarmBlockSnapshot>()

    internal inner class RecordLookup {
        private var world: org.bukkit.World? = null
        private var chunkX = 0
        private var chunkZ = 0
        private var snapshot: ManagedFarmBlockSnapshot? = null

        fun reset() {
            world = null
            snapshot = null
        }

        fun record(position: FarmPlotPosition, soil: Block): ManagedFarmBlockRecord? {
            val currentWorld = soil.world
            val currentChunkX = position.x shr 4
            val currentChunkZ = position.z shr 4
            if (world !== currentWorld || chunkX != currentChunkX || chunkZ != currentChunkZ) {
                world = currentWorld
                chunkX = currentChunkX
                chunkZ = currentChunkZ
                snapshot = blockSnapshot(soil.chunk)
            }
            return snapshot?.record(position.x, position.y, position.z)
        }
    }

    fun recordLookup(): RecordLookup = RecordLookup()

    fun capture(soil: Block, zoneId: String): ManagedFarmBlockRecord {
        val existing = record(soil)
        if (existing != null) return existing
        val created = createRecord(soil, zoneId)
        update(soil, created)
        return created
    }

    /** Captures a world-mutation batch with one PDC decode/write per affected chunk. */
    fun captureAll(soils: Collection<Block>, zoneId: String) {
        if (soils.isEmpty()) return
        soils.groupBy(Block::getChunk).forEach { (chunk, blocks) ->
            val records = blockRecords(chunk).toMutableList()
            val coordinates = records.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
            var changed = false
            blocks.forEach { soil ->
                require(soil.chunk == chunk) { "Farm capture batch crossed chunks" }
                if (coordinates.add(Triple(soil.x, soil.y, soil.z))) {
                    records += createRecord(soil, zoneId)
                    changed = true
                }
            }
            if (changed) write(chunk, records)
        }
    }

    fun captureActiveCrop(soil: Block, zoneId: String): ManagedFarmBlockRecord {
        val current = capture(soil, zoneId)
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        val cropData = crop.blockData.takeUnless { crop.type.isAir || crop.type == Material.WATER }?.asString
        return current.copy(activeCropData = cropData).also { update(soil, it) }
    }

    /** Captures original and active crop state with at most two PDC writes per affected chunk. */
    fun captureActiveCrops(soils: Collection<Block>, zoneId: String) {
        if (soils.isEmpty()) return
        captureAll(soils, zoneId)
        updateActiveCrops(soils)
    }

    /** Updates active crop snapshots with one PDC decode/write per affected chunk. Blocks must be captured first. */
    fun updateActiveCrops(soils: Collection<Block>) {
        if (soils.isEmpty()) return
        soils.groupBy(Block::getChunk).forEach { (chunk, rawBlocks) ->
            val blocks = rawBlocks.distinctBy { Triple(it.x, it.y, it.z) }
            val records = blockRecords(chunk).toMutableList()
            val indices = records.withIndex().associate { Triple(it.value.x, it.value.y, it.value.z) to it.index }
            var changed = false
            blocks.forEach { soil ->
                require(soil.chunk == chunk) { "Farm active-crop batch crossed chunks" }
                val index = requireNotNull(indices[Triple(soil.x, soil.y, soil.z)]) {
                    "Farm active-crop batch contains an uncaptured block"
                }
                val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
                val cropData = crop.blockData.takeUnless { crop.type.isAir || crop.type == Material.WATER }?.asString
                if (records[index].activeCropData != cropData) {
                    records[index] = records[index].copy(activeCropData = cropData)
                    changed = true
                }
            }
            if (changed) write(chunk, records)
        }
    }

    fun captureActiveCropIfPresent(soil: Block, zoneId: String): ManagedFarmBlockRecord {
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        if (crop.type.isAir || crop.type == Material.WATER) return record(soil) ?: capture(soil, zoneId)
        return captureActiveCrop(soil, zoneId)
    }

    fun record(soil: Block): ManagedFarmBlockRecord? = blockSnapshot(soil.chunk).record(soil.x, soil.y, soil.z)

    /** Reads each affected chunk payload once for a maintenance pass. */
    fun records(soils: Collection<Block>): Map<FarmPlotPosition, ManagedFarmBlockRecord> {
        if (soils.isEmpty()) return emptyMap()
        lookupSnapshots.clear()
        val result = HashMap<FarmPlotPosition, ManagedFarmBlockRecord>(soils.size)
        soils.forEach { soil ->
            val chunk = soil.chunk
            val snapshot = lookupSnapshots.getOrPut(chunk) { blockSnapshot(chunk) }
            snapshot.record(soil.x, soil.y, soil.z)?.let { record ->
                result[FarmPlotPosition(soil.world.name, soil.x, soil.y, soil.z)] = record
            }
        }
        return result
    }

    fun restoreActiveCrop(soil: Block): Boolean {
        val record = record(soil) ?: return false
        restoreActiveCrop(soil, record)
        return true
    }

    fun restoreActiveCrop(soil: Block, record: ManagedFarmBlockRecord) {
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        val data = record.activeCropData
        if (data == null) crop.setType(Material.AIR, false)
        else crop.setBlockData(Bukkit.createBlockData(data), false)
    }

    /** Journals a short-lived terrain effect before its authoritative blocks are changed. */
    fun beginTemporaryRemoval(
        soils: Collection<Block>,
        zoneId: String,
        owner: String,
        restoreAt: Long,
    ) {
        require(owner.matches(Regex("[a-z0-9:_-]{1,96}"))) { "Invalid farm temporary mutation owner" }
        require(restoreAt >= 0L) { "Farm temporary restoration time must not be negative" }
        val distinct = soils.distinctBy { FarmPlotPosition(it.world.name, it.x, it.y, it.z) }
        val fresh = distinct.filter { record(it)?.temporaryMutation == null }
        captureActiveCrops(fresh, zoneId)
        distinct.groupBy(Block::getChunk).forEach { (chunk, blocks) ->
            val records = blockRecords(chunk).toMutableList()
            val indices = records.withIndex().associate { Triple(it.value.x, it.value.y, it.value.z) to it.index }
            var changed = false
            blocks.forEach { soil ->
                val index = requireNotNull(indices[Triple(soil.x, soil.y, soil.z)]) {
                    "Farm temporary removal contains an uncaptured block"
                }
                val current = records[index]
                val deadline = maxOf(current.temporaryRestoreAt ?: restoreAt, restoreAt)
                if (current.temporaryMutation != owner || current.temporaryRestoreAt != deadline) {
                    records[index] = current.copy(temporaryMutation = owner, temporaryRestoreAt = deadline)
                    changed = true
                }
            }
            if (changed) write(chunk, records)
        }
    }

    /** Restores the active crop snapshot and retires only the matching temporary journal entry. */
    fun restoreTemporaryRemovals(soils: Collection<Block>, owner: String? = null): Set<Block> = buildSet {
        soils.distinctBy { FarmPlotPosition(it.world.name, it.x, it.y, it.z) }
            .groupBy(Block::getChunk)
            .forEach { (chunk, blocks) ->
                val records = blockRecords(chunk).toMutableList()
                val indices = records.withIndex().associate { Triple(it.value.x, it.value.y, it.value.z) to it.index }
                val removals = hashSetOf<Int>()
                var changed = false
                blocks.forEach { soil ->
                    val index = indices[Triple(soil.x, soil.y, soil.z)] ?: return@forEach
                    val record = records[index]
                    if (record.temporaryMutation == null || owner != null && record.temporaryMutation != owner) return@forEach
                    soil.setBlockData(Bukkit.createBlockData(record.originalSoilData), false)
                    restoreActiveCrop(soil, record)
                    add(soil)
                    if (record.indexed) {
                        records[index] = record.copy(temporaryMutation = null, temporaryRestoreAt = null)
                    } else removals += index
                    changed = true
                }
                removals.sortedDescending().forEach(records::removeAt)
                if (changed) write(chunk, records)
            }
    }

    fun restoreOriginal(soil: Block, clear: Boolean = true): Boolean =
        restoreOriginals(listOf(soil), clear).contains(soil)

    /**
     * Restores a bounded batch with one ledger write per affected chunk. Rewriting
     * the complete chunk JSON once per block caused visible end-of-event spikes on
     * large mechanized fields.
     */
    fun restoreOriginals(soils: Collection<Block>, clear: Boolean = true): Set<Block> = buildSet {
        soils.distinctBy { FarmPlotPosition(it.world.name, it.x, it.y, it.z) }
            .groupBy(Block::getChunk)
            .forEach { (chunk, chunkSoils) ->
                val records = blockRecords(chunk).toMutableList()
                val indices = records.withIndex().associate { Triple(it.value.x, it.value.y, it.value.z) to it.index }
                val removals = hashSetOf<Int>()
                var ledgerChanged = false
                chunkSoils.forEach { soil ->
                    require(soil.chunk == chunk) { "Farm restore batch crossed chunks" }
                    val index = indices[Triple(soil.x, soil.y, soil.z)] ?: return@forEach
                    val record = records[index]
                    soil.setBlockData(Bukkit.createBlockData(record.originalSoilData), false)
                    val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
                    val cropData = record.originalCropData
                    if (cropData == null) crop.setType(Material.AIR, false)
                    else crop.setBlockData(Bukkit.createBlockData(cropData), false)
                    add(soil)
                    if (clear) {
                        removals += index
                        ledgerChanged = true
                    } else if (record.activeCropData != record.originalCropData) {
                        // Indexed beds survive an order and are maintained from activeCropData.
                        // Leaving a temporary preparation crop here would make the maintenance
                        // pass overwrite the restored farm layout one second later.
                        records[index] = record.copy(activeCropData = record.originalCropData)
                        ledgerChanged = true
                    }
                }
                if (removals.isNotEmpty()) {
                    removals.sortedDescending().forEach(records::removeAt)
                }
                if (ledgerChanged) write(chunk, records)
            }
    }

    fun remove(soil: Block): Boolean {
        // Unmanaging a bed must not discard the only recovery record for an active terrain effect.
        if (record(soil)?.temporaryMutation != null && soil !in restoreTemporaryRemovals(listOf(soil))) return false
        val records = blockRecords(soil.chunk).toMutableList()
        val removed = records.removeIf { it.x == soil.x && it.y == soil.y && it.z == soil.z }
        if (removed) write(soil.chunk, records)
        return removed
    }

    fun removeTransient(soil: Block): Boolean {
        val records = blockRecords(soil.chunk).toMutableList()
        val removed = records.removeIf {
            it.x == soil.x && it.y == soil.y && it.z == soil.z && !it.indexed
        }
        if (removed) write(soil.chunk, records)
        return removed
    }

    fun captureFixedCrop(block: Block, zoneId: String): ManagedFarmFixedCropRecord {
        require(MaterialRules.isFixedBlockCrop(block.type)) { "${block.type} is not a fixed farm crop" }
        val existing = fixedCropRecord(block)
        if (existing != null) return existing
        val created = ManagedFarmFixedCropRecord(
            zoneId = zoneId,
            x = block.x,
            y = block.y,
            z = block.z,
            originalBlockData = block.blockData.asString,
            restoreAt = null,
        )
        updateFixedCrop(block, created)
        return created
    }

    fun scheduleExistingFixedCropRestore(block: Block, restoreAt: Long): ManagedFarmFixedCropRecord? {
        require(restoreAt >= 0L) { "Fixed crop restore time must not be negative" }
        val record = fixedCropRecord(block)?.copy(restoreAt = restoreAt) ?: return null
        updateFixedCrop(block, record)
        return record
    }

    fun reconcileFixedCrop(
        block: Block,
        zoneId: String,
        originalBlockData: String,
        restoreAt: Long?,
    ): ManagedFarmFixedCropRecord {
        val record = ManagedFarmFixedCropRecord(
            zoneId = zoneId,
            x = block.x,
            y = block.y,
            z = block.z,
            originalBlockData = originalBlockData,
            restoreAt = restoreAt,
        )
        updateFixedCrop(block, record)
        return record
    }

    fun fixedCropRecord(block: Block): ManagedFarmFixedCropRecord? = fixedCropRecords(block.chunk).firstOrNull {
        it.x == block.x && it.y == block.y && it.z == block.z
    }

    fun fixedCropRecords(chunk: Chunk): List<ManagedFarmFixedCropRecord> {
        val cacheKey = chunk.cacheKey()
        fixedCropCache[cacheKey]?.let { return it }
        val raw = chunk.persistentDataContainer.get(fixedCropKey, PersistentDataType.STRING) ?: return emptyList()
        return runCatching {
            require(raw.length <= 2_000_000) { "Fixed farm crop payload is unbounded" }
            gson.fromJson(raw, Array<ManagedFarmFixedCropRecord>::class.java)?.toList().orEmpty().also { records ->
                require(records.size <= MAX_RECORDS_PER_CHUNK) { "Fixed farm crop list is unbounded" }
                require(records.map { Triple(it.x, it.y, it.z) }.distinct().size == records.size) {
                    "Fixed farm crop coordinates are duplicated"
                }
                require(records.all { record ->
                    record.zoneId.matches(Regex("[a-z0-9_-]{1,48}")) &&
                        (record.x shr 4) == chunk.x && (record.z shr 4) == chunk.z &&
                        record.y in chunk.world.minHeight until chunk.world.maxHeight &&
                        record.originalBlockData.length in 1..512 &&
                        (record.restoreAt == null || record.restoreAt >= 0L)
                }) { "Fixed farm crop record is invalid" }
            }.also { fixedCropCache[cacheKey] = it }
        }.getOrElse { failure ->
            logger.log(
                Level.SEVERE,
                "Could not decode fixed farm crops in ${chunk.world.name}:${chunk.x},${chunk.z}",
                failure,
            )
            emptyList()
        }
    }

    fun removeFixedCrop(block: Block): Boolean {
        val records = fixedCropRecords(block.chunk).toMutableList()
        val removed = records.removeIf { it.x == block.x && it.y == block.y && it.z == block.z }
        if (removed) writeFixedCrops(block.chunk, records)
        return removed
    }

    fun orchardLeafRecords(chunk: Chunk): List<ManagedFarmOrchardLeafRecord> {
        val cacheKey = chunk.cacheKey()
        orchardCache[cacheKey]?.let { return it }
        val raw = chunk.persistentDataContainer.get(orchardLeafKey, PersistentDataType.STRING) ?: return emptyList()
        return runCatching {
            require(raw.length <= 2_000_000) { "Farm orchard leaf payload is unbounded" }
            gson.fromJson(raw, Array<ManagedFarmOrchardLeafRecord>::class.java)?.toList().orEmpty().also { records ->
                require(records.size <= MAX_RECORDS_PER_CHUNK) { "Farm orchard leaf list is unbounded" }
                require(records.map { Triple(it.x, it.y, it.z) }.distinct().size == records.size) {
                    "Farm orchard leaf coordinates are duplicated"
                }
                require(records.all { record ->
                    record.zoneId.matches(Regex("[a-z0-9_-]{1,48}")) &&
                        (record.x shr 4) == chunk.x && (record.z shr 4) == chunk.z &&
                        record.y in chunk.world.minHeight until chunk.world.maxHeight
                }) { "Farm orchard leaf record is invalid" }
            }.also { orchardCache[cacheKey] = it }
        }.getOrElse { failure ->
            logger.log(
                Level.SEVERE,
                "Could not decode farm orchard leaves in ${chunk.world.name}:${chunk.x},${chunk.z}",
                failure,
            )
            emptyList()
        }
    }

    fun orchardLeafRecord(block: Block): ManagedFarmOrchardLeafRecord? = orchardLeafRecords(block.chunk).firstOrNull {
        it.x == block.x && it.y == block.y && it.z == block.z
    }

    fun removeOrchardLeaf(block: Block): Boolean {
        val records = orchardLeafRecords(block.chunk).toMutableList()
        val removed = records.removeIf { it.x == block.x && it.y == block.y && it.z == block.z }
        if (removed) writeOrchardLeaves(block.chunk, records)
        return removed
    }

    fun replaceZoneIndex(
        chunk: Chunk,
        zoneId: String,
        beds: Collection<Block>,
        fixedCrops: Collection<Block>,
        orchardLeaves: Collection<Block>,
    ) {
        require(zoneId.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid farm zone id" }
        require(fixedCropRecords(chunk).none { it.zoneId == zoneId && it.restoreAt != null }) {
            "Farm zone $zoneId still has pending fixed crop restoration in ${chunk.x},${chunk.z}"
        }
        beds.forEach { soil ->
            require(soil.chunk == chunk && soil.type == Material.FARMLAND) {
                "Farm bed replacement must be validated by the registry"
            }
        }
        fixedCrops.forEach { block ->
            require(block.chunk == chunk && MaterialRules.isFixedBlockCrop(block.type)) {
                "Fixed farm crop replacement must be validated by the registry"
            }
        }
        orchardLeaves.forEach { block ->
            require(block.chunk == chunk && MaterialRules.isLeaf(block.type)) {
                "Farm orchard replacement must be validated by the registry"
            }
        }

        val bedCoordinates = beds.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
        val blockRecords = blockRecords(chunk).filterNot {
            it.zoneId == zoneId || Triple(it.x, it.y, it.z) in bedCoordinates
        }.toMutableList()
        beds.mapTo(blockRecords) { soil ->
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            val cropData = crop.blockData.takeUnless { crop.type.isAir }?.asString
            ManagedFarmBlockRecord(
                zoneId = zoneId,
                x = soil.x,
                y = soil.y,
                z = soil.z,
                originalSoilData = soil.blockData.asString,
                originalCropData = cropData,
                activeCropData = cropData,
                indexed = true,
            )
        }

        val fixedCoordinates = fixedCrops.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
        val fixedRecords = fixedCropRecords(chunk).filterNot {
            it.zoneId == zoneId || Triple(it.x, it.y, it.z) in fixedCoordinates
        }.toMutableList()
        fixedCrops.mapTo(fixedRecords) { block ->
            ManagedFarmFixedCropRecord(zoneId, block.x, block.y, block.z, block.blockData.asString, null)
        }

        val leafCoordinates = orchardLeaves.mapTo(hashSetOf()) { Triple(it.x, it.y, it.z) }
        val leafRecords = orchardLeafRecords(chunk).filterNot {
            it.zoneId == zoneId || Triple(it.x, it.y, it.z) in leafCoordinates
        }.toMutableList()
        orchardLeaves.mapTo(leafRecords) { block ->
            ManagedFarmOrchardLeafRecord(zoneId, block.x, block.y, block.z)
        }

        require(blockRecords.size <= MAX_RECORDS_PER_CHUNK) { "Managed farm block list is unbounded" }
        require(fixedRecords.size <= MAX_RECORDS_PER_CHUNK) { "Fixed farm crop list is unbounded" }
        require(leafRecords.size <= MAX_RECORDS_PER_CHUNK) { "Farm orchard leaf list is unbounded" }
        write(chunk, blockRecords)
        writeFixedCrops(chunk, fixedRecords)
        writeOrchardLeaves(chunk, leafRecords)
    }

    private fun update(soil: Block, record: ManagedFarmBlockRecord) {
        val records = blockRecords(soil.chunk).toMutableList()
        records.removeIf { it.x == soil.x && it.y == soil.y && it.z == soil.z }
        records += record
        write(soil.chunk, records)
    }

    private fun createRecord(soil: Block, zoneId: String): ManagedFarmBlockRecord {
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        val cropData = crop.blockData.takeUnless { crop.type.isAir }?.asString
        return ManagedFarmBlockRecord(
            zoneId = zoneId,
            x = soil.x,
            y = soil.y,
            z = soil.z,
            originalSoilData = soil.blockData.asString,
            originalCropData = cropData,
            activeCropData = cropData,
        )
    }

    private fun updateFixedCrop(block: Block, record: ManagedFarmFixedCropRecord) {
        val records = fixedCropRecords(block.chunk).toMutableList()
        records.removeIf { it.x == block.x && it.y == block.y && it.z == block.z }
        records += record
        writeFixedCrops(block.chunk, records)
    }

    fun blockRecords(chunk: Chunk): List<ManagedFarmBlockRecord> {
        return blockSnapshot(chunk).records
    }

    private fun blockSnapshot(chunk: Chunk): ManagedFarmBlockSnapshot {
        val cacheKey = chunk.cacheKey()
        blockCache[cacheKey]?.let { return it }
        val raw = chunk.persistentDataContainer.get(key, PersistentDataType.STRING) ?: return EMPTY_BLOCK_SNAPSHOT
        return runCatching {
            require(raw.length <= 2_000_000) { "Managed farm block payload is unbounded" }
            gson.fromJson(raw, Array<ManagedFarmBlockRecord>::class.java)?.toList().orEmpty().also { records ->
                require(records.size <= MAX_RECORDS_PER_CHUNK) { "Managed farm block list is unbounded" }
                require(records.map { Triple(it.x, it.y, it.z) }.distinct().size == records.size) {
                    "Managed farm block coordinates are duplicated"
                }
                require(records.all { record ->
                    record.zoneId.matches(Regex("[a-z0-9_-]{1,48}")) &&
                        (record.x shr 4) == chunk.x && (record.z shr 4) == chunk.z &&
                        record.y in chunk.world.minHeight until chunk.world.maxHeight &&
                        record.originalSoilData.length in 1..512 &&
                        (record.originalCropData?.length ?: 0) <= 512 &&
                        (record.activeCropData?.length ?: 0) <= 512 &&
                        (record.temporaryMutation == null) == (record.temporaryRestoreAt == null) &&
                        (record.temporaryMutation == null || record.temporaryMutation.matches(Regex("[a-z0-9:_-]{1,96}"))) &&
                        (record.temporaryRestoreAt == null || record.temporaryRestoreAt >= 0L)
                }) { "Managed farm block record is invalid" }
            }.let(::ManagedFarmBlockSnapshot).also { blockCache[cacheKey] = it }
        }.getOrElse { failure ->
            logger.log(
                Level.SEVERE,
                "Could not decode managed farm blocks in ${chunk.world.name}:${chunk.x},${chunk.z}",
                failure,
            )
            EMPTY_BLOCK_SNAPSHOT
        }
    }

    private fun write(chunk: Chunk, records: List<ManagedFarmBlockRecord>) {
        require(records.size <= MAX_RECORDS_PER_CHUNK) { "Managed farm block list is unbounded" }
        val container = chunk.persistentDataContainer
        if (records.isEmpty()) container.remove(key)
        else container.set(key, PersistentDataType.STRING, gson.toJson(records))
        blockCache[chunk.cacheKey()] = ManagedFarmBlockSnapshot(records)
    }

    private fun writeFixedCrops(chunk: Chunk, records: List<ManagedFarmFixedCropRecord>) {
        require(records.size <= MAX_RECORDS_PER_CHUNK) { "Fixed farm crop list is unbounded" }
        val container = chunk.persistentDataContainer
        if (records.isEmpty()) container.remove(fixedCropKey)
        else container.set(fixedCropKey, PersistentDataType.STRING, gson.toJson(records))
        fixedCropCache[chunk.cacheKey()] = records.toList()
    }

    private fun writeOrchardLeaves(chunk: Chunk, records: List<ManagedFarmOrchardLeafRecord>) {
        require(records.size <= MAX_RECORDS_PER_CHUNK) { "Farm orchard leaf list is unbounded" }
        val container = chunk.persistentDataContainer
        if (records.isEmpty()) container.remove(orchardLeafKey)
        else container.set(orchardLeafKey, PersistentDataType.STRING, gson.toJson(records))
        orchardCache[chunk.cacheKey()] = records.toList()
    }

    private fun Chunk.cacheKey(): String = "${world.uid}:$x:$z"

    private fun boundedBlockChunkCache(): MutableMap<String, ManagedFarmBlockSnapshot> =
        object : LinkedHashMap<String, ManagedFarmBlockSnapshot>(128, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ManagedFarmBlockSnapshot>?,
            ): Boolean = size > 512
        }

    private fun <T> boundedChunkCache(): MutableMap<String, List<T>> =
        object : LinkedHashMap<String, List<T>>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<T>>?): Boolean = size > 512
        }

    private companion object {
        const val MAX_RECORDS_PER_CHUNK = 4_096
        val EMPTY_BLOCK_SNAPSHOT = ManagedFarmBlockSnapshot(emptyList())
    }
}

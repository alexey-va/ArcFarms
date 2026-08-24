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
import java.util.logging.Level

internal data class ManagedFarmBlockRecord(
    val zoneId: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalSoilData: String,
    val originalCropData: String?,
    val activeCropData: String?,
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

/**
 * Ordinary blocks cannot carry PDC. Paper chunks can, so every entry is keyed by
 * exact block coordinates inside the owning chunk and survives an abrupt stop.
 */
internal class FarmBlockLedger(plugin: Plugin) {
    private val gson = Gson()
    private val key = NamespacedKey(plugin, "farm_managed_blocks_v1")
    private val fixedCropKey = NamespacedKey(plugin, "farm_fixed_crops_v1")
    private val logger = plugin.logger

    fun capture(soil: Block, zoneId: String): ManagedFarmBlockRecord {
        val existing = record(soil)
        if (existing != null) return existing
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        val cropData = crop.blockData.takeUnless { crop.type.isAir }?.asString
        val created = ManagedFarmBlockRecord(
            zoneId = zoneId,
            x = soil.x,
            y = soil.y,
            z = soil.z,
            originalSoilData = soil.blockData.asString,
            originalCropData = cropData,
            activeCropData = cropData,
        )
        update(soil, created)
        return created
    }

    fun captureActiveCrop(soil: Block, zoneId: String): ManagedFarmBlockRecord {
        val current = capture(soil, zoneId)
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        val cropData = crop.blockData.takeUnless { crop.type.isAir || crop.type == Material.WATER }?.asString
        return current.copy(activeCropData = cropData).also { update(soil, it) }
    }

    fun captureActiveCropIfPresent(soil: Block, zoneId: String): ManagedFarmBlockRecord {
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        if (crop.type.isAir || crop.type == Material.WATER) return record(soil) ?: capture(soil, zoneId)
        return captureActiveCrop(soil, zoneId)
    }

    fun record(soil: Block): ManagedFarmBlockRecord? = records(soil).firstOrNull {
        it.x == soil.x && it.y == soil.y && it.z == soil.z
    }

    fun restoreActiveCrop(soil: Block): Boolean {
        val record = record(soil) ?: return false
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        val data = record.activeCropData
        if (data == null) crop.setType(Material.AIR, false)
        else crop.setBlockData(Bukkit.createBlockData(data), false)
        return true
    }

    fun restoreOriginal(soil: Block, clear: Boolean = true): Boolean {
        val record = record(soil) ?: return false
        soil.setBlockData(Bukkit.createBlockData(record.originalSoilData), false)
        val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
        val cropData = record.originalCropData
        if (cropData == null) crop.setType(Material.AIR, false)
        else crop.setBlockData(Bukkit.createBlockData(cropData), false)
        if (clear) remove(soil)
        return true
    }

    fun remove(soil: Block): Boolean {
        val records = records(soil).toMutableList()
        val removed = records.removeIf { it.x == soil.x && it.y == soil.y && it.z == soil.z }
        if (removed) write(soil, records)
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
            }
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

    private fun update(soil: Block, record: ManagedFarmBlockRecord) {
        val records = records(soil).toMutableList()
        records.removeIf { it.x == soil.x && it.y == soil.y && it.z == soil.z }
        records += record
        write(soil, records)
    }

    private fun updateFixedCrop(block: Block, record: ManagedFarmFixedCropRecord) {
        val records = fixedCropRecords(block.chunk).toMutableList()
        records.removeIf { it.x == block.x && it.y == block.y && it.z == block.z }
        records += record
        writeFixedCrops(block.chunk, records)
    }

    private fun records(block: Block): List<ManagedFarmBlockRecord> {
        val raw = block.chunk.persistentDataContainer.get(key, PersistentDataType.STRING) ?: return emptyList()
        return runCatching {
            require(raw.length <= 2_000_000) { "Managed farm block payload is unbounded" }
            gson.fromJson(raw, Array<ManagedFarmBlockRecord>::class.java)?.toList().orEmpty().also { records ->
                require(records.size <= MAX_RECORDS_PER_CHUNK) { "Managed farm block list is unbounded" }
                require(records.map { Triple(it.x, it.y, it.z) }.distinct().size == records.size) {
                    "Managed farm block coordinates are duplicated"
                }
                require(records.all { record ->
                    record.zoneId.matches(Regex("[a-z0-9_-]{1,48}")) &&
                        (record.x shr 4) == block.chunk.x && (record.z shr 4) == block.chunk.z &&
                        record.y in block.world.minHeight until block.world.maxHeight &&
                        record.originalSoilData.length in 1..512 &&
                        (record.originalCropData?.length ?: 0) <= 512 &&
                        (record.activeCropData?.length ?: 0) <= 512
                }) { "Managed farm block record is invalid" }
            }
        }.getOrElse { failure ->
            logger.log(
                Level.SEVERE,
                "Could not decode managed farm blocks in ${block.world.name}:${block.chunk.x},${block.chunk.z}",
                failure,
            )
            emptyList()
        }
    }

    private fun write(block: Block, records: List<ManagedFarmBlockRecord>) {
        require(records.size <= MAX_RECORDS_PER_CHUNK) { "Managed farm block list is unbounded" }
        val container = block.chunk.persistentDataContainer
        if (records.isEmpty()) container.remove(key)
        else container.set(key, PersistentDataType.STRING, gson.toJson(records))
    }

    private fun writeFixedCrops(chunk: Chunk, records: List<ManagedFarmFixedCropRecord>) {
        require(records.size <= MAX_RECORDS_PER_CHUNK) { "Fixed farm crop list is unbounded" }
        val container = chunk.persistentDataContainer
        if (records.isEmpty()) container.remove(fixedCropKey)
        else container.set(fixedCropKey, PersistentDataType.STRING, gson.toJson(records))
    }

    private companion object {
        const val MAX_RECORDS_PER_CHUNK = 4_096
    }
}

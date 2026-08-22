package ru.ruscrafting.farms.paper

import com.google.gson.Gson
import org.bukkit.Bukkit
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

/**
 * Ordinary blocks cannot carry PDC. Paper chunks can, so every entry is keyed by
 * exact block coordinates inside the owning chunk and survives an abrupt stop.
 */
internal class FarmBlockLedger(plugin: Plugin) {
    private val gson = Gson()
    private val key = NamespacedKey(plugin, "farm_managed_blocks_v1")
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

    private fun update(soil: Block, record: ManagedFarmBlockRecord) {
        val records = records(soil).toMutableList()
        records.removeIf { it.x == soil.x && it.y == soil.y && it.z == soil.z }
        records += record
        write(soil, records)
    }

    private fun records(block: Block): List<ManagedFarmBlockRecord> {
        val raw = block.chunk.persistentDataContainer.get(key, PersistentDataType.STRING) ?: return emptyList()
        return runCatching {
            require(raw.length <= 2_000_000) { "Managed farm block payload is unbounded" }
            gson.fromJson(raw, Array<ManagedFarmBlockRecord>::class.java)?.toList().orEmpty().also { records ->
                require(records.size <= MAX_RECORDS_PER_CHUNK) { "Managed farm block list is unbounded" }
                require(records.all { record ->
                    record.zoneId.matches(Regex("[a-z0-9_-]{1,48}")) &&
                        record.y in -4_096..4_096 &&
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

    private companion object {
        const val MAX_RECORDS_PER_CHUNK = 4_096
    }
}

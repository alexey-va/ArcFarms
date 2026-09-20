package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Chunk
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneCodec
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Palette encoding keeps a large cave's exact-original journal small without compression bombs. */
internal object MineExpeditionSceneCodec : WorksitePreparedSceneCodec {
    private data class Identity(val zone: String, val sequence: Long, val scene: Int, val total: Int)

    override fun encode(records: List<WorksitePreparedSceneRecord>, world: String, chunkX: Int, chunkZ: Int,
        minHeight: Int, maxHeight: Int): ByteArray {
        validate(records, world, chunkX, chunkZ, minHeight, maxHeight)
        val palette = records.flatMap { listOf(it.originalData, it.activeData) }.distinct()
        require(palette.size <= MAX_PALETTE)
        val identities = records.map { Identity(it.zoneId, it.sequence, it.sceneId, it.totalRecords) }.distinct()
        require(identities.size <= MAX_SCENES)
        val paletteIds = palette.withIndex().associate { it.value to it.index }
        val identityIds = identities.withIndex().associate { it.value to it.index }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(VERSION)
                output.writeShort(palette.size)
                palette.forEach(output::writeUTF)
                output.writeShort(identities.size)
                identities.forEach {
                    output.writeUTF(it.zone); output.writeLong(it.sequence)
                    output.writeInt(it.scene); output.writeInt(it.total)
                }
                output.writeInt(records.size)
                records.forEach { record ->
                    output.writeShort(identityIds.getValue(Identity(record.zoneId, record.sequence, record.sceneId, record.totalRecords)))
                    output.writeByte(((record.x and 15) shl 4) or (record.z and 15))
                    output.writeShort(record.y - minHeight)
                    output.writeShort(paletteIds.getValue(record.originalData))
                    output.writeShort(paletteIds.getValue(record.activeData))
                }
            }
            bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
        }
    }

    override fun decode(raw: ByteArray, world: String, chunkX: Int, chunkZ: Int,
        minHeight: Int, maxHeight: Int): List<WorksitePreparedSceneRecord> {
        require(raw.size <= MAX_BYTES)
        require(maxHeight > minHeight && maxHeight - minHeight <= 4096)
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported expedition scene journal version" }
            val paletteCount = input.readUnsignedShort().also { require(it <= MAX_PALETTE) }
            val palette = List(paletteCount) { input.readUTF().also { require(it.length in 1..512) } }
            require(palette.toSet().size == palette.size) { "Duplicate expedition journal palette value" }
            val sceneCount = input.readUnsignedShort().also { require(it <= MAX_SCENES) }
            val identities = List(sceneCount) { Identity(input.readUTF(), input.readLong(), input.readInt(), input.readInt()) }
            require(identities.toSet().size == identities.size)
            val count = input.readInt().also { require(it in 0..MAX_RECORDS_PER_CHUNK) }
            val records = List(count) {
                val identity = identities.getOrNull(input.readUnsignedShort()) ?: error("Invalid expedition scene identity")
                val position = input.readUnsignedByte()
                val y = minHeight + input.readUnsignedShort()
                val original = palette.getOrNull(input.readUnsignedShort()) ?: error("Invalid original palette index")
                val active = palette.getOrNull(input.readUnsignedShort()) ?: error("Invalid active palette index")
                WorksitePreparedSceneRecord(world, identity.zone, identity.sequence, identity.scene,
                    (chunkX shl 4) + (position shr 4), y, (chunkZ shl 4) + (position and 15),
                    original, active, "NONE", identity.total)
            }
            require(input.available() == 0) { "Trailing expedition scene journal data" }
            validate(records, world, chunkX, chunkZ, minHeight, maxHeight)
            records
        }
    }

    override fun foreignJournalOverlaps(chunk: Chunk, positions: Collection<Triple<Int, Int, Int>>): Boolean =
        chunk.persistentDataContainer.keys.any {
            it.namespace == "arcfarms" && it.key.endsWith("_v1") && it.key != "mine_expedition_v1"
        }

    private fun validate(records: List<WorksitePreparedSceneRecord>, world: String, chunkX: Int, chunkZ: Int,
        minHeight: Int, maxHeight: Int) {
        require(maxHeight > minHeight && maxHeight - minHeight <= 4096)
        require(records.size <= MAX_RECORDS_PER_CHUNK)
        require(records.map { Triple(it.x, it.y, it.z) }.toSet().size == records.size) {
            "Duplicate expedition journal coordinate"
        }
        records.forEach {
            require(it.world == world && it.x shr 4 == chunkX && it.z shr 4 == chunkZ)
            require(it.y in minHeight until maxHeight)
            require(it.zoneId.matches(Regex("[a-z0-9_-]{1,48}")))
            require(it.sequence >= 0 && it.sceneId >= 0 && it.totalRecords in 1..MAX_SCENE_RECORDS)
            require(it.marker == "NONE")
            require(it.originalData.length in 1..512 && it.activeData.length in 1..512)
        }
    }

    const val MAX_SCENE_RECORDS = 262_144
    private const val MAX_RECORDS_PER_CHUNK = 32_768
    private const val MAX_PALETTE = 2_048
    private const val MAX_SCENES = 64
    private const val MAX_BYTES = 2_097_152
    private const val VERSION = 1
}

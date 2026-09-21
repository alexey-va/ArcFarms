package ru.ruscrafting.farms.paper.mine.incident.rescue

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal enum class MineLostMinerMazeMarker {
    NONE,
    START,
    TARGET,
}

internal data class MineLostMinerMazeJournalRecord(
    val world: String,
    val zoneId: String,
    val sequence: Long,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalData: String,
    val mazeData: String,
    val marker: MineLostMinerMazeMarker,
    val totalRecords: Int,
    val geometryVersion: Int = 1,
)

/** Strict, bounded chunk-PDC codec for the lost-miner scene. */
internal object MineLostMinerMazeJournalCodec {
    private const val VERSION = 2
    private const val MAX_RECORDS_PER_CHUNK = 4_096
    private const val MAX_SCENE_RECORDS = 16_384
    private const val MAX_BLOCK_DATA_LENGTH = 512
    private const val MAX_JOURNAL_BYTES = 524_288
    private val ZONE_ID = Regex("[a-z0-9_-]{1,48}")

    fun encode(
        records: List<MineLostMinerMazeJournalRecord>,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): ByteArray {
        validateBounds(minHeight, maxHeight)
        require(records.size <= MAX_RECORDS_PER_CHUNK) { "Too many lost-miner maze records in one chunk" }
        records.forEach { validate(it, world, chunkX, chunkZ, minHeight, maxHeight) }
        validateUnique(records)
        val raw = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(VERSION)
                output.writeInt(records.size)
                records.forEach { record ->
                    output.writeUTF(record.zoneId)
                    output.writeLong(record.sequence)
                    output.writeInt(record.x)
                    output.writeInt(record.y)
                    output.writeInt(record.z)
                    output.writeUTF(record.originalData)
                    output.writeUTF(record.mazeData)
                    output.writeByte(record.marker.ordinal)
                    output.writeInt(record.totalRecords)
                    output.writeInt(record.geometryVersion)
                }
            }
            bytes.toByteArray()
        }
        require(raw.size <= MAX_JOURNAL_BYTES) { "Lost-miner maze journal is too large" }
        return raw
    }

    fun decode(
        raw: ByteArray,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): List<MineLostMinerMazeJournalRecord> {
        validateBounds(minHeight, maxHeight)
        require(raw.size <= MAX_JOURNAL_BYTES) { "Oversized lost-miner maze journal" }
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            val version = input.readInt()
            require(version in 1..VERSION) { "Unsupported lost-miner maze journal version" }
            val count = input.readInt()
            require(count in 0..MAX_RECORDS_PER_CHUNK) { "Invalid lost-miner maze record count" }
            val records = List(count) {
                MineLostMinerMazeJournalRecord(
                    world = world,
                    zoneId = input.readUTF(),
                    sequence = input.readLong(),
                    x = input.readInt(),
                    y = input.readInt(),
                    z = input.readInt(),
                    originalData = input.readUTF(),
                    mazeData = input.readUTF(),
                    marker = MineLostMinerMazeMarker.entries.getOrNull(input.readUnsignedByte())
                        ?: error("Invalid lost-miner maze marker"),
                    totalRecords = input.readInt(),
                    geometryVersion = if(version >= 2) input.readInt() else 1,
                ).also { validate(it, world, chunkX, chunkZ, minHeight, maxHeight) }
            }
            validateUnique(records)
            require(input.available() == 0) { "Trailing data in lost-miner maze journal" }
            records
        }
    }

    private fun validate(
        record: MineLostMinerMazeJournalRecord,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ) {
        require(record.world == world) { "Lost-miner maze record belongs to another world" }
        require(record.zoneId.matches(ZONE_ID)) { "Invalid lost-miner maze zone id" }
        require(record.sequence >= 0) { "Invalid lost-miner maze sequence" }
        require(record.x shr 4 == chunkX && record.z shr 4 == chunkZ) {
            "Lost-miner maze record belongs to another chunk"
        }
        require(record.y in minHeight until maxHeight) { "Lost-miner maze record height is outside the world" }
        require(record.originalData.length in 1..MAX_BLOCK_DATA_LENGTH) { "Invalid original lost-miner maze BlockData" }
        require(record.mazeData.length in 1..MAX_BLOCK_DATA_LENGTH) { "Invalid active lost-miner maze BlockData" }
        require(record.geometryVersion in 1..MineLostMinerCaveBlocks.GEOMETRY_VERSION) { "Invalid lost-miner geometry version" }
        require(record.totalRecords in 1..MAX_SCENE_RECORDS) { "Invalid lost-miner maze scene size" }
    }

    private fun validateUnique(records: List<MineLostMinerMazeJournalRecord>) {
        require(records.map { Triple(it.x, it.y, it.z) }.distinct().size == records.size) {
            "Duplicate lost-miner maze journal position"
        }
    }

    private fun validateBounds(minHeight: Int, maxHeight: Int) {
        require(minHeight < maxHeight) { "Invalid lost-miner maze world height bounds" }
    }
}

package ru.ruscrafting.farms.paper

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal data class FarmGiantCropJournalRecord(
    val world: String,
    val zoneId: String,
    val sequence: Long,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalData: String,
    val incidentData: String,
    val broken: Boolean,
)

/** Bounded, exact codec for the chunk-PDC giant-crop recovery journal. */
internal object FarmGiantCropJournalCodec {
    private val ZONE_ID = Regex("[a-z0-9_-]{1,48}")
    private const val JOURNAL_VERSION = 1
    private const val MAX_RECORDS_PER_CHUNK = 256
    private const val MAX_BLOCK_DATA_LENGTH = 512
    const val MAX_JOURNAL_BYTES = 131_072

    fun encode(
        records: List<FarmGiantCropJournalRecord>,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): ByteArray {
        validateBounds(minHeight, maxHeight)
        require(records.size <= MAX_RECORDS_PER_CHUNK) { "Too many giant crop records in one chunk" }
        records.forEach { validateRecord(it, world, chunkX, chunkZ, minHeight, maxHeight) }
        validateUniquePositions(records)
        val raw = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(JOURNAL_VERSION)
                output.writeInt(records.size)
                records.forEach { record ->
                    output.writeUTF(record.zoneId)
                    output.writeLong(record.sequence)
                    output.writeInt(record.x)
                    output.writeInt(record.y)
                    output.writeInt(record.z)
                    output.writeUTF(record.originalData)
                    output.writeUTF(record.incidentData)
                    output.writeBoolean(record.broken)
                }
            }
            bytes.toByteArray()
        }
        require(raw.size <= MAX_JOURNAL_BYTES) { "Giant crop journal is too large" }
        return raw
    }

    fun decode(
        raw: ByteArray,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): List<FarmGiantCropJournalRecord> {
        validateBounds(minHeight, maxHeight)
        require(raw.size <= MAX_JOURNAL_BYTES) { "Oversized giant crop journal" }
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readInt() == JOURNAL_VERSION) { "Unsupported giant crop journal version" }
            val count = input.readInt()
            require(count in 0..MAX_RECORDS_PER_CHUNK) { "Invalid giant crop record count" }
            val records = List(count) {
                FarmGiantCropJournalRecord(
                    world = world,
                    zoneId = input.readUTF(),
                    sequence = input.readLong(),
                    x = input.readInt(),
                    y = input.readInt(),
                    z = input.readInt(),
                    originalData = input.readUTF(),
                    incidentData = input.readUTF(),
                    broken = input.readBoolean(),
                ).also { validateRecord(it, world, chunkX, chunkZ, minHeight, maxHeight) }
            }
            validateUniquePositions(records)
            require(input.available() == 0) { "Trailing data in giant crop journal" }
            records
        }
    }

    private fun validateRecord(
        record: FarmGiantCropJournalRecord,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ) {
        require(record.world == world) { "Giant crop record belongs to another world" }
        require(record.zoneId.matches(ZONE_ID)) { "Invalid giant crop zone id" }
        require(record.sequence >= 0L) { "Invalid giant crop sequence" }
        require(record.x shr 4 == chunkX && record.z shr 4 == chunkZ) { "Giant crop record belongs to another chunk" }
        require(record.y in minHeight until maxHeight) { "Giant crop record height is outside the world" }
        require(record.originalData.isNotBlank()) { "Giant crop original BlockData is blank" }
        require(record.incidentData.isNotBlank()) { "Giant crop incident BlockData is blank" }
        require(record.originalData.length <= MAX_BLOCK_DATA_LENGTH) { "Giant crop original BlockData is too long" }
        require(record.incidentData.length <= MAX_BLOCK_DATA_LENGTH) { "Giant crop incident BlockData is too long" }
    }

    private fun validateUniquePositions(records: List<FarmGiantCropJournalRecord>) {
        require(records.map { Triple(it.x, it.y, it.z) }.distinct().size == records.size) {
            "Duplicate giant crop journal position"
        }
    }

    private fun validateBounds(minHeight: Int, maxHeight: Int) {
        require(minHeight < maxHeight) { "Invalid giant crop world height bounds" }
    }
}

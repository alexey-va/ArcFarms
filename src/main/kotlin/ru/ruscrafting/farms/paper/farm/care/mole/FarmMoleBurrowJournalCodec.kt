package ru.ruscrafting.farms.paper.farm.care.mole

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal enum class FarmMoleBurrowMarker {
    NONE,
    START,
    LAIR,
}

internal data class FarmMoleBurrowJournalRecord(
    val world: String,
    val zoneId: String,
    val sequence: Long,
    val x: Int,
    val y: Int,
    val z: Int,
    val originalData: String,
    val burrowData: String,
    val marker: FarmMoleBurrowMarker,
    val totalRecords: Int,
)

/** Exact, bounded chunk-PDC codec for crash-safe temporary mole tunnels. */
internal object FarmMoleBurrowJournalCodec {
    private const val VERSION = 1
    private const val MAX_RECORDS_PER_CHUNK = 512
    private const val MAX_BLOCK_DATA_LENGTH = 512
    const val MAX_SCENE_RECORDS = 1_024
    const val MAX_JOURNAL_BYTES = 524_288
    private val ZONE_ID = Regex("[a-z0-9_-]{1,48}")

    fun encode(
        records: List<FarmMoleBurrowJournalRecord>,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): ByteArray {
        validateBounds(minHeight, maxHeight)
        require(records.size <= MAX_RECORDS_PER_CHUNK) { "Too many mole burrow records in one chunk" }
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
                    output.writeUTF(record.burrowData)
                    output.writeByte(record.marker.ordinal)
                    output.writeInt(record.totalRecords)
                }
            }
            bytes.toByteArray()
        }
        require(raw.size <= MAX_JOURNAL_BYTES) { "Mole burrow journal is too large" }
        return raw
    }

    fun decode(
        raw: ByteArray,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): List<FarmMoleBurrowJournalRecord> {
        validateBounds(minHeight, maxHeight)
        require(raw.size <= MAX_JOURNAL_BYTES) { "Oversized mole burrow journal" }
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported mole burrow journal version" }
            val count = input.readInt()
            require(count in 0..MAX_RECORDS_PER_CHUNK) { "Invalid mole burrow record count" }
            val records = List(count) {
                FarmMoleBurrowJournalRecord(
                    world = world,
                    zoneId = input.readUTF(),
                    sequence = input.readLong(),
                    x = input.readInt(),
                    y = input.readInt(),
                    z = input.readInt(),
                    originalData = input.readUTF(),
                    burrowData = input.readUTF(),
                    marker = FarmMoleBurrowMarker.entries.getOrNull(input.readUnsignedByte())
                        ?: error("Invalid mole burrow marker"),
                    totalRecords = input.readInt(),
                ).also { validate(it, world, chunkX, chunkZ, minHeight, maxHeight) }
            }
            validateUnique(records)
            require(input.available() == 0) { "Trailing data in mole burrow journal" }
            records
        }
    }

    private fun validate(
        record: FarmMoleBurrowJournalRecord,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ) {
        require(record.world == world) { "Mole burrow record belongs to another world" }
        require(record.zoneId.matches(ZONE_ID)) { "Invalid mole burrow zone id" }
        require(record.sequence >= 0) { "Invalid mole burrow sequence" }
        require(record.x shr 4 == chunkX && record.z shr 4 == chunkZ) { "Mole burrow record belongs to another chunk" }
        require(record.y in minHeight until maxHeight) { "Mole burrow record height is outside the world" }
        require(record.originalData.length in 1..MAX_BLOCK_DATA_LENGTH) { "Invalid original mole burrow BlockData" }
        require(record.burrowData.length in 1..MAX_BLOCK_DATA_LENGTH) { "Invalid active mole burrow BlockData" }
        require(record.totalRecords in 1..MAX_SCENE_RECORDS) { "Invalid mole burrow scene size" }
    }

    private fun validateUnique(records: List<FarmMoleBurrowJournalRecord>) {
        require(records.map { Triple(it.x, it.y, it.z) }.distinct().size == records.size) {
            "Duplicate mole burrow journal position"
        }
    }

    private fun validateBounds(minHeight: Int, maxHeight: Int) {
        require(minHeight < maxHeight) { "Invalid mole burrow world height bounds" }
    }
}

package ru.ruscrafting.farms.paper

import com.google.gson.Gson

/** Detached chunk ledger values; codecs and index construction are worker-safe. */
internal data class FarmLedgerContents(
    val blocks: List<ManagedFarmBlockRecord>,
    val fixed: List<ManagedFarmFixedCropRecord>,
    val leaves: List<ManagedFarmOrchardLeafRecord>,
)

internal data class FarmLedgerPayloads(val blocks: ByteArray?, val fixed: ByteArray?, val leaves: ByteArray?)

internal object FarmBlockLedgerCodec {
    const val MAX_RECORDS = 4_096
    private val gson = Gson()
    private val zone = Regex("[a-z0-9_-]{1,48}")
    private val mutation = Regex("[a-z0-9:_-]{1,96}")

    fun decode(blocks: Any?, fixed: Any?, leaves: Any?, x: Int, z: Int, minY: Int, maxY: Int): FarmLedgerContents {
        val blockRecords = decode(blocks, Array<ManagedFarmBlockRecord>::class.java)
        coordinates(blockRecords.map { Triple(it.x, it.y, it.z) }, x, z, minY, maxY)
        require(blockRecords.all { record -> record.zoneId.matches(zone) &&
            record.originalSoilData.length in 1..512 && (record.originalCropData?.length ?: 0) <= 512 &&
            (record.activeCropData?.length ?: 0) <= 512 &&
            (record.temporaryMutation == null) == (record.temporaryRestoreAt == null) &&
            (record.temporaryMutation == null || record.temporaryMutation.matches(mutation)) &&
            (record.temporaryRestoreAt == null || record.temporaryRestoreAt >= 0L)
        }) { "Managed farm block record is invalid" }
        val fixedRecords = decode(fixed, Array<ManagedFarmFixedCropRecord>::class.java)
        coordinates(fixedRecords.map { Triple(it.x, it.y, it.z) }, x, z, minY, maxY)
        require(fixedRecords.all { it.zoneId.matches(zone) && it.originalBlockData.length in 1..512 &&
            (it.restoreAt == null || it.restoreAt >= 0L) }) { "Fixed farm crop record is invalid" }
        val leafRecords = decode(leaves, Array<ManagedFarmOrchardLeafRecord>::class.java)
        coordinates(leafRecords.map { Triple(it.x, it.y, it.z) }, x, z, minY, maxY)
        require(leafRecords.all { it.zoneId.matches(zone) }) { "Farm orchard leaf record is invalid" }
        return FarmLedgerContents(blockRecords, fixedRecords, leafRecords)
    }

    fun encode(contents: FarmLedgerContents): FarmLedgerPayloads = FarmLedgerPayloads(
        encode(contents.blocks), encode(contents.fixed), encode(contents.leaves),
    )

    fun encodeBlocks(records: List<ManagedFarmBlockRecord>): ByteArray? = encode(records)

    private fun encode(records: List<*>): ByteArray? {
        require(records.size <= MAX_RECORDS) { "Farm ledger record list is unbounded" }
        if (records.isEmpty()) return null
        return gson.toJson(records).toByteArray(Charsets.UTF_8).also {
            require(it.size <= 2_000_000) { "Farm ledger payload is unbounded" }
        }
    }

    private fun <T> decode(raw: Any?, type: Class<Array<T>>): List<T> {
        val text = when (raw) {
            null -> return emptyList()
            is ByteArray -> { require(raw.size <= 2_000_000); raw.toString(Charsets.UTF_8) }
            is String -> raw
            else -> error("Unsupported farm ledger payload")
        }
        require(text.length <= 2_000_000) { "Farm ledger payload is unbounded" }
        return gson.fromJson(text, type)?.toList().orEmpty().also {
            require(it.size <= MAX_RECORDS) { "Farm ledger record list is unbounded" }
        }
    }

    private fun coordinates(points: List<Triple<Int, Int, Int>>, x: Int, z: Int, minY: Int, maxY: Int) {
        require(points.distinct().size == points.size) { "Farm ledger coordinates are duplicated" }
        require(points.all { (it.first shr 4) == x && (it.third shr 4) == z && it.second in minY until maxY }) {
            "Farm ledger coordinates are outside their chunk"
        }
    }
}

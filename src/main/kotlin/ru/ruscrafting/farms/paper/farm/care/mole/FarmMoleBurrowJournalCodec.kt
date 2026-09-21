package ru.ruscrafting.farms.paper.farm.care.mole

import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneCodec
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneRecord
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
    val burrowId: Int = 0,
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
    private const val VERSION = 2
    private const val LEGACY_VERSION = 1
    // Shared mine workings have a bounded 33 x 45 x 10 envelope. A rotated or
    // offset chamber can occupy 2,560 cells in one chunk; its complete journal
    // exceeds the earlier 8,192-cell burrow limit. Encoding stays bounded.
    private const val MAX_RECORDS_PER_CHUNK = 4_096
    private const val MAX_BLOCK_DATA_LENGTH = 512
    const val MAX_SCENE_RECORDS = 16_384
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
                    output.writeByte(record.burrowId)
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
            val version = input.readInt()
            require(version == VERSION || version == LEGACY_VERSION) { "Unsupported mole burrow journal version" }
            val count = input.readInt()
            require(count in 0..MAX_RECORDS_PER_CHUNK) { "Invalid mole burrow record count" }
            val records = List(count) {
                FarmMoleBurrowJournalRecord(
                    world = world,
                    zoneId = input.readUTF(),
                    sequence = input.readLong(),
                    burrowId = if (version >= VERSION) input.readUnsignedByte() else 0,
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
        require(record.burrowId in 0..15) { "Invalid mole burrow id" }
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

/** Reuses the farm binary journal format for any prepared worksite namespace. */
private val DEFAULT_FOREIGN_JOURNAL_NAMESPACES = setOf("farm_mole_burrow", "farm_greenhouse", "mine_working")

internal class FarmMoleBurrowWorksiteSceneCodec(
    private val plugin: Plugin,
    private val journalNamespace: String,
    private val foreignJournalNamespaces: Set<String> = DEFAULT_FOREIGN_JOURNAL_NAMESPACES,
) : WorksitePreparedSceneCodec {
    override fun decode(
        raw: ByteArray,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): List<WorksitePreparedSceneRecord> = FarmMoleBurrowJournalCodec.decode(
        raw, world, chunkX, chunkZ, minHeight, maxHeight,
    ).map { record ->
        WorksitePreparedSceneRecord(
            world = record.world,
            zoneId = record.zoneId,
            sequence = record.sequence,
            sceneId = record.burrowId,
            x = record.x,
            y = record.y,
            z = record.z,
            originalData = record.originalData,
            activeData = record.burrowData,
            marker = record.marker.name,
            totalRecords = record.totalRecords,
        )
    }

    override fun encode(
        records: List<WorksitePreparedSceneRecord>,
        world: String,
        chunkX: Int,
        chunkZ: Int,
        minHeight: Int,
        maxHeight: Int,
    ): ByteArray = FarmMoleBurrowJournalCodec.encode(
        records.map { record ->
            FarmMoleBurrowJournalRecord(
                world = record.world,
                zoneId = record.zoneId,
                sequence = record.sequence,
                burrowId = record.sceneId,
                x = record.x,
                y = record.y,
                z = record.z,
                originalData = record.originalData,
                burrowData = record.activeData,
                marker = FarmMoleBurrowMarker.valueOf(record.marker),
                totalRecords = record.totalRecords,
            )
        }, world, chunkX, chunkZ, minHeight, maxHeight,
    )

    override fun foreignJournalOverlaps(
        chunk: Chunk,
        positions: Collection<Triple<Int, Int, Int>>,
    ): Boolean {
        val wanted = positions.toHashSet()
        return foreignJournalNamespaces
            .asSequence()
            .filter { it != journalNamespace }
            .map { NamespacedKey(plugin, "${it}_v1") }
            .any { key ->
                val raw = chunk.persistentDataContainer.get(key, PersistentDataType.BYTE_ARRAY) ?: return@any false
                runCatching {
                    FarmMoleBurrowJournalCodec.decode(
                        raw,
                        chunk.world.name,
                        chunk.x,
                        chunk.z,
                        chunk.world.minHeight,
                        chunk.world.maxHeight,
                    ).any { Triple(it.x, it.y, it.z) in wanted }
                }.getOrElse { true }
            }
    }

}

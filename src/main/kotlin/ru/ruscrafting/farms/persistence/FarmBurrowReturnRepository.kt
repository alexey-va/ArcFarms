package ru.ruscrafting.farms.persistence

import com.google.gson.Gson
import com.google.gson.JsonParser
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableRecordJournal
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

data class FarmBurrowReturn(
    val playerId: UUID,
    val zoneId: String,
    val sequence: Long,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val enteredAt: Long,
)

/** One durable safe return per player, committed before entering a temporary tunnel. */
class FarmBurrowReturnRepository(dataRoot: Path) {
    private val gson = Gson()
    private val journal = DurableRecordJournal(
        root = dataRoot,
        relativeDirectory = Path.of("data/recovery/farm-burrow-returns"),
        maxRecordBytes = MAX_RECORD_BYTES,
        encode = { record: FarmBurrowReturn -> gson.toJson(record).toByteArray(StandardCharsets.UTF_8) },
        decode = ::decode,
        validate = ::validate,
    )

    fun commit(record: FarmBurrowReturn): FarmBurrowReturn =
        journal.commit(record.playerId.toString(), record)

    fun load(playerId: UUID): FarmBurrowReturn? = journal.loadOrNull(playerId.toString())?.also { record ->
        require(record.playerId == playerId) { "Farm burrow return identity does not match its record id" }
    }

    fun acknowledge(record: FarmBurrowReturn): DurableAcknowledgementOutcome =
        journal.acknowledgeExactly(record.playerId.toString(), record, FarmBurrowReturn::equals)

    private fun decode(raw: ByteArray): FarmBurrowReturn {
        require(raw.size <= MAX_RECORD_BYTES) { "Farm burrow return record is oversized" }
        val text = raw.toString(StandardCharsets.UTF_8)
        val root = JsonParser.parseString(text).asJsonObject
        require(root.keySet() == FIELDS) { "Farm burrow return record has an invalid shape" }
        return FarmBurrowReturn(
            playerId = UUID.fromString(root.get("playerId").asString),
            zoneId = root.get("zoneId").asString,
            sequence = root.get("sequence").asLong,
            world = root.get("world").asString,
            x = root.get("x").asDouble,
            y = root.get("y").asDouble,
            z = root.get("z").asDouble,
            yaw = root.get("yaw").asFloat,
            pitch = root.get("pitch").asFloat,
            enteredAt = root.get("enteredAt").asLong,
        ).also(::validate)
    }

    private fun validate(record: FarmBurrowReturn) {
        require(record.zoneId.matches(ZONE_ID)) { "Invalid farm burrow return zone" }
        require(record.sequence >= 0L) { "Invalid farm burrow return sequence" }
        require(record.world.matches(WORLD_ID)) { "Invalid farm burrow return world" }
        require(listOf(record.x, record.y, record.z).all(Double::isFinite)) { "Farm burrow return coordinates are invalid" }
        require(record.y in -4_096.0..4_096.0) { "Farm burrow return height is invalid" }
        require(record.yaw.isFinite() && record.pitch.isFinite()) { "Farm burrow return rotation is invalid" }
        require(record.enteredAt >= 0L) { "Farm burrow return timestamp is invalid" }
    }

    private companion object {
        const val MAX_RECORD_BYTES = 4_096L
        val ZONE_ID = Regex("[a-z0-9_-]{1,48}")
        val WORLD_ID = Regex("[A-Za-z0-9._-]{1,128}")
        val FIELDS = setOf("playerId", "zoneId", "sequence", "world", "x", "y", "z", "yaw", "pitch", "enteredAt")
    }
}

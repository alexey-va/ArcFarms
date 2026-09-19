package ru.ruscrafting.farms.domain

import kotlin.math.round
import kotlin.math.floor

/** Names accepted by the mine point editor and persisted in mine-locations.json. */
object MineLocationKeys {
    val workshop = listOf("ore_input", "ore_crusher", "ore_furnace", "ore_output", "ore_shipping")
    val workings = (1..12).map { "working_$it" }
    val all = workshop + workings

    fun isWorkshop(value: String): Boolean = value in workshop
    fun isWorking(value: String): Boolean = value in workings
    fun isKnown(value: String): Boolean = value in all

    fun canonical(raw: String): String? = raw.lowercase().replace('-', '_').takeIf(::isKnown)
}

/** A player-feet location for a mine marker or side-working entrance. */
data class MineLocationPosition(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
) {
    init {
        require(DomainIdentifiers.isWorld(world)) { "Invalid mine location world: $world" }
        require(listOf(x, y, z).all(Double::isFinite)) { "Mine location coordinates must be finite" }
        require(x in -30_000_000.0..30_000_000.0 && z in -30_000_000.0..30_000_000.0) {
            "Mine location is outside the world border"
        }
        require(y in -2_048.0..2_048.0 && yaw.isFinite()) { "Mine location height or rotation is invalid" }
        require(yaw == cardinalYaw(yaw) || (yaw == 180f && cardinalYaw(yaw) == -180f)) {
            "Mine location yaw must be cardinal"
        }
    }

    companion object {
        fun capture(world: String, x: Double, y: Double, z: Double, yaw: Float): MineLocationPosition =
            MineLocationPosition(world, x, y, z, cardinalYaw(yaw))

        fun cardinalYaw(yaw: Float): Float {
            require(yaw.isFinite()) { "Mine location yaw must be finite" }
            val snapped = round((yaw % 360f) / 90f) * 90f
            return when {
                snapped >= 180f -> snapped - 360f
                snapped < -180f -> snapped + 360f
                else -> snapped
            }
        }
    }

    fun blockPosition(): ru.ruscrafting.farms.domain.worksite.WorksitePosition =
        ru.ruscrafting.farms.domain.worksite.WorksitePosition(world, floor(x).toInt(), floor(y).toInt(), floor(z).toInt())

    /** Converts Minecraft cardinal yaw to MineWorkingPlacement's forward direction. */
    fun workingDirection(): Int = when (if (yaw == 180f) -180f else yaw) {
        0f -> 0
        90f -> 1
        -180f -> 2
        -90f -> 3
        else -> error("Mine location yaw is not cardinal: $yaw")
    }

    fun workingPlacement(floorId: String, layoutSeed: Long = 0L): MineWorkingPlacement =
        MineWorkingPlacement(blockPosition().let { it.copy(y = it.y - 1) }, workingDirection(), floorId, layoutSeed)
}

data class MineZoneLocations(
    val workshop: Map<String, MineLocationPosition> = emptyMap(),
    val workings: Map<String, MineLocationPosition> = emptyMap(),
) {
    init {
        require(workshop.keys.all(MineLocationKeys::isWorkshop)) { "Unknown mine workshop point" }
        require(workings.keys.all(MineLocationKeys::isWorking)) { "Unknown mine working point" }
        require(workshop.size <= MineLocationKeys.workshop.size) { "Too many mine workshop points" }
        require(workings.size <= MineLocationKeys.workings.size) { "Too many mine working points" }
    }

    fun point(kind: String): MineLocationPosition? =
        if (MineLocationKeys.isWorkshop(kind)) workshop[kind] else workings[kind]

    fun without(kind: String): MineZoneLocations = when {
        MineLocationKeys.isWorkshop(kind) -> copy(workshop = workshop - kind)
        MineLocationKeys.isWorking(kind) -> copy(workings = workings - kind)
        else -> this
    }

    fun with(kind: String, position: MineLocationPosition): MineZoneLocations = when {
        MineLocationKeys.isWorkshop(kind) -> copy(workshop = workshop + (kind to position))
        MineLocationKeys.isWorking(kind) -> copy(workings = workings + (kind to position))
        else -> error("Unknown mine point: $kind")
    }
}

data class MineLocations(
    val schemaVersion: Int = SCHEMA_VERSION,
    val zones: Map<String, MineZoneLocations> = emptyMap(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported mine location schema: $schemaVersion" }
    }

    fun without(zoneId: String, kind: String): MineLocations {
        val zone = zones[zoneId] ?: return this
        val remaining = zone.without(kind)
        val updated = if (remaining.workshop.isEmpty() && remaining.workings.isEmpty()) zones - zoneId else zones + (zoneId to remaining)
        return copy(zones = updated)
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

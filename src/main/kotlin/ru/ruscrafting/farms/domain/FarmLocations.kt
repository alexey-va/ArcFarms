package ru.ruscrafting.farms.domain

enum class FarmPointKind {
    TOOL,
    SEEDS,
    WATER,
    ARCHERY,
    CRATES,
    RECEIVING,
    CART,
    CUSTOMER,
    TRAVEL,
    HIVE,
    IRRIGATION,
    COVERS,
    SCARECROWS,
    PEN,
    PERK_VENDOR,
    PROCESSING,
    PROCESSING_INPUT,
    PROCESSING_INPUT_2,
    PROCESSING_INPUT_3,
    PROCESSING_INPUT_4,
    PROCESSING_OUTPUT,
    FIRE_EQUIPMENT,
    FIREWOOD,
}

data class FarmPointPosition(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    init {
        require(DomainIdentifiers.isWorld(world)) { "Invalid farm point world: $world" }
        require(listOf(x, y, z).all(Double::isFinite)) { "Farm point coordinates must be finite" }
        require(x in -30_000_000.0..30_000_000.0 && z in -30_000_000.0..30_000_000.0) {
            "Farm point is outside the world border"
        }
        require(y in -2_048.0..2_048.0 && yaw.isFinite() && pitch.isFinite() && pitch in -90f..90f) {
            "Farm point rotation or height is invalid"
        }
    }
}

object FarmProcessingPointOrientation {
    fun normalize(position: FarmPointPosition): FarmPointPosition {
        val snapped = kotlin.math.round(position.yaw / 90f) * 90f
        val normalizedYaw = when {
            snapped >= 180f -> snapped - 360f
            snapped < -180f -> snapped + 360f
            else -> snapped
        }
        return position.copy(yaw = normalizedYaw, pitch = 0f)
    }
}

/** Ground props keep the operator's facing but never inherit a camera tilt. */
object FarmGroundDisplayPointOrientation {
    fun normalize(position: FarmPointPosition): FarmPointPosition = position.copy(pitch = 0f)
}

data class FarmLocationOverrides(
    val schemaVersion: Int = SCHEMA_VERSION,
    val zones: Map<String, Map<FarmPointKind, FarmPointPosition>> = emptyMap(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported farm location schema: $schemaVersion" }
    }

    fun without(zoneId: String, kind: FarmPointKind): FarmLocationOverrides {
        val zone = zones[zoneId] ?: return this
        if (kind !in zone) return this
        val remaining = zone - kind
        val updatedZones = if (remaining.isEmpty()) zones - zoneId else zones + (zoneId to remaining)
        return copy(zones = updatedZones)
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

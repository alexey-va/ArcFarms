package ru.ruscrafting.farms.persistence

import ru.ruscrafting.farms.domain.MineLocationKeys
import ru.ruscrafting.farms.domain.MineLocations
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Durable explicit mine point overrides; no geometry defaults are stored or inferred here. */
class MineLocationRepository(dataRoot: Path) : AutoCloseable {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/mine-locations.json"),
        type = MineLocations::class.java,
        emptyValue = ::MineLocations,
        validate = ::validate,
    )

    fun load(): MineLocations = store.load()

    fun saveAsync(value: MineLocations): CompletableFuture<Unit> = store.saveAsync(value)

    fun saveBlocking(value: MineLocations) = store.saveBlocking(value)

    override fun close() = store.close()

    private fun validate(value: MineLocations) {
        require(value.schemaVersion == MineLocations.SCHEMA_VERSION) { "Unsupported mine location schema" }
        require(value.zones.size <= MAX_ZONES) { "Mine location overrides contain too many zones" }
        value.zones.forEach { (zoneId, zone) ->
            require(zoneId.matches(ZONE_PATTERN)) { "Invalid mine location zone: $zoneId" }
            require(zone.workshop.keys.all(MineLocationKeys::isWorkshop)) { "Unknown mine workshop point" }
            require(zone.workings.keys.all(MineLocationKeys::isWorking)) { "Unknown mine working point" }
            require(zone.expeditions.keys.all(MineLocationKeys::isExpedition)) { "Unknown mine expedition point" }
            require((zone.workshop.keys + zone.workings.keys).distinct().size == zone.workshop.size + zone.workings.size) {
                "Duplicate mine point key"
            }
            (zone.workshop.values + zone.workings.values + zone.expeditions.values).forEach { position ->
                // Re-run the domain checks after Gson deserialization, including finite values.
                require(position.world.matches(WORLD_PATTERN)) { "Invalid mine point world" }
                require(listOf(position.x, position.y, position.z).all(Double::isFinite)) {
                    "Invalid mine point coordinates"
                }
                require(position.x in -30_000_000.0..30_000_000.0 && position.z in -30_000_000.0..30_000_000.0) {
                    "Mine point is outside the world border"
                }
                require(position.y in -2_048.0..2_048.0 && position.yaw.isFinite()) {
                    "Mine point rotation or height is invalid"
                }
                require(
                    position.yaw == ru.ruscrafting.farms.domain.MineLocationPosition.cardinalYaw(position.yaw) ||
                        (position.yaw == 180f && ru.ruscrafting.farms.domain.MineLocationPosition.cardinalYaw(position.yaw) == -180f),
                ) {
                    "Mine point yaw must be cardinal"
                }
            }
        }
    }

    private companion object {
        const val MAX_ZONES = 256
        val ZONE_PATTERN = Regex("[a-z0-9_-]{1,48}")
        val WORLD_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

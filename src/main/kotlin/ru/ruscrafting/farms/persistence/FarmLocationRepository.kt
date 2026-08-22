package ru.ruscrafting.farms.persistence

import ru.ruscrafting.farms.domain.FarmLocationOverrides
import java.nio.file.Path

class FarmLocationRepository(dataRoot: Path) : AutoCloseable {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/farm-locations.json"),
        type = FarmLocationOverrides::class.java,
        emptyValue = ::FarmLocationOverrides,
        validate = ::validate,
    )

    fun load(): FarmLocationOverrides = store.load()

    fun saveBlocking(value: FarmLocationOverrides) = store.saveBlocking(value)

    override fun close() = store.close()

    private fun validate(value: FarmLocationOverrides) {
        require(value.schemaVersion == FarmLocationOverrides.SCHEMA_VERSION) { "Unsupported farm location schema" }
        require(value.zones.size <= 256) { "Farm location overrides contain too many zones" }
        value.zones.forEach { (zoneId, points) ->
            require(zoneId.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid farm location zone: $zoneId" }
            require(points.size <= ru.ruscrafting.farms.domain.FarmPointKind.entries.size) {
                "Farm location override contains too many points"
            }
            points.values.forEach { point ->
                require(point.world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid farm point world" }
                require(listOf(point.x, point.y, point.z).all(Double::isFinite)) { "Invalid farm point coordinates" }
                require(point.x in -30_000_000.0..30_000_000.0 && point.z in -30_000_000.0..30_000_000.0) {
                    "Farm point is outside the world border"
                }
                require(point.y in -2_048.0..2_048.0 && point.yaw.isFinite() && point.pitch.isFinite() && point.pitch in -90f..90f) {
                    "Farm point rotation or height is invalid"
                }
            }
        }
    }
}

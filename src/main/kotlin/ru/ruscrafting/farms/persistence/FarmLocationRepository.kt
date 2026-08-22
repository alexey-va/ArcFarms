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
        }
    }
}

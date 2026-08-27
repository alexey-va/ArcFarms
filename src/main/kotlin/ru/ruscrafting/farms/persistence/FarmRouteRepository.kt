package ru.ruscrafting.farms.persistence

import ru.ruscrafting.farms.domain.DomainIdentifiers
import ru.ruscrafting.farms.domain.FarmRouteState
import java.nio.file.Path

class FarmRouteRepository(dataRoot: Path) : AutoCloseable {
    private val store = AtomicJsonStore(
        path = dataRoot.resolve("data/farm-routes.json"),
        type = FarmRouteState::class.java,
        emptyValue = ::FarmRouteState,
        validate = ::validate,
    )

    fun load(): FarmRouteState = store.load()

    fun saveBlocking(value: FarmRouteState) = store.saveBlocking(value)

    override fun close() = store.close()

    private fun validate(value: FarmRouteState) {
        require(value.schemaVersion == FarmRouteState.SCHEMA_VERSION) { "Unsupported farm route schema" }
        require(value.routes.size <= 256) { "Farm route store contains too many zones" }
        value.routes.forEach { (zoneId, route) ->
            require(DomainIdentifiers.isOrder(zoneId)) { "Invalid farm route zone: $zoneId" }
            require(route.points.size in 2..512) { "Farm route $zoneId has an invalid point count" }
            require(route.points.all { point ->
                point.world.isNotBlank() &&
                    point.x.isFinite() && point.y.isFinite() && point.z.isFinite() &&
                    point.yaw.isFinite() && point.pitch.isFinite()
            }) { "Farm route $zoneId contains invalid coordinates" }
            require(route.points.map { it.world }.distinct().size == 1) { "Farm route $zoneId crosses worlds" }
            require(route.points.zipWithNext().all { (from, to) ->
                val dx = from.x - to.x
                val dy = from.y - to.y
                val dz = from.z - to.z
                dx * dx + dy * dy + dz * dz <= 100.0
            }) { "Farm route $zoneId contains a gap above 10 blocks" }
        }
    }
}

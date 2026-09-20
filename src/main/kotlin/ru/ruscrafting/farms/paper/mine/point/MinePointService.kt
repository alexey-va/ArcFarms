package ru.ruscrafting.farms.paper.mine.point

import ru.ruscrafting.farms.domain.MineLocationKeys
import ru.ruscrafting.farms.domain.MineLocationPosition
import ru.ruscrafting.farms.domain.MineLocations
import ru.ruscrafting.farms.domain.MineZoneLocations
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.persistence.MineLocationRepository
import java.util.concurrent.CompletableFuture

/** In-memory snapshot owner for explicit mine point overrides. */
internal class MinePointService(
    private val repository: MineLocationRepository,
    private val defaults: MinePointDefaults = MinePointDefaults(),
) {
    @Volatile
    private var locations = MineLocations()

    @Synchronized
    fun load(): MineLocations = repository.load().also { locations = it }

    fun snapshot(): MineLocations = locations

    fun zone(zoneId: String, worldName: String? = null): MineZoneLocations? {
        val persisted = locations.zones[zoneId]
        val fallback = worldName?.let { defaults.zone(zoneId, it) }
        return when {
            persisted == null -> fallback
            fallback == null -> persisted
            else -> MineZoneLocations(
                workshop = fallback.workshop + persisted.workshop,
                workings = fallback.workings + persisted.workings,
                expeditions = fallback.expeditions + persisted.expeditions,
            )
        }
    }

    fun configured(zoneId: String, kind: String, worldName: String? = null): MineLocationPosition? =
        zone(zoneId, worldName)?.point(kind)

    fun overridden(zoneId: String, kind: String): Boolean = locations.zones[zoneId]?.point(kind) != null

    /** Effective workshop markers for a live runtime; empty means the map supplied none. */
    fun points(runtime: MineRuntime): Map<String, MineLocationPosition> =
        zone(runtime.settings.id, runtime.region.world.name)?.workshop.orEmpty()

    /** Effective side-working entrances for a live runtime; no procedural fallback is applied. */
    fun workingPlacements(runtime: MineRuntime): Map<String, MineLocationPosition> =
        zone(runtime.settings.id, runtime.region.world.name)?.workings.orEmpty()

    @Synchronized
    fun save(zoneId: String, kind: String, position: MineLocationPosition): CompletableFuture<Unit> {
        require(MineLocationKeys.isKnown(kind)) { "Unknown mine point: $kind" }
        val previous = locations
        val zone = (previous.zones[zoneId] ?: MineZoneLocations()).with(kind, position)
        val candidate = previous.copy(zones = previous.zones + (zoneId to zone))
        locations = candidate
        return repository.saveAsync(candidate).whenComplete { _, failure ->
            if (failure != null) synchronized(this) {
                // Do not erase a newer admin edit when an older write fails.
                if (locations == candidate) locations = previous
            }
        }
    }

    @Synchronized
    fun clear(zoneId: String, kind: String): CompletableFuture<Boolean> {
        require(MineLocationKeys.isKnown(kind)) { "Unknown mine point: $kind" }
        val previous = locations
        val candidate = previous.without(zoneId, kind)
        if (candidate == previous) return CompletableFuture.completedFuture(false)
        locations = candidate
        return repository.saveAsync(candidate).thenApply { true }.whenComplete { _, failure ->
            if (failure != null) synchronized(this) {
                if (locations == candidate) locations = previous
            }
        }
    }
}

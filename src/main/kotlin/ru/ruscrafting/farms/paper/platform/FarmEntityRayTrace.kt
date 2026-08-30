package ru.ruscrafting.farms.paper.platform

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector

/** Owns Paper's entity ray trace so gameplay can be exercised against deterministic test worlds. */
internal fun interface FarmEntityRayTrace {
    fun trace(
        start: Location,
        direction: Vector,
        maxDistance: Double,
        raySize: Double,
        filter: (Entity) -> Boolean,
    ): RayTraceResult?
}

internal object PaperFarmEntityRayTrace : FarmEntityRayTrace {
    override fun trace(
        start: Location,
        direction: Vector,
        maxDistance: Double,
        raySize: Double,
        filter: (Entity) -> Boolean,
    ): RayTraceResult? = start.world.rayTraceEntities(start, direction, maxDistance, raySize, filter)
}

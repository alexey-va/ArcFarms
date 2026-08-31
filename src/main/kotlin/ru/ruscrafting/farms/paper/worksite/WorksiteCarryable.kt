package ru.ruscrafting.farms.paper.worksite

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player

/** Shared spatial rules for physical worksite objects that can be clicked, walked onto, and carried. */
internal object WorksiteCarryable {
    fun carriedLocation(
        player: Player,
        forwardOffset: Double,
        verticalOffset: Double,
        localX: Double = 0.0,
        localZ: Double = 0.0,
    ): Location {
        val direction = player.location.direction.setY(0.0)
        if (direction.lengthSquared() > 0.001) direction.normalize().multiply(forwardOffset)
        return player.location.clone().add(direction).add(localX, verticalOffset, localZ)
    }

    fun <T> nearest(
        playerLocation: Location,
        candidates: Sequence<Pair<T, Location>>,
        radius: Double,
    ): T? {
        val radiusSquared = radius * radius
        return candidates
            .filter { (_, location) -> location.world === playerLocation.world }
            .map { (value, location) -> value to playerLocation.distanceSquared(location) }
            .filter { (_, distanceSquared) -> distanceSquared <= radiusSquared }
            .minByOrNull { (_, distanceSquared) -> distanceSquared }
            ?.first
    }

    fun withinInteractionReach(player: Player, entity: Entity, maxDistance: Double): Boolean {
        val eye = player.eyeLocation
        val bounds = if (entity is Interaction) {
            val halfWidth = entity.interactionWidth / 2.0
            doubleArrayOf(
                entity.location.x - halfWidth,
                entity.location.y,
                entity.location.z - halfWidth,
                entity.location.x + halfWidth,
                entity.location.y + entity.interactionHeight,
                entity.location.z + halfWidth,
            )
        } else {
            entity.boundingBox.let { doubleArrayOf(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ) }
        }
        val dx = eye.x - eye.x.coerceIn(bounds[0], bounds[3])
        val dy = eye.y - eye.y.coerceIn(bounds[1], bounds[4])
        val dz = eye.z - eye.z.coerceIn(bounds[2], bounds[5])
        return dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance
    }
}

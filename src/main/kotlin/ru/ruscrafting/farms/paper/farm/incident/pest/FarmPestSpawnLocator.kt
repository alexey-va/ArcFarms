package ru.ruscrafting.farms.paper.farm.incident.pest

import org.bukkit.Location
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy
import java.util.random.RandomGenerator

/** Finds a loaded, walkable pest spawn without loading chunks or leaving the farm region. */
internal class FarmPestSpawnLocator(
    private val random: RandomGenerator,
    private val maximumAttempts: Int = 24,
) {
    fun find(region: ActivityRegion, anchor: Location, radius: Int): Location? {
        repeat(maximumAttempts) {
            val x = anchor.blockX + random.nextInt(-radius, radius + 1)
            val z = anchor.blockZ + random.nextInt(-radius, radius + 1)
            if (!region.world.isChunkLoaded(x shr 4, z shr 4)) return@repeat
            for (y in anchor.blockY - 2..anchor.blockY + 2) {
                val feet = region.world.getBlockAt(x, y, z)
                val head = region.world.getBlockAt(x, y + 1, z)
                val floor = region.world.getBlockAt(x, y - 1, z)
                val candidate = Location(region.world, x + 0.5, y.toDouble(), z + 0.5)
                if (
                    region.contains(candidate) && feet.isPassable && head.isPassable && floor.type.isSolid &&
                    FarmSurfacePolicy.isSurfaceSpawn(candidate)
                ) return candidate
            }
        }
        return null
    }
}

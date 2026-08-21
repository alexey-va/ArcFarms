package ru.ruscrafting.farms.paper

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.ZoneReference

interface ActivityRegion {
    val world: World
    val label: String
    val bounds: CuboidBounds

    fun contains(location: Location): Boolean =
        location.world == world && bounds.contains(location.blockX, location.blockY, location.blockZ)
}
class CuboidActivityRegion(
    override val world: World,
    override val label: String,
    override val bounds: CuboidBounds,
) : ActivityRegion

class WorldGuardActivityRegion(
    override val world: World,
    override val label: String,
    override val bounds: CuboidBounds,
    private val containsCoordinates: (Int, Int, Int) -> Boolean,
) : ActivityRegion {
    override fun contains(location: Location): Boolean =
        location.world == world && containsCoordinates(location.blockX, location.blockY, location.blockZ)
}

interface RegionGateway {
    fun resolve(reference: ZoneReference): ActivityRegion?
}

class CuboidRegionGateway : RegionGateway {
    override fun resolve(reference: ZoneReference): ActivityRegion? {
        val world = Bukkit.getWorld(reference.world) ?: return null
        val bounds = reference.bounds ?: return null
        return CuboidActivityRegion(world, "${reference.world}:${bounds.minX},${bounds.minY},${bounds.minZ}", bounds)
    }
}

class WorldGuardRegionGateway : RegionGateway {
    private val cuboids = CuboidRegionGateway()

    override fun resolve(reference: ZoneReference): ActivityRegion? {
        reference.bounds?.let { return cuboids.resolve(reference) }
        val world = Bukkit.getWorld(reference.world) ?: return null
        val regionId = reference.region ?: return null
        val manager = WorldGuard.getInstance().platform.regionContainer.get(BukkitAdapter.adapt(world)) ?: return null
        val region = manager.getRegion(regionId) ?: return null
        val min = region.minimumPoint
        val max = region.maximumPoint
        return WorldGuardActivityRegion(
            world = world,
            label = "${reference.world}:$regionId",
            bounds = CuboidBounds(min.x(), min.y(), min.z(), max.x(), max.y(), max.z()),
            containsCoordinates = region::contains,
        )
    }
}

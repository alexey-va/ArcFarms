package ru.ruscrafting.farms.paper.mine.incident.creature

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Mob
import ru.ruscrafting.farms.paper.mine.MineRuntime
import kotlin.math.abs

/** Paper pathfinding is isolated because MockBukkit does not implement native paths/goals. */
internal interface MineCreatureNavigation {
    fun initialize(mob: Mob)
    fun stop(mob: Mob)
    fun chase(mob: Mob, destination: Location, runtime: MineRuntime, floorY: Int)
}

internal object PaperMineCreatureNavigation : MineCreatureNavigation {
    override fun initialize(mob: Mob) {
        Bukkit.getMobGoals().removeAllGoals(mob)
        mob.target = null
        mob.isCollidable = false
        mob.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        mob.pathfinder.setCanOpenDoors(false)
        mob.pathfinder.setCanPassDoors(false)
        mob.pathfinder.setCanFloat(false)
    }

    override fun stop(mob: Mob) { mob.pathfinder.stopPathfinding(); mob.target = null }

    override fun chase(mob: Mob, destination: Location, runtime: MineRuntime, floorY: Int) {
        // A location path also follows creative players; vanilla target goals deliberately ignore them.
        val path = mob.pathfinder.findPath(destination)
        if (path == null || path.points.isEmpty() || path.points.any { !safeStep(runtime, it, floorY) }) {
            stop(mob)
            return
        }
        mob.pathfinder.moveTo(path, 1.25)
    }

    internal fun safeStep(runtime: MineRuntime, location: Location, floorY: Int): Boolean {
        if (location.world !== runtime.region.world || (location.y < floorY - 0.25 || location.y > floorY + 3.25) || !runtime.region.contains(location)) return false
        return listOf(-0.32, 0.32).all { dx -> listOf(-0.32, 0.32).all { dz ->
            val foot = location.clone().add(dx, 0.0, dz)
            val world = runtime.region.world
            world.isChunkLoaded(foot.blockX shr 4, foot.blockZ shr 4) &&
                world.getBlockAt(foot.blockX, kotlin.math.floor(location.y - 0.05).toInt(), foot.blockZ).type.isSolid
        } }
    }
}

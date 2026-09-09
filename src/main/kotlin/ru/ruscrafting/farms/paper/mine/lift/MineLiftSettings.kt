package ru.ruscrafting.farms.paper.mine.lift

import org.bukkit.Location
import org.bukkit.World
import ru.arc.config.Config
import java.nio.file.Path

internal data class LiftPoint(val x: Double, val y: Double, val z: Double, val yaw: Float = 0f) {
    fun location(world: World) = Location(world, x, y, z, yaw, 0f)
}

internal data class MineLiftFloor(val id: String, val y: Double, val exit: LiftPoint, val panel: LiftPoint)

internal data class MineLiftSettings(
    val world: String,
    val x: Double,
    val z: Double,
    val width: Double,
    val depth: Double,
    val speed: Double,
    val floors: List<MineLiftFloor>,
) {
    fun contains(point: Location): Boolean = point.world.name == world &&
        kotlin.math.abs(point.x - x) < width / 2 + 0.3 && kotlin.math.abs(point.z - z) < depth / 2 + 0.3 &&
        point.y in (floors.minOf { it.y } - 2)..(floors.maxOf { it.y } + 4)

    companion object {
        fun load(root: Path): MineLiftSettings? {
            val config = Config(root, "modules/mine-lift.yml")
            if (config.booleanOrNull("enabled") != true) return null
            fun number(path: String): Double = requireNotNull(config.doubleOrNull(path)) { "Missing mine lift $path" }
                .also { require(it.isFinite()) { "Non-finite mine lift $path" } }
            fun point(path: String) = LiftPoint(number("$path.x"), number("$path.y"), number("$path.z"),
                (config.doubleOrNull("$path.yaw") ?: 0.0).toFloat().also { require(it.isFinite()) })
            val ids = config.stringList("floor-order")
            require(ids.size in 2..8 && ids.distinct().size == ids.size && ids.all { it.matches(Regex("[a-z][a-z0-9_]{0,31}")) })
            val floors = ids.map { id -> MineLiftFloor(id, number("floors.$id.y"), point("floors.$id.exit"), point("floors.$id.panel")) }
            require(floors.map { it.y }.distinct().size == floors.size)
            require(floors.zipWithNext().all { (a, b) -> a.y - b.y >= 4 }) { "Lift floors must descend by at least four blocks" }
            val width = number("cabin.width").also { require(it in 1.8..3.5) }
            val depth = number("cabin.depth").also { require(it in 1.8..3.5) }
            return MineLiftSettings(requireNotNull(config.stringOrNull("world")), number("cabin.x"), number("cabin.z"),
                width, depth, number("speed").also { require(it in 1.0..8.0) }, floors)
        }
    }
}

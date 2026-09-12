package ru.ruscrafting.farms.paper.mine.lift

import org.bukkit.Location
import org.bukkit.World
import ru.arc.config.Config
import ru.ruscrafting.farms.domain.mine.lift.MineLiftMotion
import java.nio.file.Path

internal data class LiftPoint(val x: Double, val y: Double, val z: Double, val yaw: Float = 0f) {
    fun location(world: World) = Location(world, x, y, z, yaw, 0f)
}

/** Cardinal side of the cabin that faces a floor landing. */
internal enum class MineLiftDoorSide { NORTH, EAST, SOUTH, WEST }

internal data class MineLiftFloor(val id: String, val y: Double, val exit: LiftPoint, val panel: LiftPoint)

internal data class MineLiftSettings(
    val id: String,
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

    /** The cabin is an entity scene, so this is deliberately a soft reach bound around its live Y. */
    fun cabinContains(point: Location, cabinY: Double): Boolean = point.world.name == world &&
        kotlin.math.abs(point.x - x) <= width / 2 + 3.0 &&
        kotlin.math.abs(point.z - z) <= depth / 2 + 3.0 &&
        kotlin.math.abs(point.y - cabinY) <= 3.0

    fun openingSide(index: Int): MineLiftDoorSide {
        require(index in floors.indices)
        val exit = floors[index].exit
        val dx = exit.x - x
        val dz = exit.z - z
        // Exits are authored on a cardinal landing. For a malformed diagonal/center point,
        // preserve a deterministic side instead of rotating the scene unpredictably.
        return when {
            kotlin.math.abs(dx) > kotlin.math.abs(dz) -> if (dx < 0.0) MineLiftDoorSide.WEST else MineLiftDoorSide.EAST
            kotlin.math.abs(dz) > 0.0 -> if (dz < 0.0) MineLiftDoorSide.NORTH else MineLiftDoorSide.SOUTH
            dx < 0.0 -> MineLiftDoorSide.WEST
            else -> MineLiftDoorSide.EAST
        }
    }

    fun overlaps(other: MineLiftSettings): Boolean {
        if (world != other.world) return false
        fun overlap(leftMin: Double, leftMax: Double, rightMin: Double, rightMax: Double) =
            leftMin < rightMax && rightMin < leftMax
        return overlap(x - width / 2, x + width / 2, other.x - other.width / 2, other.x + other.width / 2) &&
            overlap(z - depth / 2, z + depth / 2, other.z - other.depth / 2, other.z + other.depth / 2) &&
            overlap(floors.minOf { it.y }, floors.maxOf { it.y } + 2.8,
                other.floors.minOf { it.y }, other.floors.maxOf { it.y } + 2.8)
    }

    companion object {
        private val ID_PATTERN = Regex("[a-z][a-z0-9_]{0,31}")

        fun load(root: Path): MineLiftSettings? = loadAll(root).firstOrNull { it.id == "main" }

        fun loadAll(root: Path, report: (String, Throwable) -> Unit = { _, _ -> }): List<MineLiftSettings> {
            val config = Config(root, "modules/mine-lift.yml")
            val candidates = mutableListOf<MineLiftSettings>()

            fun add(id: String, prefix: String, enabled: Boolean) {
                if (!enabled) return
                runCatching { parse(config, id, prefix) }
                    .onSuccess(candidates::add)
                    .onFailure { report(id, it) }
            }

            add("main", "", config.booleanOrNull("enabled") == true)
            config.keys("additional-lifts").sorted().forEach { id ->
                if (id == "main" || id == "status" || !ID_PATTERN.matches(id)) {
                    report(id, IllegalArgumentException("Mine lift id must match ${ID_PATTERN.pattern} and cannot be main or status"))
                } else {
                    val prefix = "additional-lifts.$id"
                    add(id, prefix, config.booleanOrNull("$prefix.enabled") == true)
                }
            }

            val accepted = mutableListOf<MineLiftSettings>()
            candidates.forEach { candidate ->
                val conflict = accepted.firstOrNull(candidate::overlaps)
                if (conflict == null) {
                    accepted += candidate
                } else {
                    report(candidate.id, IllegalArgumentException("overlaps configured mine lift ${conflict.id}"))
                }
            }
            return accepted
        }

        private fun parse(config: Config, id: String, prefix: String): MineLiftSettings {
            require(id.matches(ID_PATTERN) && id != "status") { "Invalid or reserved mine lift id $id" }
            fun path(value: String) = if (prefix.isBlank()) value else "$prefix.$value"
            fun number(name: String): Double = requireNotNull(config.doubleOrNull(path(name))) { "Missing mine lift $id $name" }
                .also { require(it.isFinite()) { "Non-finite mine lift $id $name" } }
            fun point(name: String) = LiftPoint(number("$name.x"), number("$name.y"), number("$name.z"),
                (config.doubleOrNull(path("$name.yaw")) ?: 0.0).toFloat().also { require(it.isFinite()) })
            val ids = config.stringList(path("floor-order"))
            require(ids.size in 2..8 && ids.distinct().size == ids.size && ids.all { it.matches(ID_PATTERN) })
            val floors = ids.map { floorId ->
                MineLiftFloor(floorId, number("floors.$floorId.y"), point("floors.$floorId.exit"), point("floors.$floorId.panel"))
            }
            require(floors.map { it.y }.distinct().size == floors.size)
            require(floors.zipWithNext().all { (a, b) -> a.y - b.y >= 4 }) { "Lift floors must descend by at least four blocks" }
            val width = number("cabin.width").also { require(it in 1.8..6.5) }
            val depth = number("cabin.depth").also { require(it in 1.8..6.5) }
            return MineLiftSettings(id, requireNotNull(config.stringOrNull(path("world"))), number("cabin.x"), number("cabin.z"),
                width, depth, number("speed").also { require(it in 1.0..MineLiftMotion.MAX_SPEED) }, floors)
        }
    }
}

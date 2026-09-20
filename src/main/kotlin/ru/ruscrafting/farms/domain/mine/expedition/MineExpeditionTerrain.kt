package ru.ruscrafting.farms.domain.mine.expedition

import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Small, platform-free voxel builder shared by the three expedition layouts.
 * The map is sparse on purpose: a missing cell is the solid natural stone
 * outside the authored air pockets.
 */
internal class MineExpeditionBuilder(
    val kind: MineExpeditionKind,
    val seed: Long,
    val bounds: ExpeditionBounds,
) {
    val blocks: LinkedHashMap<ExpeditionPoint, String> = linkedMapOf()
    val stations: LinkedHashMap<String, ExpeditionPoint> = linkedMapOf()
    val routes: LinkedHashMap<String, List<ExpeditionPoint>> = linkedMapOf()
    val walkingRoutes: MutableList<List<ExpeditionPoint>> = mutableListOf()
    private val reserved = linkedSetOf<ExpeditionPoint>()

    fun put(point: ExpeditionPoint, block: String) {
        require(bounds.contains(point)) { "Point $point is outside $bounds" }
        blocks[point] = block
    }

    fun solid(point: ExpeditionPoint, block: String) {
        if (point !in reserved) put(point, block)
    }

    /** Reserve a structural support cell so later decoration cannot replace it. */
    fun support(point: ExpeditionPoint, block: String) {
        require(bounds.contains(point)) { "Support $point is outside $bounds" }
        reserved += point
        put(point, block)
    }

    fun air(point: ExpeditionPoint) {
        if (point !in reserved) put(point, AIR)
    }

    /** Carves a cell that belongs to a player lane or a moving machine sweep. */
    fun reserveAir(point: ExpeditionPoint) {
        if (point !in reserved) {
            reserved += point
            put(point, AIR)
        }
    }

    fun station(id: String, point: ExpeditionPoint) {
        require(id !in stations) { "Duplicate station $id" }
        require(bounds.contains(point)) { "Station $id is outside $bounds" }
        stations[id] = point
        // Crank and pickup work uses a small interaction radius; keep the
        // entire 5x5 deck open so the player can approach from any side.
        reservePlayer(point, width = 2, height = 3)
    }

    /** Reserve a continuous three-wide, three-high walking lane. */
    fun reservePlayer(point: ExpeditionPoint, width: Int = 1, height: Int = 3) {
        for (dx in -width..width) for (dz in -width..width) {
            for (dy in 0 until height) reserveAir(point.offset(dx, dy, dz))
            val floor = point.offset(dx, -1, dz)
            support(floor, floorMaterial(floor))
        }
    }

    /** A route point is a feet coordinate; adjoining points are one block apart. */
    fun walk(points: List<ExpeditionPoint>, width: Int = 1): List<ExpeditionPoint> {
        require(points.isNotEmpty()) { "Walking route must not be empty" }
        val result = ArrayList<ExpeditionPoint>()
        points.zipWithNext().forEach { (from, to) ->
            val segment = line(from, to)
            if (result.isEmpty()) result += segment else result += segment.drop(1)
        }
        if (points.size == 1) result += points
        result.forEach { reservePlayer(it, width = width, height = 3) }
        walkingRoutes += result.toList()
        return result
    }

    fun route(name: String, points: List<ExpeditionPoint>, walking: Boolean = true) {
        require(name !in routes) { "Duplicate route $name" }
        require(points.isNotEmpty()) { "Route $name must not be empty" }
        routes[name] = points
        if (walking) walk(points)
    }

    fun corridor(from: ExpeditionPoint, to: ExpeditionPoint, width: Int = 1): List<ExpeditionPoint> =
        walk(line(from, to), width)

    fun chamber(center: ExpeditionPoint, radiusX: Int, radiusY: Int, radiusZ: Int, floorY: Int = center.y) {
        val minX = center.x - radiusX
        val maxX = center.x + radiusX
        val minY = max(bounds.min.y, center.y - radiusY)
        val maxY = min(bounds.max.y, center.y + radiusY)
        val minZ = center.z - radiusZ
        val maxZ = center.z + radiusZ
        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val nx = (x - center.x).toDouble() / max(1, radiusX)
            val ny = (y - center.y).toDouble() / max(1, radiusY)
            val nz = (z - center.z).toDouble() / max(1, radiusZ)
            val roughness = WorksiteCoherentNoise.sample(seed, x * 0.075, y * 0.07, z * 0.075) * 0.24 +
                WorksiteCoherentNoise.sample(seed + 17, x * 0.27, y * 0.23, z * 0.27) * 0.08
            if (nx * nx + ny * ny + nz * nz <= 1.0 + roughness) air(ExpeditionPoint(x, y, z))
        }
        for (x in center.x - radiusX..center.x + radiusX) for (z in center.z - radiusZ..center.z + radiusZ) {
            if (abs(x - center.x) <= radiusX && abs(z - center.z) <= radiusZ) {
                val relief = floor(WorksiteCoherentNoise.sample(seed + 37, x * 0.11, floorY * 0.05, z * 0.11) * 2.4).toInt()
                val ground = (floorY - 1 + relief).coerceIn(bounds.min.y, bounds.max.y)
                for (y in minY..ground) solid(ExpeditionPoint(x, y, z), floorMaterial(ExpeditionPoint(x, y, z)))
            }
        }
    }

    /** Carves an irregular vertical shaft while leaving geological shoulders. */
    fun shaft(centerX: Int, centerZ: Int, minY: Int, maxY: Int, radiusX: Int, radiusZ: Int, salt: Long) {
        for (y in minY..maxY) {
            val driftX = WorksiteCoherentNoise.sample(seed + salt, 0.0, y * 0.075, 2.0) * 2.0
            val driftZ = WorksiteCoherentNoise.sample(seed + salt + 7L, 3.0, y * 0.065, 0.0) * 2.0
            val rx = radiusX + floor(WorksiteCoherentNoise.sample(seed + salt, y * 0.11, 4.0, 1.0) * 1.5).toInt()
            val rz = radiusZ + floor(WorksiteCoherentNoise.sample(seed + salt + 11L, 2.0, y * 0.09, 5.0) * 1.5).toInt()
            for (x in centerX - radiusX - 2..centerX + radiusX + 2) for (z in centerZ - radiusZ - 2..centerZ + radiusZ + 2) {
                val nx = (x - centerX - driftX) / max(1, rx).toDouble()
                val nz = (z - centerZ - driftZ) / max(1, rz).toDouble()
                val edge = WorksiteCoherentNoise.sample(seed + salt, x * 0.13, y * 0.13, z * 0.13) * 0.12
                if (nx * nx + nz * nz <= 1.0 + edge) air(ExpeditionPoint(x, y, z))
            }
        }
    }

    /** Geological shoulders, strata and sparse ore patches around an opening. */
    fun strataAround(center: ExpeditionPoint, radiusX: Int, radiusY: Int, radiusZ: Int, salt: Long) {
        for (x in center.x - radiusX..center.x + radiusX) for (y in center.y - radiusY..center.y + radiusY) {
            for (z in center.z - radiusZ..center.z + radiusZ) {
                if (!bounds.contains(ExpeditionPoint(x, y, z))) continue
                val point = ExpeditionPoint(x, y, z)
                if (point in reserved || blocks[point] == AIR) continue
                val n = WorksiteCoherentNoise.sample(seed + salt, x * 0.08, y * 0.08, z * 0.08)
                val score = WorksiteDeterministicSeed.positionScore(seed + salt, "expedition_strata", x, y, z)
                val material = when {
                    n > 0.60 && y < center.y + 4 -> "minecraft:deepslate"
                    n < -0.63 -> "minecraft:tuff"
                    score % 29L == 0L -> "minecraft:iron_ore"
                    score % 37L == 0L -> "minecraft:copper_ore"
                    score % 43L == 0L -> "minecraft:coal_ore"
                    n > 0.32 -> "minecraft:andesite"
                    else -> "minecraft:stone"
                }
                solid(point, material)
            }
        }
    }

    fun stalactite(anchor: ExpeditionPoint, length: Int, material: String = "minecraft:dripstone_block") {
        val ceiling = (0..10).map { anchor.offset(dy = it) }.firstOrNull { point ->
            bounds.contains(point) && point !in reserved && blocks[point] != AIR && blocks[point.offset(dy = -1)] == AIR
        } ?: return
        for (step in 0 until length) {
            val point = ceiling.offset(dy = -step - 1)
            if (!bounds.contains(point) || point in reserved || blocks[point] != AIR) break
            put(point, material)
        }
    }

    fun outcrop(anchor: ExpeditionPoint, length: Int, axis: Char, material: String) {
        for (step in 0 until length) {
            val point = when (axis) {
                'x' -> anchor.offset(dx = step)
                'y' -> anchor.offset(dy = step)
                else -> anchor.offset(dz = step)
            }
            if (!bounds.contains(point) || point in reserved) break
            put(point, material)
        }
    }

    fun light(point: ExpeditionPoint, wall: String = "east") {
        val state = "minecraft:lantern[hanging=false,waterlogged=false]"
        if (point !in reserved && bounds.contains(point)) put(point, state)
        val bracket = when (wall) {
            "west" -> point.offset(dx = 1)
            "north" -> point.offset(dz = 1)
            "south" -> point.offset(dz = -1)
            else -> point.offset(dx = -1)
        }
        if (bracket !in reserved && bounds.contains(bracket)) put(bracket, "minecraft:iron_bars[east=true,north=false,south=false,west=false,waterlogged=false]")
    }

    private fun illuminateRoutes() {
        val lights = mutableListOf<ExpeditionPoint>()
        (walkingRoutes.flatten() + stations.values).distinct().forEach { feet ->
            if (kind == MineExpeditionKind.LAST_DESCENT && feet.x in -3..3 && feet.z in -2..2) return@forEach
            if (lights.any { abs(it.x - feet.x) + abs(it.z - feet.z) < 6 && it.y == feet.y }) return@forEach
            val lamp = feet.offset(dy = if (kind == MineExpeditionKind.DRILLING_ARK) 7 else 3)
            if (bounds.contains(lamp)) {
                put(lamp, "minecraft:light[level=${if (kind == MineExpeditionKind.DRILLING_ARK) 15 else 14},waterlogged=false]")
                lights += feet
            }
        }
    }

    fun finish(): MineExpeditionPlan {
        illuminateRoutes()
        return MineExpeditionPlan(
        kind = kind,
        seed = seed,
        bounds = bounds,
        blocks = blocks.toMap(),
        stations = stations.toMap(),
        routes = routes.mapValues { it.value.toList() },
        walkingRoutes = walkingRoutes.map { it.toList() },
        )
    }

    /** Cardinal x-then-y-then-z interpolation for machine and walking paths. */
    fun line(from: ExpeditionPoint, to: ExpeditionPoint): List<ExpeditionPoint> {
        require(bounds.contains(from) && bounds.contains(to)) { "Line endpoints outside bounds" }
        val result = mutableListOf(from)
        var current = from
        fun advance(axis: Char, target: Int) {
            while (when (axis) {
                'x' -> current.x != target
                'y' -> current.y != target
                else -> current.z != target
            }) {
                current = when (axis) {
                    'x' -> current.offset(dx = if (target > current.x) 1 else -1)
                    'y' -> current.offset(dy = if (target > current.y) 1 else -1)
                    else -> current.offset(dz = if (target > current.z) 1 else -1)
                }
                result += current
            }
        }
        advance('x', to.x)
        advance('y', to.y)
        advance('z', to.z)
        return result
    }

    private fun floorMaterial(point: ExpeditionPoint): String {
        val n = WorksiteCoherentNoise.sample(seed + 89L, point.x * 0.09, point.y * 0.08, point.z * 0.09)
        return when {
            n > 0.42 -> "minecraft:tuff"
            n < -0.36 -> "minecraft:deepslate"
            else -> "minecraft:stone"
        }
    }

    companion object {
        const val AIR = "minecraft:air"
    }
}

internal fun blockIfClear(builder: MineExpeditionBuilder, point: ExpeditionPoint, block: String) {
    if (builder.bounds.contains(point)) builder.solid(point, block)
}

internal fun ring(builder: MineExpeditionBuilder, center: ExpeditionPoint, radius: Int, block: String, vertical: Boolean = false) {
    val steps = max(16, radius * 8)
    for (index in 0 until steps) {
        val angle = index.toDouble() / steps * Math.PI * 2.0
        val point = if (vertical) {
            center.offset(dx = floor(cos(angle) * radius).toInt(), dy = floor(sin(angle) * radius).toInt())
        } else {
            center.offset(dx = floor(cos(angle) * radius).toInt(), dz = floor(sin(angle) * radius).toInt())
        }
        blockIfClear(builder, point, block)
    }
}

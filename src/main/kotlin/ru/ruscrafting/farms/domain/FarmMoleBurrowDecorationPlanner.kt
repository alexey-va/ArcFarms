package ru.ruscrafting.farms.domain

import kotlin.math.abs

data class FarmMoleDecoration(
    val position: FarmMolePassage,
    val yOffset: Int,
    val material: String,
)

/** Pure visual planner for a layered, navigable temporary cave. */
object FarmMoleBurrowDecorationPlanner {
    fun plan(
        openFloor: Set<FarmMolePassage>,
        start: FarmMolePassage,
        lair: FarmMolePassage,
        chambers: Set<FarmMolePassage>,
        tunnelHeight: Int,
        seed: Long,
        accentPercent: Int,
    ): List<FarmMoleDecoration> {
        require(tunnelHeight in 2..4)
        require(accentPercent in 0..60)
        if (openFloor.isEmpty()) return emptyList()
        val result = linkedMapOf<Triple<Int, Int, Int>, FarmMoleDecoration>()
        fun place(position: FarmMolePassage, yOffset: Int, material: String) {
            result[Triple(position.x, yOffset, position.z)] = FarmMoleDecoration(position, yOffset, material)
        }

        openFloor.sortedWith(compareBy(FarmMolePassage::x, FarmMolePassage::z)).forEach { point ->
            val mixed = mix(seed, point.x, point.z)
            val floorMaterial = when {
                manhattan(point, lair) <= 2 -> LAIR_FLOOR[Math.floorMod(mixed, LAIR_FLOOR.size.toLong()).toInt()]
                manhattan(point, start) <= 2 -> ENTRANCE_FLOOR[Math.floorMod(mixed, ENTRANCE_FLOOR.size.toLong()).toInt()]
                else -> PASSAGE_FLOOR[Math.floorMod(mixed, PASSAGE_FLOOR.size.toLong()).toInt()]
            }
            place(point, -1, floorMaterial)
            place(point, tunnelHeight, CEILING[Math.floorMod(mixed ushr 9, CEILING.size.toLong()).toInt()])

            CARDINALS.forEachIndexed { face, (dx, dz) ->
                val wall = FarmMolePassage(point.x + dx, point.z + dz)
                if (wall in openFloor) return@forEachIndexed
                repeat(tunnelHeight) { y ->
                    val wallHash = mix(mixed, face, y)
                    place(wall, y, WALL[Math.floorMod(wallHash, WALL.size.toLong()).toInt()])
                }
            }

            if (tunnelHeight >= 3 && Math.floorMod(mixed ushr 21, 100L) < accentPercent) {
                place(point, tunnelHeight - 1, "HANGING_ROOTS")
            }
        }

        chambers.sortedWith(compareBy(FarmMolePassage::x, FarmMolePassage::z)).forEachIndexed { index, center ->
            val roomSeed = mix(seed xor index.toLong(), center.x, center.z)
            val corner = ROOM_CORNERS[Math.floorMod(roomSeed, ROOM_CORNERS.size.toLong()).toInt()]
            place(FarmMolePassage(center.x + corner.first, center.z + corner.second), 0, ROOM_ACCENTS[index % ROOM_ACCENTS.size])
            if (tunnelHeight >= 3) place(center, tunnelHeight - 1, "SPORE_BLOSSOM")
            val crystalWall = FarmMolePassage(center.x + corner.first * 2, center.z + corner.second * 2)
            place(crystalWall, 1.coerceAtMost(tunnelHeight - 1), if (index % 2 == 0) "AMETHYST_BLOCK" else "CALCITE")
        }

        LAIR_CORNERS.forEachIndexed { index, (dx, dz) ->
            val point = FarmMolePassage(lair.x + dx, lair.z + dz)
            place(point, 0, if (index % 2 == 0) "MOSS_CARPET" else "BROWN_MUSHROOM")
        }
        return result.values.toList()
    }

    private fun manhattan(first: FarmMolePassage, second: FarmMolePassage): Int =
        abs(first.x - second.x) + abs(first.z - second.z)

    private fun mix(seed: Long, x: Int, z: Int): Long {
        var value = seed xor (x.toLong() * -7046029254386353131L) xor (z.toLong() * -7723592293110705685L)
        value = (value xor (value ushr 30)) * -4658895280553007687L
        return value xor (value ushr 27)
    }

    private val CARDINALS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val ROOM_CORNERS = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
    private val LAIR_CORNERS = listOf(-2 to -2, 2 to -2, 2 to 2, -2 to 2)
    private val ENTRANCE_FLOOR = listOf("ROOTED_DIRT", "COARSE_DIRT", "PACKED_MUD")
    private val PASSAGE_FLOOR = listOf("COARSE_DIRT", "ROOTED_DIRT", "MUD", "PACKED_MUD", "TUFF")
    private val LAIR_FLOOR = listOf("MUD", "PACKED_MUD", "ROOTED_DIRT", "MOSS_BLOCK")
    private val WALL = listOf("TUFF", "DRIPSTONE_BLOCK", "DEEPSLATE", "ROOTED_DIRT", "PACKED_MUD")
    private val CEILING = listOf("TUFF", "DRIPSTONE_BLOCK", "DEEPSLATE", "MOSS_BLOCK")
    private val ROOM_ACCENTS = listOf("BROWN_MUSHROOM", "RED_MUSHROOM", "POINTED_DRIPSTONE")
}

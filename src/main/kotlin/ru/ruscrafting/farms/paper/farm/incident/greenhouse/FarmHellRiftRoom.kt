package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import org.bukkit.Material
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowMarker

/** Native blocks for the underground greenhouse hall. The journal owns every placement and restoration. */
internal object FarmHellRiftRoom {
    const val HALF_WIDTH = 10
    const val HALF_LENGTH = 12
    // Eight vertical layers keep every possible 16x16 chunk slice under the
    // journal's 2,048-record per-chunk cap (21*25*8 = 4,200 total).
    const val CEILING = 6
    const val LAYOUT_VERSION = 2
    const val BURROW_ID = 2
    val bedOffsets = listOf(-5 to -6, 5 to -6, -5 to 6, 5 to 6)

    fun blocks(x: Int, y: Int, z: Int): Map<Triple<Int, Int, Int>, Pair<String, FarmMoleBurrowMarker>> {
        val result = linkedMapOf<Triple<Int, Int, Int>, Pair<String, FarmMoleBurrowMarker>>()
        fun place(dx: Int, dy: Int, dz: Int, material: Material, marker: FarmMoleBurrowMarker = FarmMoleBurrowMarker.NONE) {
            result[Triple(x + dx, y + dy, z + dz)] = material.createBlockData().asString to marker
        }

        // Full native envelope: solid floor, Nether brick walls, and a low ceiling.
        for (dx in -HALF_WIDTH..HALF_WIDTH) for (dz in -HALF_LENGTH..HALF_LENGTH) {
            place(dx, -1, dz, if (dx == 0 || dz % 3 == 0) Material.POLISHED_BLACKSTONE_BRICKS else Material.CRACKED_POLISHED_BLACKSTONE_BRICKS)
            for (dy in 0..CEILING) {
                val wall = kotlin.math.abs(dx) == HALF_WIDTH || kotlin.math.abs(dz) == HALF_LENGTH
                place(dx, dy, dz, when {
                    dy == CEILING -> Material.NETHER_BRICKS
                    wall && (dx == -HALF_WIDTH || dx == HALF_WIDTH) && dz % 3 == 0 -> Material.POLISHED_BASALT
                    wall -> Material.NETHER_BRICKS
                    else -> Material.AIR
                })
            }
        }
        // Four raised 5x5 farming beds leave broad aisles on every side.
        bedOffsets.forEach { (bedX, bedZ) ->
            for (dx in -2..2) for (dz in -2..2) place(bedX + dx, 0, bedZ + dz, Material.SOUL_SAND)
        }
        // The north end is a walkable entrance alcove; z=4 remains clear for expedition travel.
        for (dx in -2..2) for (dz in 10 until HALF_LENGTH) for (dy in 0..2) place(dx, dy, dz, Material.AIR)

        // A contained magma furnace anchors the far end: no fluid or fire source.
        place(0, 0, -HALF_LENGTH + 1, Material.FURNACE)
        place(0, 1, -HALF_LENGTH + 1, Material.MAGMA_BLOCK)
        place(0, 2, -HALF_LENGTH + 1, Material.TINTED_GLASS)
        place(0, 1, -HALF_LENGTH + 2, Material.TINTED_GLASS)
        for (dx in listOf(-1, 1)) place(dx, 1, -HALF_LENGTH + 1, Material.BASALT)
        for (dx in -2..2) {
            place(dx, 0, -HALF_LENGTH + 2, Material.BASALT)
            place(dx, 3, -HALF_LENGTH + 2, Material.BASALT)
        }
        for (dx in listOf(-2, 2)) for (dy in 1..2) place(dx, dy, -HALF_LENGTH + 2, Material.BASALT)

        // Basalt ribs reinforce the long hall without closing its central and side aisles.
        for (dz in listOf(-8, -2, 4, 10)) {
            for (dy in 0..5) {
                place(-8, dy, dz, Material.BASALT)
                place(8, dy, dz, Material.BASALT)
            }
            for (dx in -7..7) place(dx, 5, dz, Material.BASALT)
        }
        // Shroomlights are real light blocks recessed into ribs and ceiling.
        for (dx in listOf(-8, 8)) for (dz in listOf(-8, -2, 4, 10)) place(dx, 4, dz, Material.SHROOMLIGHT)
        for (dx in listOf(-4, 0, 4)) for (dz in listOf(-8, 0, 8)) place(dx, 6, dz, Material.SHROOMLIGHT)

        place(0, 0, 0, Material.AIR, FarmMoleBurrowMarker.START)
        place(0, 0, 1, Material.AIR, FarmMoleBurrowMarker.LAIR)
        return result
    }
}

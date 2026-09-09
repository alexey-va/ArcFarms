package ru.ruscrafting.farms.paper.farm.incident.greenhouse

import org.bukkit.Material
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowMarker

/** Native blocks at their normal scale; the expedition journal owns every placement and restoration. */
internal object FarmHellRiftRoom {
    fun blocks(x: Int, y: Int, z: Int): Map<Triple<Int, Int, Int>, Pair<String, FarmMoleBurrowMarker>> {
        val result = linkedMapOf<Triple<Int, Int, Int>, Pair<String, FarmMoleBurrowMarker>>()
        fun place(dx: Int, dy: Int, dz: Int, material: Material, marker: FarmMoleBurrowMarker = FarmMoleBurrowMarker.NONE) {
            result[Triple(x + dx, y + dy, z + dz)] = material.createBlockData().asString to marker
        }
        for (dx in -5..5) for (dz in -6..6) for (dy in -1..5) {
            val wall = kotlin.math.abs(dx) == 5 || kotlin.math.abs(dz) == 6
            val material = when {
                dy == -1 -> if (dx == 0) Material.POLISHED_BLACKSTONE_BRICKS else Material.CRACKED_POLISHED_BLACKSTONE_BRICKS
                dy == 5 -> Material.NETHER_BRICKS
                wall && (dx % 3 == 0 || dz % 3 == 0) -> Material.POLISHED_BASALT
                wall -> Material.NETHER_BRICKS
                else -> Material.AIR
            }
            place(dx, dy, dz, material)
        }
        // Recessed light wells provide actual light without exposing players to lava or fire blocks.
        for (dx in listOf(-5, 5)) for (dz in listOf(-4, 0, 4)) place(dx, 2, dz, Material.SHROOMLIGHT)
        for (dz in listOf(-3, 0, 3)) place(0, 5, dz, Material.SHROOMLIGHT)
        for (dx in -1..1) for (dz in -1..1) place(dx, -1, dz, Material.CRYING_OBSIDIAN)
        for (dx in listOf(-4, 4)) for (dz in listOf(-5, 5)) for (dy in 0..3) place(dx, dy, dz, Material.BASALT)
        for (dx in listOf(-1, 1)) for (dy in 0..3) place(dx, dy, 0, Material.OBSIDIAN)
        for (dx in -1..1) place(dx, 3, 0, Material.CRYING_OBSIDIAN)
        place(0, 0, 0, Material.AIR, FarmMoleBurrowMarker.START)
        place(0, 0, 1, Material.AIR, FarmMoleBurrowMarker.LAIR)
        return result
    }
}

package ru.ruscrafting.farms.paper

import org.bukkit.Material

internal object FarmGroundSpreadPolicy {
    private val blockedMaterials = setOf(
        Material.GRASS_BLOCK,
        Material.MYCELIUM,
        Material.PODZOL,
    )

    fun blocks(
        insideFarm: Boolean,
        source: Material,
        result: Material,
    ): Boolean = insideFarm && (source in blockedMaterials || result in blockedMaterials)
}

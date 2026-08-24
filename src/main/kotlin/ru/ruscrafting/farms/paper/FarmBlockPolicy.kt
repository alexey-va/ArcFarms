package ru.ruscrafting.farms.paper

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.type.Farmland

internal object FarmBlockPolicy {
    fun isSelectableBed(soil: Material, above: Material, configuredCrops: Set<String>): Boolean {
        if (soil != Material.FARMLAND) return false
        return isOpenBedContent(above, configuredCrops)
    }

    fun isOpenBedContent(above: Material, configuredCrops: Set<String>): Boolean =
        above in AIR_BLOCKS || (above.name in configuredCrops && MaterialRules.isPlantableCrop(above))

    fun isOrchardLeaf(material: Material, below: Material): Boolean =
        MaterialRules.isLeaf(material) && below in AIR_BLOCKS

    fun makeWet(block: Block) {
        require(block.type == Material.FARMLAND) { "Only farmland can join the persistent farm index" }
        val farmland = block.blockData as Farmland
        if (farmland.moisture == farmland.maximumMoisture) return
        farmland.moisture = farmland.maximumMoisture
        block.setBlockData(farmland, false)
    }

    private val AIR_BLOCKS = setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)
}

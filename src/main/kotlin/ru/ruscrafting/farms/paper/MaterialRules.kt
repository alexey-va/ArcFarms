package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.random.RandomGenerator

object MaterialRules {
    fun material(name: String): Material =
        Material.matchMaterial(name) ?: error("Unknown Paper material: $name")

    fun speciesOf(material: Material): String? {
        val name = material.name
        return when {
            name.endsWith("_LOG") -> name.removeSuffix("_LOG")
            name.endsWith("_WOOD") -> name.removeSuffix("_WOOD")
            name.endsWith("_STEM") -> name.removeSuffix("_STEM")
            name.endsWith("_HYPHAE") -> name.removeSuffix("_HYPHAE")
            else -> null
        }
    }

    fun isLumberBreakable(material: Material): Boolean =
        speciesOf(material) != null || material.name.endsWith("_LEAVES") ||
            material in setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.VINE)

    fun isPickaxe(item: ItemStack?): Boolean = item != null && item.type.name.endsWith("_PICKAXE")

    fun cropComponent(material: Material): Component = Component.translatable("block.minecraft.${material.name.lowercase()}")

    fun woodComponent(species: String): Component = Component.translatable("block.minecraft.${species.lowercase()}_log")

    fun weightedMaterial(
        weights: LinkedHashMap<Material, Int>,
        random: RandomGenerator,
    ): Material {
        require(weights.isNotEmpty())
        val total = weights.values.sumOf(Int::toLong)
        var choice = random.nextLong(total)
        for ((material, weight) in weights) {
            choice -= weight
            if (choice < 0) return material
        }
        return weights.keys.last()
    }

}

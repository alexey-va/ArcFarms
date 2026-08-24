package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.random.RandomGenerator

object MaterialRules {
    private val fixedBlockCrops = setOf(Material.MELON, Material.PUMPKIN)

    private val seedsByCrop = mapOf(
        Material.WHEAT to Material.WHEAT_SEEDS,
        Material.CARROTS to Material.CARROT,
        Material.POTATOES to Material.POTATO,
        Material.BEETROOTS to Material.BEETROOT_SEEDS,
    )

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

    fun isHoe(item: ItemStack?): Boolean = item != null && item.type.name.endsWith("_HOE")

    fun cropForSeed(item: ItemStack?): Material? = item?.type?.let(::cropForSeed)

    fun cropForSeed(seed: Material): Material? =
        seedsByCrop.entries.firstOrNull { it.value == seed }?.key

    fun seedForCrop(crop: Material): Material? = seedsByCrop[crop]

    fun isPlantableCrop(crop: Material): Boolean = crop in seedsByCrop

    fun isFixedBlockCrop(crop: Material): Boolean = crop in fixedBlockCrops

    fun cropComponent(material: Material): Component = Component.translatable(
        when (material) {
            Material.WHEAT -> "item.minecraft.wheat"
            Material.CARROTS -> "item.minecraft.carrot"
            Material.POTATOES -> "item.minecraft.potato"
            Material.BEETROOTS -> "item.minecraft.beetroot"
            else -> "block.minecraft.${material.name.lowercase()}"
        },
    )

    fun itemComponent(material: Material): Component = Component.translatable(material.translationKey())

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

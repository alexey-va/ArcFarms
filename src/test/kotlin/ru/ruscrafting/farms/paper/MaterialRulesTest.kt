package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import net.kyori.adventure.text.TranslatableComponent

class MaterialRulesTest : FunSpec({
    test("supported farm seeds map to exact crop blocks") {
        mapOf(
            Material.WHEAT_SEEDS to Material.WHEAT,
            Material.CARROT to Material.CARROTS,
            Material.POTATO to Material.POTATOES,
            Material.BEETROOT_SEEDS to Material.BEETROOTS,
            Material.SWEET_BERRIES to Material.SWEET_BERRY_BUSH,
        ).forEach { (seed, crop) ->
            MaterialRules.cropForSeed(seed) shouldBe crop
            MaterialRules.seedForCrop(crop) shouldBe seed
        }
        MaterialRules.cropForSeed(Material.MELON_SEEDS) shouldBe null
        MaterialRules.isPlantableCrop(Material.WHEAT) shouldBe true
        MaterialRules.isPlantableCrop(Material.MELON) shouldBe false
        MaterialRules.isFixedBlockCrop(Material.MELON) shouldBe true
        MaterialRules.isFixedBlockCrop(Material.PUMPKIN) shouldBe true
        MaterialRules.isFixedBlockCrop(Material.CARVED_PUMPKIN) shouldBe false
    }

    test("crop labels use harvested item names rather than sprout block names") {
        (MaterialRules.cropComponent(Material.WHEAT) as TranslatableComponent).key() shouldBe "item.minecraft.wheat"
        (MaterialRules.cropComponent(Material.CARROTS) as TranslatableComponent).key() shouldBe "item.minecraft.carrot"
        (MaterialRules.cropComponent(Material.POTATOES) as TranslatableComponent).key() shouldBe "item.minecraft.potato"
        (MaterialRules.cropComponent(Material.BEETROOTS) as TranslatableComponent).key() shouldBe "item.minecraft.beetroot"
        (MaterialRules.cropComponent(Material.SWEET_BERRY_BUSH) as TranslatableComponent).key() shouldBe
            "item.minecraft.sweet_berries"
    }

    test("every configured farm crop resolves to an inventory-safe harvest item") {
        mapOf(
            Material.WHEAT to Material.WHEAT,
            Material.CARROTS to Material.CARROT,
            Material.POTATOES to Material.POTATO,
            Material.BEETROOTS to Material.BEETROOT,
            Material.SWEET_BERRY_BUSH to Material.SWEET_BERRIES,
            Material.MELON to Material.MELON,
            Material.PUMPKIN to Material.PUMPKIN,
        ).forEach { (crop, expectedItem) ->
            MaterialRules.harvestItemForCrop(crop) shouldBe expectedItem
        }
    }
})

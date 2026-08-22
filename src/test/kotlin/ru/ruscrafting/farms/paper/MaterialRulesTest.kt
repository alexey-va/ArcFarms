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
        ).forEach { (seed, crop) ->
            MaterialRules.cropForSeed(seed) shouldBe crop
            MaterialRules.seedForCrop(crop) shouldBe seed
        }
        MaterialRules.cropForSeed(Material.MELON_SEEDS) shouldBe null
    }

    test("crop labels use harvested item names rather than sprout block names") {
        (MaterialRules.cropComponent(Material.WHEAT) as TranslatableComponent).key() shouldBe "item.minecraft.wheat"
        (MaterialRules.cropComponent(Material.CARROTS) as TranslatableComponent).key() shouldBe "item.minecraft.carrot"
        (MaterialRules.cropComponent(Material.POTATOES) as TranslatableComponent).key() shouldBe "item.minecraft.potato"
        (MaterialRules.cropComponent(Material.BEETROOTS) as TranslatableComponent).key() shouldBe "item.minecraft.beetroot"
    }
})

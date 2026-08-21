package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material

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
})

package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class FarmBlockPolicyTest : FunSpec({
    val crops = setOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "SWEET_BERRY_BUSH", "MELON", "PUMPKIN")

    test("only open or normally planted farmland can be selected") {
        FarmBlockPolicy.isSelectableBed(Material.FARMLAND, Material.AIR, crops) shouldBe true
        FarmBlockPolicy.isSelectableBed(Material.FARMLAND, Material.WHEAT, crops) shouldBe true
        FarmBlockPolicy.isSelectableBed(Material.FARMLAND, Material.SWEET_BERRY_BUSH, crops) shouldBe true
        FarmBlockPolicy.isSelectableBed(Material.FARMLAND, Material.STONE, crops) shouldBe false
        FarmBlockPolicy.isSelectableBed(Material.FARMLAND, Material.OAK_PLANKS, crops) shouldBe false
        FarmBlockPolicy.isSelectableBed(Material.FARMLAND, Material.MELON_STEM, crops) shouldBe false
        FarmBlockPolicy.isSelectableBed(Material.FARMLAND, Material.MELON, crops) shouldBe false
        FarmBlockPolicy.isSelectableBed(Material.DIRT, Material.AIR, crops) shouldBe false
    }

    test("apple anchors require leaves with free hanging space below") {
        FarmBlockPolicy.isOrchardLeaf(Material.OAK_LEAVES, Material.AIR) shouldBe true
        FarmBlockPolicy.isOrchardLeaf(Material.CHERRY_LEAVES, Material.AIR) shouldBe true
        FarmBlockPolicy.isOrchardLeaf(Material.OAK_LEAVES, Material.OAK_LOG) shouldBe false
        FarmBlockPolicy.isOrchardLeaf(Material.OAK_LOG, Material.AIR) shouldBe false
    }

    test("indexed beds survive temporary soil conversion but not player construction") {
        FarmBlockPolicy.isRecoverableIndexedBed(Material.DIRT, Material.AIR, crops) shouldBe true
        FarmBlockPolicy.isRecoverableIndexedBed(Material.GRASS_BLOCK, Material.WHEAT, crops) shouldBe true
        FarmBlockPolicy.isRecoverableIndexedBed(Material.PODZOL, Material.AIR, crops) shouldBe true
        FarmBlockPolicy.isRecoverableIndexedBed(Material.STONE, Material.AIR, crops) shouldBe false
        FarmBlockPolicy.isRecoverableIndexedBed(Material.DIRT, Material.OAK_PLANKS, crops) shouldBe false
    }
})

package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorldEditToolPolicyTest : FunSpec({
    test("selection wand aliases are owned by WorldEdit") {
        WorldEditToolPolicy.ownsInteraction(
            heldItemId = "minecraft:wooden_axe",
            selectionWandId = "wooden_axe",
            navigationWandId = "minecraft:compass",
            hasBoundTool = false,
            superPickaxeActive = false,
        ) shouldBe true
    }

    test("bound tools and super pickaxes stay owned by WorldEdit") {
        WorldEditToolPolicy.ownsInteraction("minecraft:stick", null, null, true, false) shouldBe true
        WorldEditToolPolicy.ownsInteraction("minecraft:diamond_pickaxe", null, null, false, true) shouldBe true
    }

    test("ordinary farm tools remain owned by ArcFarms") {
        WorldEditToolPolicy.ownsInteraction(
            heldItemId = "minecraft:diamond_hoe",
            selectionWandId = "minecraft:wooden_axe",
            navigationWandId = "minecraft:compass",
            hasBoundTool = false,
            superPickaxeActive = false,
        ) shouldBe false
    }
})

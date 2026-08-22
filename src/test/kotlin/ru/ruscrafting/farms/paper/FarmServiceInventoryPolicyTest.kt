package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmServiceInventoryPolicyTest : FunSpec({
    test("service items cannot enter the personal crafting grid") {
        FarmServiceInventoryPolicy.cancelClick(
            playerCraftingView = true,
            rawSlot = 1,
            topSize = 5,
            shiftClick = false,
            currentTagged = false,
            cursorTagged = true,
            hotbarTagged = false,
        ) shouldBe true
        FarmServiceInventoryPolicy.cancelDrag(true, setOf(1, 9), 5) shouldBe true
    }

    test("service items can still be rearranged inside the player inventory") {
        FarmServiceInventoryPolicy.cancelClick(
            playerCraftingView = true,
            rawSlot = 20,
            topSize = 5,
            shiftClick = false,
            currentTagged = true,
            cursorTagged = false,
            hotbarTagged = false,
        ) shouldBe false
    }

    test("shift click and external containers remain sealed") {
        FarmServiceInventoryPolicy.cancelClick(true, 20, 5, true, true, false, false) shouldBe true
        FarmServiceInventoryPolicy.cancelClick(false, 20, 27, false, true, false, false) shouldBe true
    }

    test("offhand swaps cannot move a tagged supply into the crafting grid") {
        FarmServiceInventoryPolicy.cancelClick(true, 2, 5, false, false, false, true) shouldBe true
    }
})

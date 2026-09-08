package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmFoodPurchaseTest : FunSpec({
    val food = FarmFoodPurchase(UUID.randomUUID().toString(), "BREAD", 16, 20, 100)
    test("food and perks spend the same weekly points without changing active perks") {
        val before = FarmPlayerPerks(100, 180, mapOf(FarmPerkType.SPEED to 99999))
        before.buyFood(food, 20, 200) shouldBe before.copy(spentPoints = 200, foodPurchase = food)
        before.buyFood(food.copy(price = 21), 21, 200) shouldBe null
    }
    test("pending and claimed food cannot be bought twice") {
        val state = FarmPlayerPerks(100, 20, foodPurchase = food)
        state.buyFood(food, 20, 1000) shouldBe null
        state.copy(foodPurchase = food.copy(claimed = true)).buyFood(food, 20, 1000) shouldBe null
    }
    test("week rollover preserves paid delivery and claim boundaries") {
        for (claimed in listOf(false, true)) {
            val purchase = food.copy(claimed = claimed)
            FarmPlayerPerks(100, 20, foodPurchase = purchase).forWeek(107, 1000) shouldBe
                FarmPlayerPerks(107, foodPurchase = purchase)
        }
    }
    test("food cannot overflow the spent counter") {
        FarmPlayerPerks(100, Long.MAX_VALUE - 10).buyFood(food, 20, Long.MAX_VALUE) shouldBe null
    }
    test("premium perks retain the ordinary no stacking and expiry rules") {
        for (type in listOf(FarmPerkType.IRON_FARMER, FarmPerkType.SKY_COURIER)) {
            val state = FarmPlayerPerks(100)
            val result = state.purchasePerk(type, 900, 86400000, 900, 1000) as FarmPerkPurchaseResult.Purchased
            result.activeUntil shouldBe 86401000
            result.state.purchasePerk(type, 900, 86400000, 5000, 2000) shouldBe
                FarmPerkPurchaseResult.AlreadyActive(86401000)
        }
    }
})

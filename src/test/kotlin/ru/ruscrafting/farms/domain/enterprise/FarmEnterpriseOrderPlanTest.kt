package ru.ruscrafting.farms.domain.enterprise

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import ru.ruscrafting.farms.domain.FarmContractRarity
import ru.ruscrafting.farms.domain.FarmOrder

class FarmEnterpriseOrderPlanTest : FunSpec({
    val commonSmall = order("common_small", 10, FarmContractRarity.COMMON)
    val commonLarge = order("common_large", 30, FarmContractRarity.COMMON)
    val rare = order("rare", 100, FarmContractRarity.RARE)
    val orders = listOf(commonSmall, commonLarge, rare)

    test("TEAM preserves complete original order pool and contract data") {
        FarmEnterpriseOrderPlan.orders(WorksiteEnterprisePlan.TEAM, orders) shouldContainExactly orders
    }

    test("STEADY chooses smaller common contracts and preserves each order") {
        FarmEnterpriseOrderPlan.orders(WorksiteEnterprisePlan.STEADY, orders).map { it.id } shouldContainExactly listOf("common_small")
    }

    test("CHALLENGE chooses rare contracts and falls back when no rare pool exists") {
        FarmEnterpriseOrderPlan.orders(WorksiteEnterprisePlan.CHALLENGE, orders).map { it.id } shouldContainExactly listOf("rare")
        FarmEnterpriseOrderPlan.orders(WorksiteEnterprisePlan.CHALLENGE, listOf(commonSmall)).map { it.id } shouldContainExactly listOf("common_small")
    }
})

private fun order(id: String, quota: Int, rarity: FarmContractRarity) = FarmOrder(
    id = id,
    required = mapOf("WHEAT" to quota),
    rarity = rarity,
)

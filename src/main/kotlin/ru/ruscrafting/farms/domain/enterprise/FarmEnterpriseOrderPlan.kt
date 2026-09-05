package ru.ruscrafting.farms.domain.enterprise

import ru.ruscrafting.farms.domain.FarmContractRarity
import ru.ruscrafting.farms.domain.FarmOrder

/** Selects complete existing contracts; quotas, rewards and incidents are never rewritten. */
internal object FarmEnterpriseOrderPlan {
    fun orders(plan: WorksiteEnterprisePlan, orders: List<FarmOrder>): List<FarmOrder> {
        if (orders.isEmpty() || plan == WorksiteEnterprisePlan.TEAM) return orders
        val selected = when (plan) {
            WorksiteEnterprisePlan.STEADY -> {
                val common = orders.filter { it.rarity == FarmContractRarity.COMMON }
                val sizes = common.map { it.totalRequired }.sorted()
                val median = sizes.getOrNull((sizes.size - 1).coerceAtLeast(0) / 2)
                common.filter { median != null && it.totalRequired <= median }
            }
            WorksiteEnterprisePlan.CHALLENGE -> orders.filter { it.rarity == FarmContractRarity.RARE }
            WorksiteEnterprisePlan.TEAM -> orders
        }
        return selected.ifEmpty { orders }
    }
}

package ru.ruscrafting.farms.domain

/** CLAIMED is a durable no-replay boundary; an interrupted delivery needs operator reconciliation. */
data class FarmFoodPurchase(
    val id: String,
    val material: String,
    val amount: Int,
    val price: Long,
    val chargedWeek: Long,
    val claimed: Boolean = false,
) {
    init {
        require(runCatching { java.util.UUID.fromString(id) }.isSuccess)
        require(material in setOf("BREAD", "COOKED_BEEF", "GOLDEN_CARROT"))
        require(amount in 1..64 && price in 1..1_000_000 && chargedWeek >= 0)
    }
}

internal fun FarmPlayerPerks.buyFood(
    purchase: FarmFoodPurchase,
    price: Long,
    weeklyContribution: Long,
): FarmPlayerPerks? {
    require(price > 0 && !purchase.claimed && purchase.price == price && purchase.chargedWeek == weekStartEpochDay)
    if (foodPurchase != null || spentPoints > Long.MAX_VALUE - price ||
        weeklyContribution < spentPoints || weeklyContribution - spentPoints < price) return null
    return copy(spentPoints = spentPoints + price, foodPurchase = purchase)
}

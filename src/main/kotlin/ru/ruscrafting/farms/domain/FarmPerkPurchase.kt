package ru.ruscrafting.farms.domain

internal sealed interface FarmPerkPurchaseResult {
    data class Purchased(val state: FarmPlayerPerks, val activeUntil: Long) : FarmPerkPurchaseResult
    data class AlreadyActive(val activeUntil: Long) : FarmPerkPurchaseResult
    data class InsufficientPoints(val required: Long, val available: Long) : FarmPerkPurchaseResult
}

/** Pure purchase decision so persistence and every GUI path share the no-stacking rule. */
internal fun FarmPlayerPerks.purchasePerk(
    type: FarmPerkType,
    price: Long,
    durationMillis: Long,
    weeklyContribution: Long,
    now: Long,
): FarmPerkPurchaseResult {
    require(price > 0) { "Farm perk price must be positive" }
    require(durationMillis > 0) { "Farm perk duration must be positive" }
    activeUntil[type]?.takeIf { it > now }?.let { return FarmPerkPurchaseResult.AlreadyActive(it) }
    val available = (weeklyContribution - spentPoints).coerceAtLeast(0)
    if (available < price || spentPoints > Long.MAX_VALUE - price) {
        return FarmPerkPurchaseResult.InsufficientPoints(price, available)
    }
    val until = runCatching { Math.addExact(now, durationMillis) }.getOrDefault(Long.MAX_VALUE)
    return FarmPerkPurchaseResult.Purchased(
        state = copy(
            spentPoints = spentPoints + price,
            activeUntil = activeUntil + (type to until),
        ),
        activeUntil = until,
    )
}

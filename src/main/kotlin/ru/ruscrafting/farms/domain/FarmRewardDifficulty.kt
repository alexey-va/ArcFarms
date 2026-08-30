package ru.ruscrafting.farms.domain

/** Stable reward scaling from the actual order and its deterministic incident plan. */
object FarmRewardDifficulty {
    fun multiplierPercent(order: FarmOrder, rules: FarmRules, sequence: Long): Int {
        val incidentCount = rules.incidentTargetCount(sequence)
        val incidents = FarmIncidentPlanner.sequence(order.incidentTypes, incidentCount, sequence) +
            FarmIncidentType.FOOD_DELIVERY
        val volumeBonus = (order.totalRequired / 320).coerceIn(0, 20)
        val rarityBonus = if (order.rarity == FarmContractRarity.RARE) 15 else 0
        val incidentBonus = incidents.sumOf(::incidentWeight)
        return (BASE_PERCENT + volumeBonus + rarityBonus + incidentBonus).coerceIn(BASE_PERCENT, MAX_PERCENT)
    }

    private fun incidentWeight(type: FarmIncidentType): Int = when (type) {
        FarmIncidentType.PESTS -> 4
        FarmIncidentType.DROUGHT -> 5
        FarmIncidentType.MARKET -> 4
        FarmIncidentType.CHANNELS -> 5
        FarmIncidentType.BIRDS, FarmIncidentType.GIANT_CROP -> 6
        FarmIncidentType.NIGHT_SHIFT, FarmIncidentType.PROCESSING, FarmIncidentType.BARN_FIRE,
        FarmIncidentType.FROST,
        -> 8
        FarmIncidentType.FOOD_DELIVERY -> 12
    }

    private const val BASE_PERCENT = 125
    private const val MAX_PERCENT = 250
}

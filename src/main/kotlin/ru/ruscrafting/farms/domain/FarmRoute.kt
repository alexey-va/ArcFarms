package ru.ruscrafting.farms.domain

import java.util.UUID

data class FarmDeliveryRoute(
    val points: List<FarmPointPosition>,
) {
    init {
        require(points.size in 2..512) { "Farm delivery route must contain 2..512 points" }
        require(points.map(FarmPointPosition::world).distinct().size == 1) { "Farm delivery route crosses worlds" }
        points.zipWithNext().forEach { (from, to) ->
            val dx = from.x - to.x
            val dy = from.y - to.y
            val dz = from.z - to.z
            require(dx * dx + dy * dy + dz * dz <= 100.0) { "Farm delivery route contains a gap above 10 blocks" }
        }
    }
}

data class FarmRouteState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val routes: Map<String, FarmDeliveryRoute> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

enum class FarmPerkType {
    HARVEST_AREA,
    SPEED,
    SUSTENANCE,
    REWARD_BOOST,
}

data class FarmPlayerPerks(
    val weekStartEpochDay: Long,
    val spentPoints: Long = 0,
    val activeUntil: Map<FarmPerkType, Long> = emptyMap(),
) {
    init {
        require(spentPoints >= 0) { "Spent farm perk points cannot be negative" }
        require(activeUntil.size <= FarmPerkType.entries.size) { "Farm perk state contains unknown entries" }
        require(activeUntil.values.all { it >= 0 }) { "Farm perk expiry cannot be negative" }
    }

    fun forWeek(weekStart: Long, now: Long): FarmPlayerPerks {
        if (weekStartEpochDay == weekStart) return this
        return FarmPlayerPerks(
            weekStartEpochDay = weekStart,
            spentPoints = 0,
            activeUntil = activeUntil.filterValues { it > now },
        )
    }
}

data class FarmPerkLedgerState(
    val values: Map<UUID, FarmPlayerPerks> = emptyMap(),
)

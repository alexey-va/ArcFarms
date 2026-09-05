package ru.ruscrafting.farms.domain.enterprise

/** Personal amounts are estimates until the business week is durably closed. */
data class WorksiteEnterprisePlayerView(
    val workerAccruedCents: Long,
    val projectedDividendCents: Long,
    val nextSettlementMillis: Long,
    val currentWeekStartEpochDay: Long,
    val completedOrders: Long,
    val contribution: Long,
    val availableThisWeekCents: Long,
    val licenseWeeksRemaining: Int,
    val projectStage: Int,
    val projectOrders: Long,
    val projectTarget: Long,
    val planId: String,
    val canVote: Boolean,
    val canAdvise: Boolean,
    val simulated: Boolean = false,
)

/** Accepted per-order worker pool; a worker's share depends on actual contribution. */
data class WorksiteEnterpriseOrderPremium(
    val workerPoolCents: Long,
    val simulated: Boolean,
)

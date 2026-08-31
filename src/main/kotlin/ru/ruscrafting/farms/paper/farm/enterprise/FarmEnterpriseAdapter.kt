package ru.ruscrafting.farms.paper.farm.enterprise

import ru.ruscrafting.farms.config.WorksiteEnterpriseMode
import ru.ruscrafting.farms.config.WorksiteEnterpriseSettings
import ru.ruscrafting.farms.domain.enterprise.ActiveWorksiteEnterpriseOrder
import ru.ruscrafting.farms.domain.enterprise.EnterpriseReservationDecision
import ru.ruscrafting.farms.domain.enterprise.EnterpriseSettlementDecision
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseLedger
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseCompanyView
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterprisePolicy
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseSnapshot
import ru.ruscrafting.farms.domain.enterprise.worksiteBusinessWeekStartEpochDay
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import java.util.UUID

internal interface FarmEnterprisePort {
    fun orderStarted(worksiteId: String, orderId: String, sequence: Long, startedAt: Long): Boolean

    fun orderCancelled(worksiteId: String, sequence: Long): Boolean

    fun orderCompleted(
        worksiteId: String,
        sequence: Long,
        contributors: Map<UUID, Int>,
        commercialEligible: Boolean,
    ): Boolean
}

/** Farm-only event translation around the reusable worksite enterprise kernel. */
internal class FarmEnterpriseAdapter(
    private val settings: () -> WorksiteEnterpriseSettings,
    private val debug: ArcFarmsDebug,
    private val clock: () -> Long,
    private val ledger: WorksiteEnterpriseLedger = WorksiteEnterpriseLedger(),
    private val commerciallyActive: (WorksiteEnterpriseSettings) -> Boolean = { false },
    private val shadowEligible: (WorksiteEnterpriseSettings) -> Boolean = { true },
    private val companyExists: (WorksiteEnterpriseSettings) -> Boolean = { false },
) : FarmEnterprisePort {
    fun replace(snapshot: WorksiteEnterpriseSnapshot?) = ledger.replace(snapshot ?: WorksiteEnterpriseSnapshot())

    fun snapshot(): WorksiteEnterpriseSnapshot = ledger.snapshot()

    fun companyView(): WorksiteEnterpriseCompanyView? {
        val configured = settings()
        if (configured.mode == WorksiteEnterpriseMode.OFF && !companyExists(configured)) return null
        return ledger.companyView(configured.policy(), configured.weekStartEpochDay(clock()))
    }

    override fun orderStarted(worksiteId: String, orderId: String, sequence: Long, startedAt: Long): Boolean {
        val configured = settings()
        val acceptsCommercialOrder = configured.mode == WorksiteEnterpriseMode.SHADOW && shadowEligible(configured) ||
            configured.mode == WorksiteEnterpriseMode.LIVE && commerciallyActive(configured)
        if (!acceptsCommercialOrder || worksiteId != configured.worksiteId) return false
        val decision = ledger.reserve(
            configured.policy(),
            ActiveWorksiteEnterpriseOrder(
                worksiteId = worksiteId,
                orderId = orderId,
                sequence = sequence,
                grossTariffCents = configured.grossTariffCents(orderId),
                reservedAt = startedAt,
                businessWeekStartEpochDay = configured.weekStartEpochDay(startedAt),
            ),
        )
        debugReservation(worksiteId, orderId, sequence, decision)
        return decision.changed
    }

    override fun orderCancelled(worksiteId: String, sequence: Long): Boolean {
        val changed = ledger.releaseReservation(settings().activity, worksiteId, sequence)
        debug.event(
            "enterprise_shadow_cancellation",
            "activity" to "farm",
            "worksite" to worksiteId,
            "sequence" to sequence,
            "released" to changed,
        )
        return changed
    }

    override fun orderCompleted(
        worksiteId: String,
        sequence: Long,
        contributors: Map<UUID, Int>,
        commercialEligible: Boolean,
    ): Boolean {
        val configured = settings()
        val acceptedBeforeModeChange = ledger.hasReservation(configured.activity, worksiteId, sequence)
        if (!acceptedBeforeModeChange && (
                configured.mode == WorksiteEnterpriseMode.OFF ||
                    configured.mode == WorksiteEnterpriseMode.LIVE && !commerciallyActive(configured) ||
                    worksiteId != configured.worksiteId
            )
        ) return false
        val acceptedContributors = contributors.filterValues { it > 0 }
        val decision = if (commercialEligible && acceptedContributors.isNotEmpty()) {
            ledger.settle(
                policy = configured.policy(),
                worksiteId = worksiteId,
                sequence = sequence,
                weekStartEpochDay = configured.weekStartEpochDay(clock()),
                contributors = acceptedContributors,
            )
        } else {
            ledger.excludeCompletion(configured.policy(), worksiteId, sequence)
        }
        debugSettlement(worksiteId, sequence, commercialEligible, acceptedContributors.size, decision)
        return decision.changed
    }

    fun reconcileRuntimes(
        runtimes: Collection<FarmRuntime>,
        configured: WorksiteEnterpriseSettings = settings(),
    ): Boolean {
        val activeOrders = runtimes.mapNotNull { runtime ->
            val orderId = runtime.state.orderId ?: return@mapNotNull null
            if (runtime.state.phase in setOf(FarmPhase.IDLE, FarmPhase.COOLDOWN)) return@mapNotNull null
            val startedAt = runtime.state.startedAt.takeIf { it > 0L } ?: clock()
            ActiveWorksiteEnterpriseOrder(
                worksiteId = runtime.settings.id,
                orderId = orderId,
                sequence = runtime.state.sequence,
                grossTariffCents = configured.grossTariffCents(orderId),
                reservedAt = startedAt,
                businessWeekStartEpochDay = configured.weekStartEpochDay(startedAt),
            )
        }
        val acceptsNewOrders = configured.mode == WorksiteEnterpriseMode.SHADOW && shadowEligible(configured) ||
            configured.mode == WorksiteEnterpriseMode.LIVE && commerciallyActive(configured)
        val acceptedOrders = if (acceptsNewOrders) {
            activeOrders
        } else {
            activeOrders.filter { ledger.hasReservation(configured.activity, it.worksiteId, it.sequence) }
        }
        return reconcileOrders(acceptedOrders, configured)
    }

    internal fun reconcileOrders(
        activeOrders: Collection<ActiveWorksiteEnterpriseOrder>,
        configured: WorksiteEnterpriseSettings = settings(),
    ): Boolean {
        return ledger.reconcile(configured.policy(), activeOrders)
    }

    fun pruneReports(configured: WorksiteEnterpriseSettings = settings()): Boolean =
        ledger.pruneReports(configured.policy())

    private fun debugReservation(
        worksiteId: String,
        orderId: String,
        sequence: Long,
        decision: EnterpriseReservationDecision,
    ) {
        debug.event(
            "enterprise_shadow_reservation",
            "activity" to "farm",
            "worksite" to worksiteId,
            "order" to orderId,
            "sequence" to sequence,
            "outcome" to decision.outcome,
            "available_cents" to decision.availableCents,
        )
    }

    private fun debugSettlement(
        worksiteId: String,
        sequence: Long,
        commercialEligible: Boolean,
        contributorCount: Int,
        decision: EnterpriseSettlementDecision,
    ) {
        debug.event(
            "enterprise_shadow_settlement",
            "activity" to "farm",
            "worksite" to worksiteId,
            "sequence" to sequence,
            "eligible" to commercialEligible,
            "contributors" to contributorCount,
            "outcome" to decision.outcome,
            "gross_cents" to decision.grossRevenueCents,
            "burn_cents" to decision.operatingBurnCents,
            "worker_cents" to decision.workerBonusCents,
            "retained_cents" to decision.retainedProfitCents,
        )
    }
}

private fun WorksiteEnterpriseSettings.policy(): WorksiteEnterprisePolicy = WorksiteEnterprisePolicy(
    activity = activity,
    companyId = companyId,
    licenseGrossEnvelopeCents = licenseGrossEnvelopeCents,
    operatingCostPercent = operatingCostPercent,
    workerBonusPercent = workerBonusPercent,
    dividendPercent = dividendPercent,
    weeklyUpkeepCents = weeklyUpkeepCents,
    retainedReportWeeks = retainedReportWeeks,
)

internal fun WorksiteEnterpriseSettings.weekStartEpochDay(timestampMillis: Long): Long =
    worksiteBusinessWeekStartEpochDay(
        timestampMillis = timestampMillis,
        zoneId = businessWeek.zoneId,
        weekStartDay = businessWeek.startDay,
        weekStartTime = businessWeek.startTime,
    )

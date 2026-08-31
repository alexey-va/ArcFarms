package ru.ruscrafting.farms.domain.enterprise

import ru.ruscrafting.farms.domain.ActivityKind
import java.math.BigInteger
import java.util.UUID

/** Economy policy supplied by one worksite adapter to the shared enterprise kernel. */
internal data class WorksiteEnterprisePolicy(
    val activity: ActivityKind,
    val companyId: String,
    val licenseGrossEnvelopeCents: Long,
    val operatingCostPercent: Int,
    val workerBonusPercent: Int,
    val dividendPercent: Int,
    val weeklyUpkeepCents: Long,
    val retainedReportWeeks: Int = 16,
) {
    init {
        require(ENTERPRISE_ID.matches(companyId)) { "Invalid enterprise company id: $companyId" }
        require(licenseGrossEnvelopeCents in 1..MAX_ENTERPRISE_MONEY_CENTS) {
            "Enterprise license envelope is outside the safe range"
        }
        require(operatingCostPercent in 0..100) { "Enterprise operating cost percent is invalid" }
        require(workerBonusPercent in 0..100) { "Enterprise worker bonus percent is invalid" }
        require(dividendPercent in 0..100) { "Enterprise dividend percent is invalid" }
        require(weeklyUpkeepCents in 0..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise weekly upkeep is invalid" }
        require(retainedReportWeeks in 2..52) { "Enterprise retained report weeks must be in 2..52" }
    }
}

internal data class ActiveWorksiteEnterpriseOrder(
    val worksiteId: String,
    val orderId: String,
    val sequence: Long,
    val grossTariffCents: Long,
    val reservedAt: Long,
    val businessWeekStartEpochDay: Long,
)

data class WorksiteEnterpriseTerms(
    val operatingCostPercent: Int,
    val workerBonusPercent: Int,
    val dividendPercent: Int,
    val weeklyUpkeepCents: Long,
) {
    init {
        require(operatingCostPercent in 0..100) { "Enterprise operating cost percent is invalid" }
        require(workerBonusPercent in 0..100) { "Enterprise worker bonus percent is invalid" }
        require(dividendPercent in 0..100) { "Enterprise dividend percent is invalid" }
        require(weeklyUpkeepCents in 0..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise weekly upkeep is invalid" }
    }
}

data class WorksiteEnterpriseReservation(
    val operationId: String,
    val activity: ActivityKind,
    val companyId: String,
    val worksiteId: String,
    val orderId: String,
    val sequence: Long,
    val grossTariffCents: Long,
    val reservedAt: Long,
    // Nullable only for state written before the acceptance week was snapshotted.
    val businessWeekStartEpochDay: Long? = null,
    // Nullable only for state written by the first SHADOW build before terms were snapshotted.
    val terms: WorksiteEnterpriseTerms? = null,
)

data class WorksiteEnterpriseWeek(
    val activity: ActivityKind,
    val companyId: String,
    val weekStartEpochDay: Long,
    val completedOrders: Int = 0,
    val grossRevenueCents: Long = 0,
    val operatingBurnCents: Long = 0,
    val workerBonusCents: Long = 0,
    val retainedProfitCents: Long = 0,
    val uniqueContributors: Set<UUID> = emptySet(),
    val projectedWorkerCreditsCents: Map<UUID, Long> = emptyMap(),
    // The whole business week keeps the policy accepted by its first settled order.
    val terms: WorksiteEnterpriseTerms? = null,
) {
    internal fun projectedDividendCents(policy: WorksiteEnterprisePolicy): Long {
        val accepted = terms ?: policy.terms()
        val result = (retainedProfitCents - accepted.weeklyUpkeepCents).coerceAtLeast(0)
        return percentOf(result, accepted.dividendPercent)
    }
}

data class WorksiteEnterpriseSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val reservations: Map<String, WorksiteEnterpriseReservation> = emptyMap(),
    val lastCompletedSequenceByWorksite: Map<String, Long> = emptyMap(),
    val rejectedReservationSequenceByWorksite: Map<String, Long> = emptyMap(),
    val settledGrossByCompany: Map<String, Long> = emptyMap(),
    val weeks: Map<String, WorksiteEnterpriseWeek> = emptyMap(),
    val releasedReservationsByCompany: Map<String, Long> = emptyMap(),
    val rejectedReservationsByCompany: Map<String, Long> = emptyMap(),
    val unreservedCompletionsByCompany: Map<String, Long> = emptyMap(),
    val excludedCompletionsByCompany: Map<String, Long> = emptyMap(),
    // Nullable only for snapshots written before primary funding existed.
    val financing: WorksiteEnterpriseFinancingSnapshot? = WorksiteEnterpriseFinancingSnapshot(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class WorksiteEnterpriseCompanyView(
    val activity: ActivityKind,
    val companyId: String,
    val completedOrdersThisWeek: Int,
    val uniqueContributorsThisWeek: Int,
    val grossRevenueThisWeekCents: Long,
    val operatingBurnThisWeekCents: Long,
    val workerBonusThisWeekCents: Long,
    val retainedProfitThisWeekCents: Long,
    val projectedDividendPoolCents: Long,
    val licenseGrossEnvelopeCents: Long,
    val settledGrossCents: Long,
    val reservedGrossCents: Long,
    val availableGrossCents: Long,
    val operatingCostPercent: Int,
    val workerBonusPercent: Int,
    val dividendPercent: Int,
    val weeklyUpkeepCents: Long,
)

internal enum class EnterpriseReservationOutcome {
    RESERVED,
    ALREADY_RESERVED,
    STALE_SEQUENCE,
    CONFLICT,
    ENVELOPE_EXHAUSTED,
}

internal data class EnterpriseReservationDecision(
    val outcome: EnterpriseReservationOutcome,
    val changed: Boolean,
    val availableCents: Long,
)

internal enum class EnterpriseSettlementOutcome {
    SETTLED,
    DUPLICATE,
    STALE_SEQUENCE,
    UNRESERVED,
    EXCLUDED,
}

internal data class EnterpriseSettlementDecision(
    val outcome: EnterpriseSettlementOutcome,
    val changed: Boolean,
    val grossRevenueCents: Long = 0,
    val operatingBurnCents: Long = 0,
    val workerBonusCents: Long = 0,
    val retainedProfitCents: Long = 0,
)

/**
 * Main-thread-confined, platform-neutral reservation and settlement owner.
 * It never calls Vault, Bukkit, storage, or a worksite-specific engine.
 */
internal class WorksiteEnterpriseLedger {
    private var state = WorksiteEnterpriseSnapshot()

    fun replace(snapshot: WorksiteEnterpriseSnapshot) {
        validate(snapshot)
        state = snapshot
    }

    fun snapshot(): WorksiteEnterpriseSnapshot = state

    fun companyView(
        policy: WorksiteEnterprisePolicy,
        weekStartEpochDay: Long,
    ): WorksiteEnterpriseCompanyView {
        require(weekStartEpochDay >= 0) { "Enterprise week is invalid" }
        val companyKey = companyKey(policy.activity, policy.companyId)
        val week = state.weeks[weekKey(policy.activity, policy.companyId, weekStartEpochDay)]
        val effectiveTerms = week?.terms ?: policy.terms()
        val settled = state.settledGrossByCompany[companyKey] ?: 0L
        val reserved = state.reservations.values.asSequence()
            .filter { it.companyKey() == companyKey }
            .fold(0L) { total, reservation -> safeMoneyAdd(total, reservation.grossTariffCents) }
        return WorksiteEnterpriseCompanyView(
            activity = policy.activity,
            companyId = policy.companyId,
            completedOrdersThisWeek = week?.completedOrders ?: 0,
            uniqueContributorsThisWeek = week?.uniqueContributors?.size ?: 0,
            grossRevenueThisWeekCents = week?.grossRevenueCents ?: 0,
            operatingBurnThisWeekCents = week?.operatingBurnCents ?: 0,
            workerBonusThisWeekCents = week?.workerBonusCents ?: 0,
            retainedProfitThisWeekCents = week?.retainedProfitCents ?: 0,
            projectedDividendPoolCents = week?.projectedDividendCents(policy) ?: 0,
            licenseGrossEnvelopeCents = policy.licenseGrossEnvelopeCents,
            settledGrossCents = settled,
            reservedGrossCents = reserved,
            availableGrossCents = (policy.licenseGrossEnvelopeCents - settled - reserved).coerceAtLeast(0),
            operatingCostPercent = effectiveTerms.operatingCostPercent,
            workerBonusPercent = effectiveTerms.workerBonusPercent,
            dividendPercent = effectiveTerms.dividendPercent,
            weeklyUpkeepCents = effectiveTerms.weeklyUpkeepCents,
        )
    }

    fun reserve(
        policy: WorksiteEnterprisePolicy,
        order: ActiveWorksiteEnterpriseOrder,
    ): EnterpriseReservationDecision {
        validateOrder(order)
        val worksiteKey = worksiteKey(policy.activity, order.worksiteId)
        val companyKey = companyKey(policy.activity, policy.companyId)
        val lastCompleted = state.lastCompletedSequenceByWorksite[worksiteKey] ?: -1L
        if (order.sequence <= lastCompleted) {
            return EnterpriseReservationDecision(EnterpriseReservationOutcome.STALE_SEQUENCE, false, available(policy))
        }
        val current = state.reservations[worksiteKey]
        if (current?.sequence == order.sequence) {
            val matches = current.companyId == policy.companyId && current.orderId == order.orderId &&
                current.grossTariffCents == order.grossTariffCents
            if (matches && (current.terms == null || current.businessWeekStartEpochDay == null)) {
                state = state.copy(
                    reservations = state.reservations + (
                        worksiteKey to current.copy(
                            terms = current.terms ?: acceptedTerms(policy, order.businessWeekStartEpochDay),
                            businessWeekStartEpochDay = current.businessWeekStartEpochDay
                                ?: order.businessWeekStartEpochDay,
                        )
                    ),
                )
                return EnterpriseReservationDecision(
                    EnterpriseReservationOutcome.ALREADY_RESERVED,
                    true,
                    available(policy),
                )
            }
            return EnterpriseReservationDecision(
                if (matches) EnterpriseReservationOutcome.ALREADY_RESERVED else EnterpriseReservationOutcome.CONFLICT,
                false,
                available(policy),
            )
        }
        if (current != null && current.sequence > order.sequence) {
            return EnterpriseReservationDecision(EnterpriseReservationOutcome.STALE_SEQUENCE, false, available(policy))
        }

        var changed = false
        if (current != null) {
            state = state.copy(
                reservations = state.reservations - worksiteKey,
                releasedReservationsByCompany = increment(state.releasedReservationsByCompany, current.companyKey()),
            )
            changed = true
        }
        val available = available(policy)
        if (order.grossTariffCents > available) {
            val alreadyCounted = state.rejectedReservationSequenceByWorksite[worksiteKey] == order.sequence
            state = state.copy(
                rejectedReservationSequenceByWorksite = state.rejectedReservationSequenceByWorksite +
                    (worksiteKey to order.sequence),
                rejectedReservationsByCompany = if (alreadyCounted) state.rejectedReservationsByCompany else
                    increment(state.rejectedReservationsByCompany, companyKey),
            )
            return EnterpriseReservationDecision(
                EnterpriseReservationOutcome.ENVELOPE_EXHAUSTED,
                changed || !alreadyCounted,
                available,
            )
        }

        val acceptedTerms = acceptedTerms(policy, order.businessWeekStartEpochDay)
        val reservation = WorksiteEnterpriseReservation(
            operationId = operationId(policy.activity, order.worksiteId, order.sequence),
            activity = policy.activity,
            companyId = policy.companyId,
            worksiteId = order.worksiteId,
            orderId = order.orderId,
            sequence = order.sequence,
            grossTariffCents = order.grossTariffCents,
            reservedAt = order.reservedAt,
            businessWeekStartEpochDay = order.businessWeekStartEpochDay,
            terms = acceptedTerms,
        )
        val policyWeekKey = weekKey(policy.activity, policy.companyId, order.businessWeekStartEpochDay)
        val policyWeek = state.weeks[policyWeekKey] ?: WorksiteEnterpriseWeek(
            activity = policy.activity,
            companyId = policy.companyId,
            weekStartEpochDay = order.businessWeekStartEpochDay,
            terms = acceptedTerms,
        )
        state = state.copy(
            reservations = state.reservations + (worksiteKey to reservation),
            rejectedReservationSequenceByWorksite = state.rejectedReservationSequenceByWorksite - worksiteKey,
            weeks = pruneWeeks(
                state.weeks + (policyWeekKey to policyWeek),
                policy.activity,
                policy.companyId,
                policy.retainedReportWeeks,
                state.reservations.values + reservation,
            ),
        )
        return EnterpriseReservationDecision(EnterpriseReservationOutcome.RESERVED, true, available - order.grossTariffCents)
    }

    fun settle(
        policy: WorksiteEnterprisePolicy,
        worksiteId: String,
        sequence: Long,
        weekStartEpochDay: Long,
        contributors: Map<UUID, Int>,
    ): EnterpriseSettlementDecision {
        require(weekStartEpochDay >= 0) { "Enterprise week is invalid" }
        require(contributors.isNotEmpty() && contributors.size <= MAX_ENTERPRISE_CONTRIBUTORS) {
            "Enterprise settlement must have 1..$MAX_ENTERPRISE_CONTRIBUTORS contributors"
        }
        require(contributors.values.all { it > 0 }) { "Enterprise contribution must be positive" }
        val worksiteKey = worksiteKey(policy.activity, worksiteId)
        if (sequence <= (state.lastCompletedSequenceByWorksite[worksiteKey] ?: -1L)) {
            return EnterpriseSettlementDecision(EnterpriseSettlementOutcome.DUPLICATE, false)
        }
        val reservation = state.reservations[worksiteKey]
        if (reservation != null && reservation.sequence > sequence) {
            return EnterpriseSettlementDecision(EnterpriseSettlementOutcome.STALE_SEQUENCE, false)
        }
        if (reservation == null || reservation.sequence != sequence) {
            state = state.copy(
                reservations = state.reservations - worksiteKey,
                lastCompletedSequenceByWorksite = state.lastCompletedSequenceByWorksite + (worksiteKey to sequence),
                unreservedCompletionsByCompany = increment(
                    state.unreservedCompletionsByCompany,
                    companyKey(policy.activity, policy.companyId),
                ),
            )
            return EnterpriseSettlementDecision(EnterpriseSettlementOutcome.UNRESERVED, true)
        }

        val acceptedTerms = reservation.terms ?: policy.terms()
        val acceptedWeekStart = reservation.businessWeekStartEpochDay ?: weekStartEpochDay
        val gross = reservation.grossTariffCents
        val operatingBurn = percentOf(gross, acceptedTerms.operatingCostPercent)
        val postCostMargin = gross - operatingBurn
        val workerBonus = percentOf(postCostMargin, acceptedTerms.workerBonusPercent)
        val retainedProfit = postCostMargin - workerBonus
        val credits = allocate(workerBonus, contributors)
        val companyKey = reservation.companyKey()
        val weekKey = weekKey(reservation.activity, reservation.companyId, acceptedWeekStart)
        val currentWeek = state.weeks[weekKey] ?: WorksiteEnterpriseWeek(
            activity = reservation.activity,
            companyId = reservation.companyId,
            weekStartEpochDay = acceptedWeekStart,
            terms = acceptedTerms,
        )
        val uniqueContributors = currentWeek.uniqueContributors + contributors.keys
        require(uniqueContributors.size <= MAX_ENTERPRISE_CONTRIBUTORS) {
            "Enterprise unique contributor set is unbounded"
        }
        val nextWeek = currentWeek.copy(
            completedOrders = Math.addExact(currentWeek.completedOrders, 1),
            grossRevenueCents = safeMoneyAdd(currentWeek.grossRevenueCents, gross),
            operatingBurnCents = safeMoneyAdd(currentWeek.operatingBurnCents, operatingBurn),
            workerBonusCents = safeMoneyAdd(currentWeek.workerBonusCents, workerBonus),
            retainedProfitCents = safeMoneyAdd(currentWeek.retainedProfitCents, retainedProfit),
            uniqueContributors = uniqueContributors,
            projectedWorkerCreditsCents = mergeCredits(currentWeek.projectedWorkerCreditsCents, credits),
        )
        val weeks = pruneWeeks(
            state.weeks + (weekKey to nextWeek),
            reservation.activity,
            reservation.companyId,
            policy.retainedReportWeeks,
            state.reservations.values.filterNot { it.operationId == reservation.operationId },
        )
        state = state.copy(
            reservations = state.reservations - worksiteKey,
            lastCompletedSequenceByWorksite = state.lastCompletedSequenceByWorksite + (worksiteKey to sequence),
            rejectedReservationSequenceByWorksite = state.rejectedReservationSequenceByWorksite - worksiteKey,
            settledGrossByCompany = state.settledGrossByCompany +
                (companyKey to safeMoneyAdd(state.settledGrossByCompany[companyKey] ?: 0L, gross)),
            weeks = weeks,
        )
        return EnterpriseSettlementDecision(
            EnterpriseSettlementOutcome.SETTLED,
            true,
            gross,
            operatingBurn,
            workerBonus,
            retainedProfit,
        )
    }

    fun excludeCompletion(
        policy: WorksiteEnterprisePolicy,
        worksiteId: String,
        sequence: Long,
    ): EnterpriseSettlementDecision {
        val worksiteKey = worksiteKey(policy.activity, worksiteId)
        if (sequence <= (state.lastCompletedSequenceByWorksite[worksiteKey] ?: -1L)) {
            return EnterpriseSettlementDecision(EnterpriseSettlementOutcome.DUPLICATE, false)
        }
        val reservation = state.reservations[worksiteKey]
        if (reservation != null && reservation.sequence > sequence) {
            return EnterpriseSettlementDecision(EnterpriseSettlementOutcome.STALE_SEQUENCE, false)
        }
        val companyKey = reservation
            ?.takeIf { it.sequence == sequence }
            ?.companyKey()
            ?: companyKey(policy.activity, policy.companyId)
        state = state.copy(
            reservations = state.reservations - worksiteKey,
            lastCompletedSequenceByWorksite = state.lastCompletedSequenceByWorksite + (worksiteKey to sequence),
            rejectedReservationSequenceByWorksite = state.rejectedReservationSequenceByWorksite - worksiteKey,
            excludedCompletionsByCompany = increment(
                state.excludedCompletionsByCompany,
                companyKey,
            ),
        )
        return EnterpriseSettlementDecision(EnterpriseSettlementOutcome.EXCLUDED, true)
    }

    fun reconcile(
        policy: WorksiteEnterprisePolicy,
        activeOrders: Collection<ActiveWorksiteEnterpriseOrder>,
    ): Boolean {
        require(activeOrders.map { worksiteKey(policy.activity, it.worksiteId) }.distinct().size == activeOrders.size) {
            "Enterprise reconciliation contains duplicate worksites"
        }
        activeOrders.forEach(::validateOrder)
        val activeByKey = activeOrders.associateBy { worksiteKey(policy.activity, it.worksiteId) }
        val stale = state.reservations.filter { (key, reservation) ->
            if (reservation.activity != policy.activity) return@filter false
            val active = activeByKey[key]
            active == null || active.sequence != reservation.sequence || active.orderId != reservation.orderId
        }
        if (stale.isEmpty()) return false
        var releases = state.releasedReservationsByCompany
        stale.values.groupingBy(WorksiteEnterpriseReservation::companyKey).eachCount().forEach { (companyKey, count) ->
            releases = releases + (companyKey to safeCountAdd(releases[companyKey] ?: 0L, count.toLong()))
        }
        state = state.copy(
            reservations = state.reservations - stale.keys,
            releasedReservationsByCompany = releases,
        )
        return true
    }

    fun releaseReservation(activity: ActivityKind, worksiteId: String, sequence: Long): Boolean {
        require(ENTERPRISE_ID.matches(worksiteId)) { "Invalid enterprise worksite id: $worksiteId" }
        require(sequence >= 0L) { "Enterprise order sequence is invalid" }
        val worksiteKey = worksiteKey(activity, worksiteId)
        val reservation = state.reservations[worksiteKey]?.takeIf { it.sequence == sequence } ?: return false
        state = state.copy(
            reservations = state.reservations - worksiteKey,
            releasedReservationsByCompany = increment(
                state.releasedReservationsByCompany,
                reservation.companyKey(),
            ),
        )
        return true
    }

    fun hasReservation(activity: ActivityKind, worksiteId: String, sequence: Long): Boolean =
        state.reservations[worksiteKey(activity, worksiteId)]?.sequence == sequence

    /** Discards calibration-only accounting before the first real funding round opens. */
    fun clearCompanyAccounting(activity: ActivityKind, companyId: String): Boolean {
        val companyKey = companyKey(activity, companyId)
        val reservationKeys = state.reservations.filterValues {
            it.activity == activity && it.companyId == companyId
        }.keys
        val weekKeys = state.weeks.filterValues {
            it.activity == activity && it.companyId == companyId
        }.keys
        val changed = reservationKeys.isNotEmpty() || weekKeys.isNotEmpty() ||
            listOf(
                state.settledGrossByCompany,
                state.releasedReservationsByCompany,
                state.rejectedReservationsByCompany,
                state.unreservedCompletionsByCompany,
                state.excludedCompletionsByCompany,
            ).any { companyKey in it }
        if (!changed) return false
        state = state.copy(
            reservations = state.reservations - reservationKeys,
            settledGrossByCompany = state.settledGrossByCompany - companyKey,
            weeks = state.weeks - weekKeys,
            releasedReservationsByCompany = state.releasedReservationsByCompany - companyKey,
            rejectedReservationsByCompany = state.rejectedReservationsByCompany - companyKey,
            unreservedCompletionsByCompany = state.unreservedCompletionsByCompany - companyKey,
            excludedCompletionsByCompany = state.excludedCompletionsByCompany - companyKey,
        )
        return true
    }

    /** Applies a smaller history window immediately during a safe config reload. */
    fun pruneReports(policy: WorksiteEnterprisePolicy): Boolean {
        val pruned = pruneWeeks(
            weeks = state.weeks,
            activity = policy.activity,
            companyId = policy.companyId,
            retainedReportWeeks = policy.retainedReportWeeks,
            activeReservations = state.reservations.values,
        )
        if (pruned == state.weeks) return false
        state = state.copy(weeks = pruned)
        return true
    }

    private fun available(policy: WorksiteEnterprisePolicy): Long {
        val companyKey = companyKey(policy.activity, policy.companyId)
        val settled = state.settledGrossByCompany[companyKey] ?: 0L
        val reserved = state.reservations.values.asSequence()
            .filter { it.companyKey() == companyKey }
            .fold(0L) { total, reservation -> safeMoneyAdd(total, reservation.grossTariffCents) }
        return (policy.licenseGrossEnvelopeCents - settled - reserved).coerceAtLeast(0)
    }

    private fun acceptedTerms(policy: WorksiteEnterprisePolicy, weekStartEpochDay: Long): WorksiteEnterpriseTerms =
        state.weeks[weekKey(policy.activity, policy.companyId, weekStartEpochDay)]?.terms
            ?: state.reservations.values.firstOrNull {
                it.activity == policy.activity && it.companyId == policy.companyId &&
                    it.businessWeekStartEpochDay == weekStartEpochDay && it.terms != null
            }?.terms
            ?: policy.terms()

    private fun pruneWeeks(
        weeks: Map<String, WorksiteEnterpriseWeek>,
        activity: ActivityKind,
        companyId: String,
        retainedReportWeeks: Int,
        activeReservations: Collection<WorksiteEnterpriseReservation>,
    ): Map<String, WorksiteEnterpriseWeek> {
        val companyWeeks = weeks.entries.filter {
            it.value.activity == activity && it.value.companyId == companyId
        }.sortedByDescending { it.value.weekStartEpochDay }
        if (companyWeeks.size <= retainedReportWeeks) return weeks
        val pinnedWeekStarts = activeReservations.asSequence()
            .filter { it.activity == activity && it.companyId == companyId }
            .mapNotNull(WorksiteEnterpriseReservation::businessWeekStartEpochDay)
            .toSet()
        val retainedKeys = buildSet {
            companyWeeks.asSequence()
                .filter { it.value.weekStartEpochDay in pinnedWeekStarts }
                .mapTo(this, Map.Entry<String, WorksiteEnterpriseWeek>::key)
            companyWeeks.asSequence()
                .filterNot { it.value.weekStartEpochDay in pinnedWeekStarts }
                .take(retainedReportWeeks)
                .mapTo(this, Map.Entry<String, WorksiteEnterpriseWeek>::key)
        }
        return weeks - companyWeeks.asSequence()
            .map(Map.Entry<String, WorksiteEnterpriseWeek>::key)
            .filterNot(retainedKeys::contains)
            .toSet()
    }

    private companion object {
        val ORDER_ID = Regex("[a-z0-9_-]{1,64}")

        fun validateOrder(order: ActiveWorksiteEnterpriseOrder) {
            require(ENTERPRISE_ID.matches(order.worksiteId)) { "Invalid enterprise worksite id: ${order.worksiteId}" }
            require(ORDER_ID.matches(order.orderId)) { "Invalid enterprise order id: ${order.orderId}" }
            require(order.sequence >= 0) { "Enterprise order sequence is invalid" }
            require(order.grossTariffCents in 1..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise gross tariff is invalid" }
            require(order.reservedAt >= 0) { "Enterprise reservation time is invalid" }
            require(order.businessWeekStartEpochDay >= 0) { "Enterprise reservation week is invalid" }
        }

        fun validate(snapshot: WorksiteEnterpriseSnapshot) {
            require(snapshot.schemaVersion == WorksiteEnterpriseSnapshot.SCHEMA_VERSION) {
                "Unsupported worksite enterprise schema: ${snapshot.schemaVersion}"
            }
            require(snapshot.reservations.size <= MAX_ENTERPRISE_WORKSITES) { "Enterprise reservations are unbounded" }
            require(snapshot.weeks.size <= MAX_ENTERPRISE_REPORTS) { "Enterprise weekly reports are unbounded" }
            require(snapshot.lastCompletedSequenceByWorksite.size <= MAX_ENTERPRISE_WORKSITES) {
                "Enterprise completion watermarks are unbounded"
            }
            require(snapshot.rejectedReservationSequenceByWorksite.size <= MAX_ENTERPRISE_WORKSITES) {
                "Enterprise rejected reservation watermarks are unbounded"
            }
            listOf(snapshot.lastCompletedSequenceByWorksite, snapshot.rejectedReservationSequenceByWorksite).forEach { values ->
                require(values.keys.all(::validOwnerKey) && values.values.all { it >= 0L }) {
                    "Enterprise worksite watermark map is invalid"
                }
            }
            snapshot.reservations.forEach { (key, reservation) ->
                require(key == worksiteKey(reservation.activity, reservation.worksiteId)) {
                    "Enterprise reservation key does not match its worksite"
                }
                require(reservation.operationId == operationId(reservation.activity, reservation.worksiteId, reservation.sequence)) {
                    "Enterprise reservation operation id is invalid"
                }
                validateOrder(
                    ActiveWorksiteEnterpriseOrder(
                        reservation.worksiteId,
                        reservation.orderId,
                        reservation.sequence,
                        reservation.grossTariffCents,
                        reservation.reservedAt,
                        reservation.businessWeekStartEpochDay ?: 0L,
                    ),
                )
                require(ENTERPRISE_ID.matches(reservation.companyId)) { "Enterprise reservation company is invalid" }
                reservation.businessWeekStartEpochDay?.let {
                    require(it >= 0L) { "Enterprise reservation week is invalid" }
                }
                reservation.terms?.let(::validateTerms)
            }
            snapshot.weeks.forEach { (key, week) ->
                require(key == weekKey(week.activity, week.companyId, week.weekStartEpochDay)) {
                    "Enterprise week key does not match its report"
                }
                require(week.completedOrders >= 0 && week.weekStartEpochDay >= 0) { "Enterprise week counters are invalid" }
                require(week.uniqueContributors.size <= MAX_ENTERPRISE_CONTRIBUTORS) {
                    "Enterprise unique contributor set is unbounded"
                }
                require(week.projectedWorkerCreditsCents.size <= MAX_ENTERPRISE_CONTRIBUTORS) {
                    "Enterprise projected worker credits are unbounded"
                }
                listOf(
                    week.grossRevenueCents,
                    week.operatingBurnCents,
                    week.workerBonusCents,
                    week.retainedProfitCents,
                ).forEach { require(it in 0..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise week money is invalid" } }
                require(week.projectedWorkerCreditsCents.values.all { it in 0..MAX_ENTERPRISE_MONEY_CENTS }) {
                    "Enterprise projected worker credit is invalid"
                }
                week.terms?.let(::validateTerms)
                require(
                    safeMoneyAdd(
                        safeMoneyAdd(week.operatingBurnCents, week.workerBonusCents),
                        week.retainedProfitCents,
                    ) == week.grossRevenueCents,
                ) { "Enterprise week money does not conserve gross revenue" }
                require(
                    week.projectedWorkerCreditsCents.values.fold(0L) { total, cents -> safeMoneyAdd(total, cents) } ==
                        week.workerBonusCents,
                ) {
                    "Enterprise worker credits do not conserve the worker bonus"
                }
                require(week.uniqueContributors == week.projectedWorkerCreditsCents.keys) {
                    "Enterprise contributor identities do not match worker credits"
                }
            }
            listOf(
                snapshot.settledGrossByCompany,
                snapshot.releasedReservationsByCompany,
                snapshot.rejectedReservationsByCompany,
                snapshot.unreservedCompletionsByCompany,
                snapshot.excludedCompletionsByCompany,
            ).forEach { values ->
                require(
                    values.size <= MAX_ENTERPRISE_COMPANIES && values.keys.all(::validOwnerKey) &&
                        values.values.all { it in 0..MAX_ENTERPRISE_MONEY_CENTS },
                ) {
                    "Enterprise aggregate map is invalid"
                }
            }
        }

        fun allocate(totalCents: Long, contributions: Map<UUID, Int>): Map<UUID, Long> {
            if (totalCents == 0L) return contributions.keys.associateWith { 0L }
            val totalContribution = contributions.values.sumOf(Int::toLong)
            val total = BigInteger.valueOf(totalCents)
            val denominator = BigInteger.valueOf(totalContribution)
            val shares = contributions.map { (playerId, contribution) ->
                val product = total.multiply(BigInteger.valueOf(contribution.toLong()))
                val division = product.divideAndRemainder(denominator)
                Allocation(playerId, division[0].longValueExact(), division[1])
            }
            var remainder = totalCents - shares.sumOf(Allocation::cents)
            val ordered = shares.sortedWith(compareByDescending<Allocation> { it.remainder }.thenBy { it.playerId.toString() })
            val result = shares.associateTo(linkedMapOf()) { it.playerId to it.cents }
            var index = 0
            while (remainder > 0) {
                val playerId = ordered[index++].playerId
                result[playerId] = Math.addExact(result.getValue(playerId), 1L)
                remainder--
            }
            return result
        }

        fun mergeCredits(left: Map<UUID, Long>, right: Map<UUID, Long>): Map<UUID, Long> {
            require(left.size + right.size <= MAX_ENTERPRISE_CONTRIBUTORS * 2) { "Enterprise worker credits are unbounded" }
            val merged = left.toMutableMap()
            right.forEach { (playerId, cents) -> merged[playerId] = safeMoneyAdd(merged[playerId] ?: 0L, cents) }
            require(merged.size <= MAX_ENTERPRISE_CONTRIBUTORS) { "Enterprise worker credits are unbounded" }
            return merged
        }

        fun increment(values: Map<String, Long>, key: String): Map<String, Long> =
            values + (key to safeCountAdd(values[key] ?: 0L, 1L))

        fun safeMoneyAdd(left: Long, right: Long): Long = Math.addExact(left, right).also {
            require(it in 0..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise money aggregate exceeds its safe bound" }
        }

        fun safeCountAdd(left: Long, right: Long): Long = Math.addExact(left, right).also {
            require(it in 0..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise counter exceeds its safe bound" }
        }

        fun worksiteKey(activity: ActivityKind, worksiteId: String): String = "${activity.name.lowercase()}:$worksiteId"
        fun companyKey(activity: ActivityKind, companyId: String): String = "${activity.name.lowercase()}:$companyId"
        fun operationId(activity: ActivityKind, worksiteId: String, sequence: Long): String =
            "enterprise:${activity.name.lowercase()}:$worksiteId:$sequence"
        fun weekKey(activity: ActivityKind, companyId: String, weekStartEpochDay: Long): String =
            "${companyKey(activity, companyId)}:$weekStartEpochDay"

        fun validOwnerKey(value: String): Boolean = ActivityKind.entries.any { activity ->
            val prefix = "${activity.name.lowercase()}:"
            value.startsWith(prefix) && ENTERPRISE_ID.matches(value.removePrefix(prefix))
        }

        data class Allocation(val playerId: UUID, val cents: Long, val remainder: BigInteger)

        fun validateTerms(terms: WorksiteEnterpriseTerms) {
            WorksiteEnterpriseTerms(
                operatingCostPercent = terms.operatingCostPercent,
                workerBonusPercent = terms.workerBonusPercent,
                dividendPercent = terms.dividendPercent,
                weeklyUpkeepCents = terms.weeklyUpkeepCents,
            )
        }
    }
}

private fun WorksiteEnterpriseReservation.companyKey(): String = "${activity.name.lowercase()}:$companyId"

private fun WorksiteEnterprisePolicy.terms(): WorksiteEnterpriseTerms = WorksiteEnterpriseTerms(
    operatingCostPercent = operatingCostPercent,
    workerBonusPercent = workerBonusPercent,
    dividendPercent = dividendPercent,
    weeklyUpkeepCents = weeklyUpkeepCents,
)

private fun percentOf(value: Long, percent: Int): Long = Math.multiplyExact(value, percent.toLong()) / 100L

internal const val MAX_ENTERPRISE_MONEY_CENTS = 1_000_000_000_000_000L
internal const val MAX_ENTERPRISE_CONTRIBUTORS = 10_000
private val ENTERPRISE_ID = Regex("[a-z0-9_-]{1,48}")
private const val MAX_ENTERPRISE_WORKSITES = 1_024
private const val MAX_ENTERPRISE_COMPANIES = 256
private const val MAX_ENTERPRISE_REPORTS = 13_312

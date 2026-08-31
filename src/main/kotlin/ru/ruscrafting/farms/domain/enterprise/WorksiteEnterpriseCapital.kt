package ru.ruscrafting.farms.domain.enterprise

import ru.ruscrafting.farms.domain.ActivityKind
import java.math.BigInteger
import java.util.UUID

internal data class WorksiteEnterpriseCapitalPolicy(
    val activity: ActivityKind,
    val companyId: String,
    val totalShares: Int,
    val sharePriceCents: Long,
    val maxSharesPerOwner: Int,
    val fundingDurationMillis: Long,
    val licenseBurnPercent: Int,
    val licenseWeeks: Int,
    val reserveTargetWeeks: Int,
    val retainedReportWeeks: Int,
    val defaultDividendPercent: Int,
    val defaultWeeklyUpkeepCents: Long,
) {
    init {
        require(ENTERPRISE_CAPITAL_ID.matches(companyId)) { "Invalid enterprise company id: $companyId" }
        require(totalShares in 1..MAX_ENTERPRISE_SHARES) { "Enterprise share count is invalid" }
        require(sharePriceCents in 1..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise share price is invalid" }
        require(maxSharesPerOwner in 1..totalShares) { "Enterprise owner limit is invalid" }
        require(fundingDurationMillis in 60_000L..MAX_FUNDING_DURATION_MILLIS) {
            "Enterprise funding duration is invalid"
        }
        require(licenseBurnPercent in 1..99) { "Enterprise license burn percent is invalid" }
        require(licenseWeeks in 1..52) { "Enterprise license duration is invalid" }
        require(reserveTargetWeeks in setOf(1, 2, 4)) { "Enterprise reserve target is invalid" }
        require(retainedReportWeeks in 2..52) { "Enterprise report retention is invalid" }
        require(defaultDividendPercent in 0..100) { "Enterprise default dividend percent is invalid" }
        require(defaultWeeklyUpkeepCents in 0..MAX_ENTERPRISE_MONEY_CENTS) {
            "Enterprise default weekly upkeep is invalid"
        }
        capitalCents()
    }

    fun capitalCents(): Long = safeCapitalMultiply(sharePriceCents, totalShares)
}

enum class WorksiteEnterpriseCapitalPhase { FUNDING, ACTIVE, CANCELLED, EXPIRED }

enum class WorksiteEnterpriseMoneyOperationKind { SHARE_PURCHASE, ACCOUNT_WITHDRAWAL }

enum class WorksiteEnterpriseMoneyOperationState { PREPARED, APPLIED, REJECTED, MANUAL_REVIEW }

data class WorksiteEnterpriseCapitalCompany(
    val activity: ActivityKind,
    val companyId: String,
    val phase: WorksiteEnterpriseCapitalPhase,
    val fundingOpenedAt: Long,
    val fundingClosesAt: Long,
    val totalShares: Int,
    val sharePriceCents: Long,
    val maxSharesPerOwner: Int,
    val licenseBurnPercent: Int,
    val licenseWeeks: Int,
    val reserveTargetWeeks: Int,
    val issuedShares: Int = 0,
    val shareholdings: Map<UUID, Int> = emptyMap(),
    val escrowCents: Long = 0,
    val treasuryCents: Long = 0,
    val activatedWeekStartEpochDay: Long? = null,
    val licenseEndsWeekStartEpochDay: Long? = null,
    val lastClosedWeekStartEpochDay: Long? = null,
)

data class WorksiteEnterpriseMoneyOperation(
    val operationId: String,
    val kind: WorksiteEnterpriseMoneyOperationKind,
    val state: WorksiteEnterpriseMoneyOperationState,
    val activity: ActivityKind,
    val companyId: String,
    val playerId: UUID,
    val shares: Int,
    val amountCents: Long,
    val createdAt: Long,
)

data class WorksiteEnterpriseDistribution(
    val activity: ActivityKind,
    val companyId: String,
    val weekStartEpochDay: Long,
    val completedOrders: Int,
    val uniqueContributors: Int,
    val grossRevenueCents: Long,
    val operatingBurnCents: Long,
    val workerBonusCents: Long,
    val retainedProfitCents: Long,
    val upkeepBurnCents: Long,
    val dividendPoolCents: Long,
    val dividendPerShareCents: Long,
    val roundingBurnCents: Long,
    val treasuryBeforeCents: Long,
    val treasuryAfterCents: Long,
)

data class WorksiteEnterpriseFinancingSnapshot(
    val companies: Map<String, WorksiteEnterpriseCapitalCompany> = emptyMap(),
    val investmentCreditsCents: Map<UUID, Long> = emptyMap(),
    val operations: Map<String, WorksiteEnterpriseMoneyOperation> = emptyMap(),
    val distributions: Map<String, WorksiteEnterpriseDistribution> = emptyMap(),
)

data class WorksiteEnterpriseOwnershipView(
    val phase: WorksiteEnterpriseCapitalPhase,
    val fundingClosesAt: Long,
    val totalShares: Int,
    val issuedShares: Int,
    val reservedShares: Int,
    val ownedShares: Int,
    val sharePriceCents: Long,
    val maxSharesPerOwner: Int,
    val treasuryCents: Long,
    val accountBalanceCents: Long,
    val accountAvailableCents: Long,
    val pendingManualReviewCount: Int,
)

internal enum class EnterpriseMoneyPreparationOutcome {
    PREPARED,
    DUPLICATE,
    NOT_AVAILABLE,
    FUNDING_CLOSED,
    OWNER_LIMIT,
    SOLD_OUT,
    INSUFFICIENT_CREDIT,
}

internal data class EnterpriseMoneyPreparation(
    val outcome: EnterpriseMoneyPreparationOutcome,
    val operationId: String? = null,
    val amountCents: Long = 0,
)

internal enum class EnterpriseProviderOutcome { SUCCESS, REJECTED, UNKNOWN }

/** Platform-neutral funding, ownership, weekly distribution and claim ledger. */
internal class WorksiteEnterpriseCapitalLedger {
    private var state = WorksiteEnterpriseFinancingSnapshot()

    fun replace(snapshot: WorksiteEnterpriseFinancingSnapshot?) {
        val normalized = snapshot ?: WorksiteEnterpriseFinancingSnapshot()
        validate(normalized)
        state = normalized
    }

    fun snapshot(): WorksiteEnterpriseFinancingSnapshot = state

    fun recoverPreparedOperations(): Boolean {
        val prepared = state.operations.filterValues { it.state == WorksiteEnterpriseMoneyOperationState.PREPARED }
        if (prepared.isEmpty()) return false
        state = state.copy(
            operations = state.operations + prepared.mapValues { (_, operation) ->
                operation.copy(state = WorksiteEnterpriseMoneyOperationState.MANUAL_REVIEW)
            },
        )
        return true
    }

    fun ensureCompany(
        policy: WorksiteEnterpriseCapitalPolicy,
        now: Long,
    ): Boolean {
        require(now >= 0L) { "Enterprise funding time is invalid" }
        val key = companyKey(policy.activity, policy.companyId)
        if (state.companies.containsKey(key)) return false
        val closesAt = Math.addExact(now, policy.fundingDurationMillis)
        val company = WorksiteEnterpriseCapitalCompany(
            activity = policy.activity,
            companyId = policy.companyId,
            phase = WorksiteEnterpriseCapitalPhase.FUNDING,
            fundingOpenedAt = now,
            fundingClosesAt = closesAt,
            totalShares = policy.totalShares,
            sharePriceCents = policy.sharePriceCents,
            maxSharesPerOwner = policy.maxSharesPerOwner,
            licenseBurnPercent = policy.licenseBurnPercent,
            licenseWeeks = policy.licenseWeeks,
            reserveTargetWeeks = policy.reserveTargetWeeks,
        )
        state = state.copy(companies = state.companies + (key to company))
        return true
    }

    fun view(activity: ActivityKind, companyId: String, playerId: UUID): WorksiteEnterpriseOwnershipView? {
        val key = companyKey(activity, companyId)
        val company = state.companies[key] ?: return null
        val unresolved = unresolvedOperations(activity, companyId)
        val reservedShares = unresolved.asSequence()
            .filter { it.kind == WorksiteEnterpriseMoneyOperationKind.SHARE_PURCHASE }
            .sumOf(WorksiteEnterpriseMoneyOperation::shares)
        val reservedCredit = unresolved.asSequence()
            .filter {
                it.kind == WorksiteEnterpriseMoneyOperationKind.ACCOUNT_WITHDRAWAL && it.playerId == playerId
            }
            .sumOf(WorksiteEnterpriseMoneyOperation::amountCents)
        val balance = state.investmentCreditsCents[playerId] ?: 0L
        return WorksiteEnterpriseOwnershipView(
            phase = company.phase,
            fundingClosesAt = company.fundingClosesAt,
            totalShares = company.totalShares,
            issuedShares = company.issuedShares,
            reservedShares = reservedShares,
            ownedShares = company.shareholdings[playerId] ?: 0,
            sharePriceCents = company.sharePriceCents,
            maxSharesPerOwner = company.maxSharesPerOwner,
            treasuryCents = company.treasuryCents,
            accountBalanceCents = balance,
            accountAvailableCents = (balance - reservedCredit).coerceAtLeast(0L),
            pendingManualReviewCount = unresolved.count {
                it.playerId == playerId && it.state == WorksiteEnterpriseMoneyOperationState.MANUAL_REVIEW
            },
        )
    }

    fun isCommerciallyActive(activity: ActivityKind, companyId: String, weekStartEpochDay: Long): Boolean {
        val company = state.companies[companyKey(activity, companyId)] ?: return false
        val licenseEnd = company.licenseEndsWeekStartEpochDay ?: return false
        return company.phase == WorksiteEnterpriseCapitalPhase.ACTIVE && weekStartEpochDay < licenseEnd
    }

    fun hasCompany(activity: ActivityKind, companyId: String): Boolean =
        state.companies.containsKey(companyKey(activity, companyId))

    fun prepareSharePurchase(
        activity: ActivityKind,
        companyId: String,
        playerId: UUID,
        shares: Int,
        operationId: String,
        now: Long,
    ): EnterpriseMoneyPreparation {
        validateOperationId(operationId)
        state.operations[operationId]?.let { existing ->
            return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.DUPLICATE, existing.operationId, existing.amountCents)
        }
        val key = companyKey(activity, companyId)
        val company = state.companies[key]
            ?: return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.NOT_AVAILABLE)
        if (company.phase != WorksiteEnterpriseCapitalPhase.FUNDING || now >= company.fundingClosesAt) {
            return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.FUNDING_CLOSED)
        }
        require(shares > 0) { "Enterprise purchase share count must be positive" }
        val unresolved = unresolvedOperations(activity, companyId)
        if (unresolved.any {
                it.playerId == playerId && it.kind == WorksiteEnterpriseMoneyOperationKind.SHARE_PURCHASE
            }
        ) return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.DUPLICATE)
        val ownerShares = company.shareholdings[playerId] ?: 0
        val ownerReserved = unresolved.filter { it.playerId == playerId }.sumOf(WorksiteEnterpriseMoneyOperation::shares)
        if (ownerShares + ownerReserved + shares > company.maxSharesPerOwner) {
            return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.OWNER_LIMIT)
        }
        val reservedShares = unresolved.sumOf(WorksiteEnterpriseMoneyOperation::shares)
        if (company.issuedShares + reservedShares + shares > company.totalShares) {
            return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.SOLD_OUT)
        }
        val amount = safeCapitalMultiply(company.sharePriceCents, shares)
        val operation = WorksiteEnterpriseMoneyOperation(
            operationId = operationId,
            kind = WorksiteEnterpriseMoneyOperationKind.SHARE_PURCHASE,
            state = WorksiteEnterpriseMoneyOperationState.PREPARED,
            activity = activity,
            companyId = companyId,
            playerId = playerId,
            shares = shares,
            amountCents = amount,
            createdAt = now,
        )
        state = state.copy(operations = appendOperation(operation))
        return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.PREPARED, operationId, amount)
    }

    fun prepareAccountWithdrawal(
        activity: ActivityKind,
        companyId: String,
        playerId: UUID,
        operationId: String,
        now: Long,
    ): EnterpriseMoneyPreparation {
        validateOperationId(operationId)
        state.operations[operationId]?.let { existing ->
            return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.DUPLICATE, existing.operationId, existing.amountCents)
        }
        if (!state.companies.containsKey(companyKey(activity, companyId))) {
            return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.NOT_AVAILABLE)
        }
        val unresolved = unresolvedOperations(activity, companyId)
        if (unresolved.any {
                it.playerId == playerId && it.kind == WorksiteEnterpriseMoneyOperationKind.ACCOUNT_WITHDRAWAL
            }
        ) return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.DUPLICATE)
        val balance = state.investmentCreditsCents[playerId] ?: 0L
        val reserved = unresolved.asSequence()
            .filter { it.playerId == playerId && it.kind == WorksiteEnterpriseMoneyOperationKind.ACCOUNT_WITHDRAWAL }
            .sumOf(WorksiteEnterpriseMoneyOperation::amountCents)
        val available = balance - reserved
        if (available <= 0L) return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.INSUFFICIENT_CREDIT)
        val operation = WorksiteEnterpriseMoneyOperation(
            operationId = operationId,
            kind = WorksiteEnterpriseMoneyOperationKind.ACCOUNT_WITHDRAWAL,
            state = WorksiteEnterpriseMoneyOperationState.PREPARED,
            activity = activity,
            companyId = companyId,
            playerId = playerId,
            shares = 0,
            amountCents = available,
            createdAt = now,
        )
        state = state.copy(operations = appendOperation(operation))
        return EnterpriseMoneyPreparation(EnterpriseMoneyPreparationOutcome.PREPARED, operationId, available)
    }

    fun completeMoneyOperation(
        operationId: String,
        providerOutcome: EnterpriseProviderOutcome,
        businessWeekStartEpochDay: Long,
    ): WorksiteEnterpriseMoneyOperation? {
        val operation = state.operations[operationId] ?: return null
        if (operation.state != WorksiteEnterpriseMoneyOperationState.PREPARED) return operation
        val nextState = when (providerOutcome) {
            EnterpriseProviderOutcome.REJECTED -> WorksiteEnterpriseMoneyOperationState.REJECTED
            EnterpriseProviderOutcome.UNKNOWN -> WorksiteEnterpriseMoneyOperationState.MANUAL_REVIEW
            EnterpriseProviderOutcome.SUCCESS -> WorksiteEnterpriseMoneyOperationState.APPLIED
        }
        if (providerOutcome != EnterpriseProviderOutcome.SUCCESS) {
            val completed = operation.copy(state = nextState)
            state = state.copy(operations = state.operations + (operationId to completed))
            return completed
        }
        when (operation.kind) {
            WorksiteEnterpriseMoneyOperationKind.SHARE_PURCHASE -> applySharePurchase(operation, businessWeekStartEpochDay)
            WorksiteEnterpriseMoneyOperationKind.ACCOUNT_WITHDRAWAL -> applyAccountWithdrawal(operation)
        }
        val completed = operation.copy(state = WorksiteEnterpriseMoneyOperationState.APPLIED)
        state = state.copy(operations = state.operations + (operationId to completed))
        return completed
    }

    fun advance(
        policy: WorksiteEnterpriseCapitalPolicy,
        now: Long,
        currentWeekStartEpochDay: Long,
        revenueSnapshot: WorksiteEnterpriseSnapshot,
        allowCreate: Boolean = true,
    ): Boolean {
        val key = companyKey(policy.activity, policy.companyId)
        if (!state.companies.containsKey(key) && !allowCreate) return false
        var changed = ensureCompany(policy, now)
        var company = state.companies.getValue(key)
        requireCompatibleCapitalTerms(company, policy)
        if (company.phase == WorksiteEnterpriseCapitalPhase.FUNDING && now >= company.fundingClosesAt) {
            val unresolvedPurchases = unresolvedOperations(policy.activity, policy.companyId)
                .any { it.kind == WorksiteEnterpriseMoneyOperationKind.SHARE_PURCHASE }
            if (!unresolvedPurchases && company.issuedShares < company.totalShares) {
                refundCancelledFunding(company)
                company = state.companies.getValue(key)
                changed = true
            }
        }
        if (company.phase == WorksiteEnterpriseCapitalPhase.ACTIVE) {
            if (closeFinishedWeeks(company, currentWeekStartEpochDay, revenueSnapshot, policy)) {
                company = state.companies.getValue(key)
                changed = true
            }
            val licenseEnd = company.licenseEndsWeekStartEpochDay
            val hasReservations = revenueSnapshot.reservations.values.any {
                it.activity == company.activity && it.companyId == company.companyId
            }
            if (licenseEnd != null && currentWeekStartEpochDay >= licenseEnd && !hasReservations &&
                company.lastClosedWeekStartEpochDay == licenseEnd - DAYS_PER_WEEK
            ) {
                state = state.copy(companies = state.companies + (key to company.copy(phase = WorksiteEnterpriseCapitalPhase.EXPIRED)))
                changed = true
            }
        }
        return changed
    }

    private fun requireCompatibleCapitalTerms(
        company: WorksiteEnterpriseCapitalCompany,
        policy: WorksiteEnterpriseCapitalPolicy,
    ) {
        require(
            company.totalShares == policy.totalShares &&
                company.sharePriceCents == policy.sharePriceCents &&
                company.maxSharesPerOwner == policy.maxSharesPerOwner &&
                company.licenseBurnPercent == policy.licenseBurnPercent &&
                company.licenseWeeks == policy.licenseWeeks &&
                company.reserveTargetWeeks == policy.reserveTargetWeeks,
        ) { "Enterprise capital terms are immutable after funding opens" }
    }

    private fun applySharePurchase(operation: WorksiteEnterpriseMoneyOperation, currentWeekStartEpochDay: Long) {
        val key = companyKey(operation.activity, operation.companyId)
        val company = requireNotNull(state.companies[key]) { "Enterprise purchase company disappeared" }
        check(company.phase == WorksiteEnterpriseCapitalPhase.FUNDING) { "Enterprise funding is closed" }
        val owned = company.shareholdings[operation.playerId] ?: 0
        val issued = Math.addExact(company.issuedShares, operation.shares)
        check(owned + operation.shares <= company.maxSharesPerOwner && issued <= company.totalShares) {
            "Enterprise purchase reservation drifted"
        }
        val escrow = safeCapitalAdd(company.escrowCents, operation.amountCents)
        var updated = company.copy(
            issuedShares = issued,
            shareholdings = company.shareholdings + (operation.playerId to Math.addExact(owned, operation.shares)),
            escrowCents = escrow,
        )
        if (issued == company.totalShares) {
            val expectedCapital = safeCapitalMultiply(company.sharePriceCents, company.totalShares)
            check(escrow == expectedCapital) { "Enterprise escrow does not match fully issued capital" }
            val licenseBurn = percentOfCapital(expectedCapital, company.licenseBurnPercent)
            updated = updated.copy(
                phase = WorksiteEnterpriseCapitalPhase.ACTIVE,
                escrowCents = 0,
                treasuryCents = expectedCapital - licenseBurn,
                activatedWeekStartEpochDay = currentWeekStartEpochDay,
                licenseEndsWeekStartEpochDay = Math.addExact(
                    currentWeekStartEpochDay,
                    Math.multiplyExact(company.licenseWeeks.toLong(), DAYS_PER_WEEK),
                ),
            )
        }
        state = state.copy(companies = state.companies + (key to updated))
    }

    private fun applyAccountWithdrawal(operation: WorksiteEnterpriseMoneyOperation) {
        val balance = state.investmentCreditsCents[operation.playerId] ?: 0L
        check(balance >= operation.amountCents) { "Enterprise investment credit reservation drifted" }
        val remaining = balance - operation.amountCents
        state = state.copy(
            investmentCreditsCents = if (remaining == 0L) {
                state.investmentCreditsCents - operation.playerId
            } else {
                state.investmentCreditsCents + (operation.playerId to remaining)
            },
        )
    }

    private fun refundCancelledFunding(company: WorksiteEnterpriseCapitalCompany) {
        var credits = state.investmentCreditsCents
        company.shareholdings.forEach { (playerId, shares) ->
            credits = addCredit(credits, playerId, safeCapitalMultiply(company.sharePriceCents, shares))
        }
        val expectedEscrow = safeCapitalMultiply(company.sharePriceCents, company.issuedShares)
        check(expectedEscrow == company.escrowCents) { "Enterprise cancelled escrow does not conserve purchases" }
        val key = companyKey(company.activity, company.companyId)
        state = state.copy(
            companies = state.companies + (
                key to company.copy(
                    phase = WorksiteEnterpriseCapitalPhase.CANCELLED,
                    issuedShares = 0,
                    shareholdings = emptyMap(),
                    escrowCents = 0,
                )
            ),
            investmentCreditsCents = credits,
        )
    }

    private fun closeFinishedWeeks(
        company: WorksiteEnterpriseCapitalCompany,
        currentWeekStartEpochDay: Long,
        revenueSnapshot: WorksiteEnterpriseSnapshot,
        policy: WorksiteEnterpriseCapitalPolicy,
    ): Boolean {
        val firstWeek = company.activatedWeekStartEpochDay ?: return false
        val licenseEnd = company.licenseEndsWeekStartEpochDay ?: return false
        var weekStart = company.lastClosedWeekStartEpochDay?.let { Math.addExact(it, DAYS_PER_WEEK) } ?: firstWeek
        val closeBefore = minOf(currentWeekStartEpochDay, licenseEnd)
        var changed = false
        var current = company
        while (weekStart < closeBefore) {
            val pinned = revenueSnapshot.reservations.values.any {
                it.activity == company.activity && it.companyId == company.companyId &&
                    it.businessWeekStartEpochDay == weekStart
            }
            if (pinned) break
            val revenue = revenueSnapshot.weeks[weekKey(company.activity, company.companyId, weekStart)]
            current = distributeWeek(current, weekStart, revenue, policy, policy.retainedReportWeeks)
            changed = true
            weekStart = Math.addExact(weekStart, DAYS_PER_WEEK)
        }
        return changed
    }

    private fun distributeWeek(
        company: WorksiteEnterpriseCapitalCompany,
        weekStart: Long,
        revenue: WorksiteEnterpriseWeek?,
        policy: WorksiteEnterpriseCapitalPolicy,
        retainedReportWeeks: Int,
    ): WorksiteEnterpriseCapitalCompany {
        val treasuryBefore = company.treasuryCents
        val retained = revenue?.retainedProfitCents ?: 0L
        val afterRevenue = safeCapitalAdd(treasuryBefore, retained)
        val configuredUpkeep = revenue?.terms?.weeklyUpkeepCents ?: policy.defaultWeeklyUpkeepCents
        val upkeep = minOf(afterRevenue, configuredUpkeep)
        val afterUpkeep = afterRevenue - upkeep
        val reserveTarget = safeCapitalMultiply(configuredUpkeep, company.reserveTargetWeeks)
        val profitAfterUpkeep = (retained - configuredUpkeep).coerceAtLeast(0L)
        val distributable = minOf(profitAfterUpkeep, (afterUpkeep - reserveTarget).coerceAtLeast(0L))
        val dividendRatio = revenue?.terms?.dividendPercent ?: policy.defaultDividendPercent
        val dividendPool = percentOfCapital(distributable, dividendRatio)
        val dividendPerShare = if (company.totalShares == 0) 0L else dividendPool / company.totalShares
        var creditedDividends = 0L
        var credits = state.investmentCreditsCents
        company.shareholdings.forEach { (playerId, shares) ->
            val credit = safeCapitalMultiply(dividendPerShare, shares)
            creditedDividends = safeCapitalAdd(creditedDividends, credit)
            credits = addCredit(credits, playerId, credit)
        }
        revenue?.projectedWorkerCreditsCents?.forEach { (playerId, credit) ->
            credits = addCredit(credits, playerId, credit)
        }
        val roundingBurn = dividendPool - creditedDividends
        val treasuryAfter = afterUpkeep - dividendPool
        val distribution = WorksiteEnterpriseDistribution(
            activity = company.activity,
            companyId = company.companyId,
            weekStartEpochDay = weekStart,
            completedOrders = revenue?.completedOrders ?: 0,
            uniqueContributors = revenue?.uniqueContributors?.size ?: 0,
            grossRevenueCents = revenue?.grossRevenueCents ?: 0L,
            operatingBurnCents = revenue?.operatingBurnCents ?: 0L,
            workerBonusCents = revenue?.workerBonusCents ?: 0L,
            retainedProfitCents = retained,
            upkeepBurnCents = upkeep,
            dividendPoolCents = dividendPool,
            dividendPerShareCents = dividendPerShare,
            roundingBurnCents = roundingBurn,
            treasuryBeforeCents = treasuryBefore,
            treasuryAfterCents = treasuryAfter,
        )
        val key = companyKey(company.activity, company.companyId)
        val reportKey = weekKey(company.activity, company.companyId, weekStart)
        val reports = pruneDistributions(state.distributions + (reportKey to distribution), key, retainedReportWeeks)
        val updated = company.copy(treasuryCents = treasuryAfter, lastClosedWeekStartEpochDay = weekStart)
        state = state.copy(
            companies = state.companies + (key to updated),
            investmentCreditsCents = credits,
            distributions = reports,
        )
        return updated
    }

    private fun appendOperation(operation: WorksiteEnterpriseMoneyOperation): Map<String, WorksiteEnterpriseMoneyOperation> {
        val next = state.operations + (operation.operationId to operation)
        if (next.size <= MAX_ENTERPRISE_OPERATIONS) return next
        val removable = next.values.asSequence()
            .filter { it.state in TERMINAL_OPERATION_STATES }
            .sortedBy(WorksiteEnterpriseMoneyOperation::createdAt)
            .take(next.size - MAX_ENTERPRISE_OPERATIONS)
            .map(WorksiteEnterpriseMoneyOperation::operationId)
            .toSet()
        require(removable.size == next.size - MAX_ENTERPRISE_OPERATIONS) {
            "Enterprise unresolved operation journal is full"
        }
        return next - removable
    }

    private fun unresolvedOperations(activity: ActivityKind, companyId: String) = state.operations.values.filter {
        it.activity == activity && it.companyId == companyId &&
            it.state in UNRESOLVED_OPERATION_STATES
    }

    private companion object {
        val TERMINAL_OPERATION_STATES = setOf(
            WorksiteEnterpriseMoneyOperationState.APPLIED,
            WorksiteEnterpriseMoneyOperationState.REJECTED,
        )
        val UNRESOLVED_OPERATION_STATES = setOf(
            WorksiteEnterpriseMoneyOperationState.PREPARED,
            WorksiteEnterpriseMoneyOperationState.MANUAL_REVIEW,
        )

        fun validate(snapshot: WorksiteEnterpriseFinancingSnapshot) {
            require(snapshot.companies.size <= MAX_ENTERPRISE_CAPITAL_COMPANIES) {
                "Enterprise capital companies are unbounded"
            }
            require(snapshot.operations.size <= MAX_ENTERPRISE_OPERATIONS) { "Enterprise operations are unbounded" }
            require(snapshot.distributions.size <= MAX_ENTERPRISE_DISTRIBUTIONS) {
                "Enterprise distributions are unbounded"
            }
            require(snapshot.investmentCreditsCents.size <= MAX_ENTERPRISE_INVESTORS) {
                "Enterprise investment accounts are unbounded"
            }
            require(snapshot.investmentCreditsCents.values.all { it in 0..MAX_ENTERPRISE_MONEY_CENTS }) {
                "Enterprise investment credit is invalid"
            }
            snapshot.companies.forEach { (key, company) ->
                require(key == companyKey(company.activity, company.companyId)) { "Enterprise capital key is invalid" }
                require(ENTERPRISE_CAPITAL_ID.matches(company.companyId)) { "Enterprise capital company id is invalid" }
                require(company.totalShares in 1..MAX_ENTERPRISE_SHARES) { "Enterprise capital share count is invalid" }
                require(company.sharePriceCents in 1..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise capital price is invalid" }
                require(company.maxSharesPerOwner in 1..company.totalShares) { "Enterprise capital owner limit is invalid" }
                require(company.issuedShares == company.shareholdings.values.sum()) { "Enterprise issued shares drifted" }
                require(company.issuedShares in 0..company.totalShares) { "Enterprise issued shares are invalid" }
                require(company.shareholdings.size <= MAX_ENTERPRISE_SHARES &&
                    company.shareholdings.values.all { it in 1..company.maxSharesPerOwner }
                ) { "Enterprise share registry is invalid" }
                listOf(company.escrowCents, company.treasuryCents).forEach {
                    require(it in 0..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise capital money is invalid" }
                }
                require(company.fundingOpenedAt >= 0L && company.fundingClosesAt > company.fundingOpenedAt) {
                    "Enterprise funding window is invalid"
                }
                require(company.licenseBurnPercent in 1..99 && company.licenseWeeks in 1..52) {
                    "Enterprise license terms are invalid"
                }
                require(company.reserveTargetWeeks in setOf(1, 2, 4)) { "Enterprise reserve target is invalid" }
                if (company.phase == WorksiteEnterpriseCapitalPhase.FUNDING) {
                    require(company.escrowCents == safeCapitalMultiply(company.sharePriceCents, company.issuedShares)) {
                        "Enterprise funding escrow does not conserve purchases"
                    }
                }
                if (company.phase == WorksiteEnterpriseCapitalPhase.ACTIVE ||
                    company.phase == WorksiteEnterpriseCapitalPhase.EXPIRED
                ) {
                    require(company.issuedShares == company.totalShares && company.escrowCents == 0L) {
                        "Enterprise active capital is incomplete"
                    }
                    require(company.activatedWeekStartEpochDay != null && company.licenseEndsWeekStartEpochDay != null) {
                        "Enterprise active license dates are missing"
                    }
                }
            }
            snapshot.operations.forEach { (key, operation) ->
                require(key == operation.operationId) { "Enterprise operation key is invalid" }
                validateOperationId(operation.operationId)
                require(ENTERPRISE_CAPITAL_ID.matches(operation.companyId)) { "Enterprise operation company is invalid" }
                require(operation.amountCents in 1..MAX_ENTERPRISE_MONEY_CENTS && operation.createdAt >= 0L) {
                    "Enterprise operation amount or time is invalid"
                }
                when (operation.kind) {
                    WorksiteEnterpriseMoneyOperationKind.SHARE_PURCHASE -> require(operation.shares in 1..MAX_ENTERPRISE_SHARES)
                    WorksiteEnterpriseMoneyOperationKind.ACCOUNT_WITHDRAWAL -> require(operation.shares == 0)
                }
            }
            snapshot.distributions.forEach { (key, report) ->
                require(key == weekKey(report.activity, report.companyId, report.weekStartEpochDay)) {
                    "Enterprise distribution key is invalid"
                }
                require(report.weekStartEpochDay >= 0L && report.completedOrders >= 0 && report.uniqueContributors >= 0) {
                    "Enterprise distribution counters are invalid"
                }
                listOf(
                    report.grossRevenueCents,
                    report.operatingBurnCents,
                    report.workerBonusCents,
                    report.retainedProfitCents,
                    report.upkeepBurnCents,
                    report.dividendPoolCents,
                    report.dividendPerShareCents,
                    report.roundingBurnCents,
                    report.treasuryBeforeCents,
                    report.treasuryAfterCents,
                ).forEach { require(it in 0..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise distribution money is invalid" } }
            }
        }

        fun validateOperationId(operationId: String) {
            require(ENTERPRISE_OPERATION_ID.matches(operationId)) { "Invalid enterprise operation id" }
        }

        fun pruneDistributions(
            reports: Map<String, WorksiteEnterpriseDistribution>,
            companyKey: String,
            retainedWeeks: Int,
        ): Map<String, WorksiteEnterpriseDistribution> {
            val owned = reports.entries.filter { it.key.startsWith("$companyKey:") }
                .sortedByDescending { it.value.weekStartEpochDay }
            return reports - owned.drop(retainedWeeks).map(Map.Entry<String, WorksiteEnterpriseDistribution>::key).toSet()
        }

        fun addCredit(values: Map<UUID, Long>, playerId: UUID, amount: Long): Map<UUID, Long> {
            if (amount == 0L) return values
            require(values.size < MAX_ENTERPRISE_INVESTORS || playerId in values) { "Enterprise investment accounts are unbounded" }
            return values + (playerId to safeCapitalAdd(values[playerId] ?: 0L, amount))
        }
    }
}

private fun companyKey(activity: ActivityKind, companyId: String): String = "${activity.name.lowercase()}:$companyId"

private fun weekKey(activity: ActivityKind, companyId: String, weekStartEpochDay: Long): String =
    "${companyKey(activity, companyId)}:$weekStartEpochDay"

private fun safeCapitalAdd(left: Long, right: Long): Long = Math.addExact(left, right).also {
    require(it in 0..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise money exceeds its safe bound" }
}

private fun safeCapitalMultiply(cents: Long, multiplier: Int): Long =
    BigInteger.valueOf(cents).multiply(BigInteger.valueOf(multiplier.toLong())).longValueExact().also {
        require(it in 0..MAX_ENTERPRISE_MONEY_CENTS) { "Enterprise money exceeds its safe bound" }
    }

private fun percentOfCapital(value: Long, percent: Int): Long =
    BigInteger.valueOf(value).multiply(BigInteger.valueOf(percent.toLong())).divide(BigInteger.valueOf(100)).longValueExact()

private val ENTERPRISE_CAPITAL_ID = Regex("[a-z0-9_-]{1,48}")
private val ENTERPRISE_OPERATION_ID = Regex("enterprise:(buy|withdraw):[a-z0-9_-]{1,48}:[0-9a-f-]{36}")
private const val DAYS_PER_WEEK = 7L
private const val MAX_FUNDING_DURATION_MILLIS = 31L * 24L * 60L * 60L * 1_000L
private const val MAX_ENTERPRISE_SHARES = 10_000
private const val MAX_ENTERPRISE_CAPITAL_COMPANIES = 256
private const val MAX_ENTERPRISE_OPERATIONS = 10_000
private const val MAX_ENTERPRISE_DISTRIBUTIONS = 13_312
private const val MAX_ENTERPRISE_INVESTORS = 1_000_000

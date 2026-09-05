package ru.ruscrafting.farms.paper.enterprise

import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.WorksiteEnterpriseMode
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.enterprise.*
import java.time.LocalDate
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseCompanyView
import ru.ruscrafting.farms.domain.enterprise.EnterpriseMoneyPreparationOutcome
import ru.ruscrafting.farms.domain.enterprise.EnterpriseProviderOutcome
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseCapitalLedger
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseCapitalPolicy
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseLedger
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseMoneyOperationKind
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseMoneyOperationState
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseOwnershipView
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseSnapshot
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmEconomyGateway
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.NoOpFarmEconomyGateway
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.farm.enterprise.FarmEnterpriseAdapter
import ru.ruscrafting.farms.paper.farm.enterprise.FarmEnterprisePort
import ru.ruscrafting.farms.paper.farm.enterprise.weekStartEpochDay
import org.bukkit.OfflinePlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal enum class EnterpriseInvestmentActionResult {
    STARTED,
    NOT_AVAILABLE,
    FUNDING_CLOSED,
    OWNER_LIMIT,
    SOLD_OUT,
    ALREADY_PENDING,
    NO_CREDIT,
    SUCCESS,
    PROVIDER_REJECTED,
    MANUAL_REVIEW,
    STATE_ERROR,
}

internal interface EnterpriseMoneyTaskToken

internal interface EnterpriseMoneyTasks {
    fun token(): EnterpriseMoneyTaskToken
    fun run(token: EnterpriseMoneyTaskToken, action: () -> Unit)
}

internal class SupervisedEnterpriseMoneyTasks(
    private val supervisor: RuntimeTaskSupervisor,
) : EnterpriseMoneyTasks {
    private data class Token(val value: RuntimeTaskSupervisor.Token) : EnterpriseMoneyTaskToken

    override fun token(): EnterpriseMoneyTaskToken = Token(supervisor.token())

    override fun run(token: EnterpriseMoneyTaskToken, action: () -> Unit) {
        require(token is Token) { "Enterprise money task token belongs to another scheduler" }
        supervisor.runLater(token.value, 1L, action)
    }
}

private object ImmediateEnterpriseMoneyTasks : EnterpriseMoneyTasks {
    private object Token : EnterpriseMoneyTaskToken
    override fun token(): EnterpriseMoneyTaskToken = Token
    override fun run(token: EnterpriseMoneyTaskToken, action: () -> Unit) = action()
}

/**
 * Owns the one activity-neutral enterprise ledger and the thin adapters that
 * translate each worksite lifecycle into it. Future mine and lumber adapters
 * join here so reservations, reporting and exact-once watermarks stay shared.
 */
internal class WorksiteEnterpriseService(
    settings: () -> ArcFarmsConfig,
    debug: ArcFarmsDebug,
    clock: () -> Long,
    private val economy: FarmEconomyGateway = NoOpFarmEconomyGateway,
    private val persist: () -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) },
    private val tasks: EnterpriseMoneyTasks = ImmediateEnterpriseMoneyTasks,
) {
    private val ledger = WorksiteEnterpriseLedger()
    private val capital = WorksiteEnterpriseCapitalLedger()
    private val participation = WorksiteEnterpriseParticipationLedger()
    private var ballotWritePending = false
    private var ballotWriteFailed = false
    private val settings = settings
    private val debug = debug
    private val clock = clock
    private val farmAdapter = FarmEnterpriseAdapter(
        settings = { requireNotNull(settings().enterprises[ActivityKind.FARM]) },
        debug = debug,
        clock = clock,
        ledger = ledger,
        commerciallyActive = { configured ->
            capital.isCommerciallyActive(
                configured.activity,
                configured.companyId,
                configured.weekStartEpochDay(clock()),
            )
        },
        shadowEligible = { configured -> !capital.hasCompany(configured.activity, configured.companyId) },
        companyExists = { configured -> capital.hasCompany(configured.activity, configured.companyId) },
        availableGrossLimit = { configured, reservationWeek ->
            capital.unlockedGrossCents(configured.activity, configured.companyId,
                reservationWeek, configured.licenseGrossEnvelopeCents)
        },
    )

    val farm: FarmEnterprisePort = object : FarmEnterprisePort {
        override fun orderPremium(worksiteId: String, sequence: Long): WorksiteEnterpriseOrderPremium? {
            val reservation = ledger.snapshot().reservations["farm:$worksiteId"]?.takeIf { it.sequence == sequence } ?: return null
            val terms = reservation.terms ?: return null
            val afterCost = reservation.grossTariffCents - reservation.grossTariffCents * terms.operatingCostPercent / 100
            return WorksiteEnterpriseOrderPremium(afterCost * terms.workerBonusPercent / 100,
                !capital.hasCompany(reservation.activity, reservation.companyId))
        }
        override fun orderStarted(worksiteId: String, orderId: String, sequence: Long, startedAt: Long) =
            farmAdapter.orderStarted(worksiteId, orderId, sequence, startedAt)
        override fun orderCancelled(worksiteId: String, sequence: Long) = farmAdapter.orderCancelled(worksiteId, sequence)
        override fun orderCompleted(worksiteId: String, sequence: Long, contributors: Map<UUID, Int>, commercialEligible: Boolean): Boolean {
            val revenueChanged = farmAdapter.orderCompleted(worksiteId, sequence, contributors, commercialEligible)
            val configured = settings().enterprises.getValue(ActivityKind.FARM)
            val recorded = engagementEnabled() && configured.worksiteId == worksiteId && commercialEligible &&
                contributors.any { it.value > 0 } && participation.recordCompleted(configured.activity, configured.companyId,
                    worksiteId, sequence, configured.weekStartEpochDay(clock()), contributors)
            return revenueChanged || recorded
        }
        override fun orderPool(worksiteId: String, orders: List<FarmOrder>): List<FarmOrder> {
            val configured = settings().enterprises.getValue(ActivityKind.FARM)
            if (!engagementEnabled() || configured.worksiteId != worksiteId) return orders
            if (capital.hasCompany(configured.activity, configured.companyId) &&
                !capital.isCommerciallyActive(configured.activity, configured.companyId, configured.weekStartEpochDay(clock()))) return orders
            advanceParticipation()
            return FarmEnterpriseOrderPlan.orders(participation.view(configured.activity, configured.companyId).targetPlan, orders)
        }
        override fun playerView(playerId: UUID) = this@WorksiteEnterpriseService.playerView(playerId)
        override fun projectView(worksiteId: String): WorksiteEnterpriseProjectProgress? {
            val configured = settings().enterprises.getValue(ActivityKind.FARM)
            return if (engagementEnabled() && configured.worksiteId == worksiteId)
                participation.view(configured.activity, configured.companyId).project else null
        }
    }

    fun replace(snapshot: WorksiteEnterpriseSnapshot?): Boolean {
        val restored = snapshot ?: WorksiteEnterpriseSnapshot()
        ledger.replace(restored)
        capital.replace(restored.financing)
        participation.replace(restored.participation)
        ballotWritePending = false
        ballotWriteFailed = false
        return capital.recoverPreparedOperations()
    }

    fun snapshot(): WorksiteEnterpriseSnapshot = ledger.snapshot().copy(financing = capital.snapshot(), participation = participation.snapshot())

    fun reconcileFarms(runtimes: Collection<FarmRuntime>, candidate: ArcFarmsConfig? = null): Boolean {
        val configured = candidate?.enterprises?.getValue(ActivityKind.FARM)
        val active = configured ?: settings().enterprises.getValue(ActivityKind.FARM)
        val clearedShadowAccounting = active.mode == ru.ruscrafting.farms.config.WorksiteEnterpriseMode.LIVE &&
            !capital.hasCompany(active.activity, active.companyId) &&
            ledger.clearCompanyAccounting(active.activity, active.companyId)
        val reconciled = if (configured == null) {
            farmAdapter.reconcileRuntimes(runtimes)
        } else {
            farmAdapter.reconcileRuntimes(runtimes, configured)
        }
        val pruned = farmAdapter.pruneReports(active)
        val capitalChanged = capital.advance(
            active.capitalPolicy(),
            clock(),
            active.weekStartEpochDay(clock()),
            ledger.snapshot(),
            allowCreate = active.mode == ru.ruscrafting.farms.config.WorksiteEnterpriseMode.LIVE,
        )
        val participationChanged = advanceParticipation(candidate)
        return clearedShadowAccounting || reconciled || pruned || capitalChanged || participationChanged
    }

    fun companyView(activity: ActivityKind): WorksiteEnterpriseCompanyView? = when (activity) {
        ActivityKind.FARM -> farmAdapter.companyView()
        ActivityKind.LUMBER, ActivityKind.MINE -> null
    }

    fun ownershipView(activity: ActivityKind, playerId: UUID): WorksiteEnterpriseOwnershipView? {
        val configured = settings().enterprises[activity] ?: return null
        if (configured.mode != ru.ruscrafting.farms.config.WorksiteEnterpriseMode.LIVE &&
            !capital.hasCompany(activity, configured.companyId)
        ) return null
        return capital.view(activity, configured.companyId, playerId)
    }

    fun buyShares(
        player: OfflinePlayer,
        shares: Int,
        complete: (EnterpriseInvestmentActionResult) -> Unit,
    ): EnterpriseInvestmentActionResult {
        val configured = settings().enterprises.getValue(ActivityKind.FARM)
        if (configured.mode != ru.ruscrafting.farms.config.WorksiteEnterpriseMode.LIVE || !economy.available) {
            return EnterpriseInvestmentActionResult.NOT_AVAILABLE
        }
        val operationId = "enterprise:buy:${configured.companyId}:${UUID.randomUUID()}"
        val prepared = runCatching {
            capital.prepareSharePurchase(
                configured.activity,
                configured.companyId,
                player.uniqueId,
                shares,
                operationId,
                clock(),
            )
        }.getOrElse { failure ->
            debugMoneyFailure("enterprise_purchase_prepare_failed", operationId, failure)
            return EnterpriseInvestmentActionResult.STATE_ERROR
        }
        val immediate = preparationResult(prepared.outcome)
        if (immediate != EnterpriseInvestmentActionResult.STARTED) return immediate
        persistPrepared(operationId, player, WorksiteEnterpriseMoneyOperationKind.SHARE_PURCHASE, tasks.token(), complete)
        return immediate
    }

    fun withdrawAccount(
        player: OfflinePlayer,
        complete: (EnterpriseInvestmentActionResult) -> Unit,
    ): EnterpriseInvestmentActionResult {
        val configured = settings().enterprises.getValue(ActivityKind.FARM)
        if (!economy.available || !capital.hasCompany(configured.activity, configured.companyId)) {
            return EnterpriseInvestmentActionResult.NOT_AVAILABLE
        }
        val operationId = "enterprise:withdraw:${configured.companyId}:${UUID.randomUUID()}"
        val prepared = runCatching {
            capital.prepareAccountWithdrawal(
                configured.activity,
                configured.companyId,
                player.uniqueId,
                operationId,
                clock(),
            )
        }.getOrElse { failure ->
            debugMoneyFailure("enterprise_withdraw_prepare_failed", operationId, failure)
            return EnterpriseInvestmentActionResult.STATE_ERROR
        }
        val immediate = preparationResult(prepared.outcome)
        if (immediate != EnterpriseInvestmentActionResult.STARTED) return immediate
        persistPrepared(operationId, player, WorksiteEnterpriseMoneyOperationKind.ACCOUNT_WITHDRAWAL, tasks.token(), complete)
        return immediate
    }

    fun tick(): Boolean {
        val participationChanged = advanceParticipation()
        val configured = settings().enterprises.getValue(ActivityKind.FARM)
        val capitalChanged = capital.advance(
            configured.capitalPolicy(),
            clock(),
            configured.weekStartEpochDay(clock()),
            ledger.snapshot(),
            allowCreate = configured.mode == ru.ruscrafting.farms.config.WorksiteEnterpriseMode.LIVE,
        )
        return capitalChanged || participationChanged
    }

    private fun engagementEnabled(): Boolean {
        val configured = settings().enterprises.getValue(ActivityKind.FARM)
        return configured.mode != WorksiteEnterpriseMode.OFF || capital.hasCompany(configured.activity, configured.companyId)
    }

    private fun holdings(configured: ru.ruscrafting.farms.config.WorksiteEnterpriseSettings = settings().enterprises.getValue(ActivityKind.FARM)): Map<UUID, Int> {
        val company = capital.snapshot().companies["farm:${configured.companyId}"] ?: return emptyMap()
        return if (capital.isCommerciallyActive(configured.activity, configured.companyId, configured.weekStartEpochDay(clock())))
            company.shareholdings else emptyMap()
    }

    private fun advanceParticipation(candidate: ArcFarmsConfig? = null): Boolean {
        val configured = (candidate ?: settings()).enterprises.getValue(ActivityKind.FARM)
        if (configured.mode == WorksiteEnterpriseMode.OFF && !capital.hasCompany(configured.activity, configured.companyId) ||
            ballotWritePending || ballotWriteFailed) return false
        return participation.advance(configured.activity, configured.companyId,
            configured.weekStartEpochDay(clock()), holdings(configured)).changed
    }

    fun participationView(playerId: UUID): WorksiteEnterpriseParticipationView? {
        if (!engagementEnabled()) return null
        val configured = settings().enterprises.getValue(ActivityKind.FARM)
        return participation.view(configured.activity, configured.companyId, playerId)
    }

    fun playerView(playerId: UUID): WorksiteEnterprisePlayerView? {
        if (!engagementEnabled()) return null
        val configured = settings().enterprises.getValue(ActivityKind.FARM)
        val week = configured.weekStartEpochDay(clock())
        val key = "farm:${configured.companyId}"
        val company = capital.snapshot().companies[key]
        val ownership = capital.view(configured.activity, configured.companyId, playerId)
        val overview = farmAdapter.companyView() ?: return null
        val view = participation.view(configured.activity, configured.companyId, playerId)
        val unlocked = capital.unlockedGrossCents(configured.activity, configured.companyId, week,
            configured.licenseGrossEnvelopeCents) ?: configured.licenseGrossEnvelopeCents
        val next = LocalDate.ofEpochDay(week).plusWeeks(1).atTime(configured.businessWeek.startTime)
            .atZone(configured.businessWeek.zoneId).toInstant().toEpochMilli()
        val pendingWorker = ledger.snapshot().weeks.values.filter {
            it.activity == configured.activity && it.companyId == configured.companyId &&
                (company?.lastClosedWeekStartEpochDay == null || it.weekStartEpochDay > company.lastClosedWeekStartEpochDay)
        }.sumOf { it.projectedWorkerCreditsCents[playerId] ?: 0L }
        return WorksiteEnterprisePlayerView(
            simulated = company == null,
            workerAccruedCents = pendingWorker,
            projectedDividendCents = overview.projectedDividendPoolCents / configured.capital.totalShares * (ownership?.ownedShares ?: 0),
            nextSettlementMillis = next, currentWeekStartEpochDay = week,
            completedOrders = view.project.personalCompletedOrders.toLong(), contribution = view.project.personalContributions.toLong(),
            availableThisWeekCents = (unlocked - overview.settledGrossCents - overview.reservedGrossCents).coerceAtLeast(0),
            licenseWeeksRemaining = company?.licenseEndsWeekStartEpochDay?.let { ((it - week) / 7).coerceIn(0, 52).toInt() } ?: 0,
            projectStage = view.project.completedMilestones, projectOrders = view.project.contributions.toLong(),
            projectTarget = (view.project.nextMilestone ?: 50).toLong(), planId = view.targetPlan.name.lowercase(),
            canVote = !ballotWritePending && !ballotWriteFailed && participation.selectedBallot(configured.activity, configured.companyId, playerId) == null && (holdings()[playerId] ?: 0) > 0,
            canAdvise = !ballotWritePending && !ballotWriteFailed && participation.selectedBallot(configured.activity, configured.companyId, playerId) == null && participation.canAdvise(configured.activity, configured.companyId, playerId),
        )
    }

    fun vote(playerId: UUID, plan: WorksiteEnterprisePlan, week: Long, complete: (Boolean) -> Unit) {
        if (!engagementEnabled() || ballotWritePending || ballotWriteFailed) { complete(false); return }
        advanceParticipation()
        val configured = settings().enterprises.getValue(ActivityKind.FARM)
        if (week != configured.weekStartEpochDay(clock()) + 7) { complete(false); return }
        val result = participation.vote(configured.activity, configured.companyId, playerId, plan, week, holdings())
        if (!result.changed) { complete(false); return }
        ballotWritePending = true
        val token = tasks.token()
        runCatching(persist).getOrElse { CompletableFuture.failedFuture(it) }.whenComplete { _, failure ->
            tasks.run(token) {
                ballotWritePending = false
                // An ambiguous disk write must not apply an unconfirmed policy. Reload reconciles the saved ballot.
                ballotWriteFailed = failure != null
                if (failure != null) debugMoneyFailure("enterprise_ballot_write_failed", "$playerId:$week", failure)
                complete(failure == null)
            }
        }
    }

    private fun persistPrepared(
        operationId: String,
        player: OfflinePlayer,
        kind: WorksiteEnterpriseMoneyOperationKind,
        token: EnterpriseMoneyTaskToken,
        complete: (EnterpriseInvestmentActionResult) -> Unit,
    ) {
        val initialWrite = runCatching(persist).getOrElse { failure ->
            rejectUnsentOperation(operationId, failure)
            complete(EnterpriseInvestmentActionResult.STATE_ERROR)
            return
        }
        initialWrite.whenComplete { _, failure ->
            tasks.run(token) task@{
                if (failure != null) {
                    rejectUnsentOperation(operationId, failure)
                    complete(EnterpriseInvestmentActionResult.STATE_ERROR)
                    return@task
                }
                val operation = capital.snapshot().operations[operationId] ?: run {
                    complete(EnterpriseInvestmentActionResult.STATE_ERROR)
                    return@task
                }
                if (operation.state != WorksiteEnterpriseMoneyOperationState.PREPARED) {
                    complete(
                        if (operation.state == WorksiteEnterpriseMoneyOperationState.MANUAL_REVIEW) {
                            EnterpriseInvestmentActionResult.MANUAL_REVIEW
                        } else EnterpriseInvestmentActionResult.STATE_ERROR,
                    )
                    return@task
                }
                val providerOutcome = runCatching {
                    val amount = operation.amountCents / 100.0
                    val accepted = when (kind) {
                        WorksiteEnterpriseMoneyOperationKind.SHARE_PURCHASE -> economy.withdraw(player, amount)
                        WorksiteEnterpriseMoneyOperationKind.ACCOUNT_WITHDRAWAL -> economy.deposit(player, amount)
                    }
                    if (accepted) EnterpriseProviderOutcome.SUCCESS else EnterpriseProviderOutcome.REJECTED
                }.getOrElse { failure ->
                    debug.event(
                        "enterprise_money_provider_unknown",
                        "operation" to operationId,
                        "kind" to kind,
                        "failure" to failure.javaClass.simpleName,
                    )
                    EnterpriseProviderOutcome.UNKNOWN
                }
                val configured = settings().enterprises.getValue(operation.activity)
                val recordedOutcome = runCatching {
                    capital.completeMoneyOperation(
                        operationId,
                        providerOutcome,
                        configured.weekStartEpochDay(clock()),
                    )
                    providerOutcome
                }.getOrElse { completionFailure ->
                    debugMoneyFailure("enterprise_money_completion_failed", operationId, completionFailure)
                    runCatching {
                        capital.completeMoneyOperation(
                            operationId,
                            EnterpriseProviderOutcome.UNKNOWN,
                            configured.weekStartEpochDay(clock()),
                        )
                    }
                    EnterpriseProviderOutcome.UNKNOWN
                }
                val finalWrite = runCatching(persist).getOrElse { finalFailure ->
                    debugMoneyFailure("enterprise_money_final_write_failed", operationId, finalFailure)
                    complete(EnterpriseInvestmentActionResult.MANUAL_REVIEW)
                    return@task
                }
                finalWrite.whenComplete { _, finalFailure ->
                    tasks.run(token) {
                        val result = when {
                            recordedOutcome == EnterpriseProviderOutcome.UNKNOWN -> EnterpriseInvestmentActionResult.MANUAL_REVIEW
                            recordedOutcome == EnterpriseProviderOutcome.REJECTED -> EnterpriseInvestmentActionResult.PROVIDER_REJECTED
                            finalFailure != null -> EnterpriseInvestmentActionResult.MANUAL_REVIEW
                            else -> EnterpriseInvestmentActionResult.SUCCESS
                        }
                        complete(result)
                    }
                }
            }
        }
    }

    private fun rejectUnsentOperation(operationId: String, failure: Throwable) {
        debugMoneyFailure("enterprise_money_initial_write_failed", operationId, failure)
        runCatching { capital.completeMoneyOperation(operationId, EnterpriseProviderOutcome.REJECTED, 0L) }
        runCatching(persist)
    }

    private fun debugMoneyFailure(event: String, operationId: String, failure: Throwable) {
        debug.event(
            event,
            "operation" to operationId,
            "failure" to failure.javaClass.simpleName,
        )
    }

    private fun preparationResult(outcome: EnterpriseMoneyPreparationOutcome): EnterpriseInvestmentActionResult = when (outcome) {
        EnterpriseMoneyPreparationOutcome.PREPARED -> EnterpriseInvestmentActionResult.STARTED
        EnterpriseMoneyPreparationOutcome.DUPLICATE -> EnterpriseInvestmentActionResult.ALREADY_PENDING
        EnterpriseMoneyPreparationOutcome.NOT_AVAILABLE -> EnterpriseInvestmentActionResult.NOT_AVAILABLE
        EnterpriseMoneyPreparationOutcome.FUNDING_CLOSED -> EnterpriseInvestmentActionResult.FUNDING_CLOSED
        EnterpriseMoneyPreparationOutcome.OWNER_LIMIT -> EnterpriseInvestmentActionResult.OWNER_LIMIT
        EnterpriseMoneyPreparationOutcome.SOLD_OUT -> EnterpriseInvestmentActionResult.SOLD_OUT
        EnterpriseMoneyPreparationOutcome.INSUFFICIENT_CREDIT -> EnterpriseInvestmentActionResult.NO_CREDIT
    }
}

private fun ru.ruscrafting.farms.config.WorksiteEnterpriseSettings.capitalPolicy(): WorksiteEnterpriseCapitalPolicy =
    WorksiteEnterpriseCapitalPolicy(
        activity = activity,
        companyId = companyId,
        totalShares = capital.totalShares,
        sharePriceCents = capital.sharePriceCents,
        maxSharesPerOwner = capital.maxSharesPerOwner,
        fundingDurationMillis = TimeUnit.DAYS.toMillis(capital.fundingDurationDays.toLong()),
        licenseBurnPercent = capital.licenseBurnPercent,
        licenseWeeks = capital.licenseWeeks,
        reserveTargetWeeks = capital.reserveTargetWeeks,
        retainedReportWeeks = retainedReportWeeks,
        defaultDividendPercent = dividendPercent,
        defaultWeeklyUpkeepCents = weeklyUpkeepCents,
    )

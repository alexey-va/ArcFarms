package ru.ruscrafting.farms.paper.enterprise

import ru.ruscrafting.farms.config.ArcFarmsConfig
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
    )

    val farm: FarmEnterprisePort = farmAdapter

    fun replace(snapshot: WorksiteEnterpriseSnapshot?): Boolean {
        val restored = snapshot ?: WorksiteEnterpriseSnapshot()
        ledger.replace(restored)
        capital.replace(restored.financing)
        return capital.recoverPreparedOperations()
    }

    fun snapshot(): WorksiteEnterpriseSnapshot = ledger.snapshot().copy(financing = capital.snapshot())

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
        return clearedShadowAccounting || reconciled || pruned || capitalChanged
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
        val configured = settings().enterprises.getValue(ActivityKind.FARM)
        return capital.advance(
            configured.capitalPolicy(),
            clock(),
            configured.weekStartEpochDay(clock()),
            ledger.snapshot(),
            allowCreate = configured.mode == ru.ruscrafting.farms.config.WorksiteEnterpriseMode.LIVE,
        )
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

package ru.ruscrafting.farms.domain.enterprise

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.ActivityKind
import java.util.UUID

class WorksiteEnterpriseCapitalLedgerTest : FunSpec({
    test("full funding activates the company and splits capital into license burn and treasury") {
        val ledger = WorksiteEnterpriseCapitalLedger()
        val policy = policy(totalShares = 10, sharePriceCents = 1_000_000, maxSharesPerOwner = 5)
        ledger.ensureCompany(policy, now = 1_000)

        listOf(OWNER_A, OWNER_B).forEachIndexed { index, owner ->
            val operationId = operationId("buy", index)
            ledger.prepareSharePurchase(ActivityKind.FARM, COMPANY, owner, 5, operationId, 2_000L + index)
                .outcome shouldBe EnterpriseMoneyPreparationOutcome.PREPARED
            ledger.completeMoneyOperation(operationId, EnterpriseProviderOutcome.SUCCESS, businessWeekStartEpochDay = 100)
        }

        val company = ledger.snapshot().companies.getValue("farm:$COMPANY")
        company.phase shouldBe WorksiteEnterpriseCapitalPhase.ACTIVE
        company.issuedShares shouldBe 10
        company.escrowCents shouldBe 0
        company.treasuryCents shouldBe 5_000_000
        company.activatedWeekStartEpochDay shouldBe 100
        company.licenseEndsWeekStartEpochDay shouldBe 184
    }

    test("prepared purchases reserve both the company supply and the personal ownership cap") {
        val ledger = WorksiteEnterpriseCapitalLedger()
        val policy = policy(totalShares = 10, maxSharesPerOwner = 5)
        ledger.ensureCompany(policy, 1_000)

        ledger.prepareSharePurchase(ActivityKind.FARM, COMPANY, OWNER_A, 5, operationId("buy", 1), 2_000)
            .outcome shouldBe EnterpriseMoneyPreparationOutcome.PREPARED
        ledger.prepareSharePurchase(ActivityKind.FARM, COMPANY, OWNER_A, 1, operationId("buy", 2), 2_001)
            .outcome shouldBe EnterpriseMoneyPreparationOutcome.DUPLICATE
        ledger.prepareSharePurchase(ActivityKind.FARM, COMPANY, OWNER_B, 6, operationId("buy", 3), 2_002)
            .outcome shouldBe EnterpriseMoneyPreparationOutcome.OWNER_LIMIT

        val view = requireNotNull(ledger.view(ActivityKind.FARM, COMPANY, OWNER_A))
        view.reservedShares shouldBe 5
        view.ownedShares shouldBe 0
    }

    test("restart turns a prepared provider operation into manual review without retrying it") {
        val first = WorksiteEnterpriseCapitalLedger()
        first.ensureCompany(policy(), 1_000)
        val operationId = operationId("buy", 4)
        first.prepareSharePurchase(ActivityKind.FARM, COMPANY, OWNER_A, 2, operationId, 2_000)

        val recovered = WorksiteEnterpriseCapitalLedger()
        recovered.replace(first.snapshot())
        recovered.recoverPreparedOperations() shouldBe true
        recovered.recoverPreparedOperations() shouldBe false

        val operation = recovered.snapshot().operations.getValue(operationId)
        operation.state shouldBe WorksiteEnterpriseMoneyOperationState.MANUAL_REVIEW
        recovered.view(ActivityKind.FARM, COMPANY, OWNER_A)?.reservedShares shouldBe 2
        recovered.view(ActivityKind.FARM, COMPANY, OWNER_B)?.pendingManualReviewCount shouldBe 0
        recovered.prepareSharePurchase(ActivityKind.FARM, COMPANY, OWNER_A, 1, operationId("buy", 5), 3_000)
            .outcome shouldBe EnterpriseMoneyPreparationOutcome.DUPLICATE
    }

    test("failed funding returns every accepted contribution to durable investment credit") {
        val ledger = WorksiteEnterpriseCapitalLedger()
        val policy = policy(totalShares = 10, sharePriceCents = 25_000, maxSharesPerOwner = 5)
        ledger.ensureCompany(policy, 1_000)
        val operationId = operationId("buy", 6)
        ledger.prepareSharePurchase(ActivityKind.FARM, COMPANY, OWNER_A, 4, operationId, 2_000)
        ledger.completeMoneyOperation(operationId, EnterpriseProviderOutcome.SUCCESS, 100)

        ledger.advance(policy, now = 1_000 + policy.fundingDurationMillis, 100, WorksiteEnterpriseSnapshot()) shouldBe true

        val view = requireNotNull(ledger.view(ActivityKind.FARM, COMPANY, OWNER_A))
        view.phase shouldBe WorksiteEnterpriseCapitalPhase.CANCELLED
        view.ownedShares shouldBe 0
        view.accountBalanceCents shouldBe 100_000
        view.accountAvailableCents shouldBe 100_000
    }

    test("weekly settlement credits workers and record-date owners while conserving treasury") {
        val ledger = activeLedger()
        val revenue = WorksiteEnterpriseWeek(
            activity = ActivityKind.FARM,
            companyId = COMPANY,
            weekStartEpochDay = 100,
            completedOrders = 1,
            grossRevenueCents = 1_000_000,
            operatingBurnCents = 200_000,
            workerBonusCents = 240_000,
            retainedProfitCents = 560_000,
            uniqueContributors = setOf(WORKER),
            projectedWorkerCreditsCents = mapOf(WORKER to 240_000),
            terms = WorksiteEnterpriseTerms(20, 30, 50, 100_000),
        )
        val revenueSnapshot = WorksiteEnterpriseSnapshot(weeks = mapOf("farm:$COMPANY:100" to revenue))

        ledger.advance(policy(totalShares = 4, sharePriceCents = 1_000_000, maxSharesPerOwner = 4), 9_000, 107, revenueSnapshot) shouldBe true

        val snapshot = ledger.snapshot()
        val company = snapshot.companies.getValue("farm:$COMPANY")
        val report = snapshot.distributions.getValue("farm:$COMPANY:100")
        report.upkeepBurnCents shouldBe 100_000
        report.dividendPoolCents shouldBe 230_000
        report.dividendPerShareCents shouldBe 57_500
        report.roundingBurnCents shouldBe 0
        company.treasuryCents shouldBe 2_230_000
        snapshot.investmentCreditsCents.getValue(OWNER_A) shouldBe 230_000
        snapshot.investmentCreditsCents.getValue(WORKER) shouldBe 240_000
    }

    test("an empty business week still burns configured upkeep without minting dividends") {
        val ledger = activeLedger()

        ledger.advance(
            policy(totalShares = 4, sharePriceCents = 1_000_000, maxSharesPerOwner = 4),
            now = 9_000,
            currentWeekStartEpochDay = 107,
            revenueSnapshot = WorksiteEnterpriseSnapshot(),
        ) shouldBe true

        val snapshot = ledger.snapshot()
        val company = snapshot.companies.getValue("farm:$COMPANY")
        val report = snapshot.distributions.getValue("farm:$COMPANY:100")
        report.grossRevenueCents shouldBe 0
        report.upkeepBurnCents shouldBe 100_000
        report.dividendPoolCents shouldBe 0
        company.treasuryCents shouldBe 1_900_000
        snapshot.investmentCreditsCents[OWNER_A] shouldBe null
    }

    test("a week with a carried reservation is not closed until that reservation is terminal") {
        val ledger = activeLedger()
        val reservation = WorksiteEnterpriseReservation(
            operationId = "enterprise:farm:communal_farm:1",
            activity = ActivityKind.FARM,
            companyId = COMPANY,
            worksiteId = COMPANY,
            orderId = "market_crates",
            sequence = 1,
            grossTariffCents = 1_000_000,
            reservedAt = 1_000,
            businessWeekStartEpochDay = 100,
            terms = WorksiteEnterpriseTerms(20, 30, 50, 100_000),
        )
        val pinned = WorksiteEnterpriseSnapshot(reservations = mapOf("farm:$COMPANY" to reservation))

        ledger.advance(policy(totalShares = 4, sharePriceCents = 1_000_000, maxSharesPerOwner = 4), 9_000, 107, pinned) shouldBe false
        ledger.snapshot().distributions shouldBe emptyMap()
        ledger.advance(
            policy(totalShares = 4, sharePriceCents = 1_000_000, maxSharesPerOwner = 4),
            10_000,
            107,
            WorksiteEnterpriseSnapshot(),
        ) shouldBe true
        ledger.snapshot().distributions.keys shouldBe setOf("farm:$COMPANY:100")
    }

    test("investment withdrawal reserves the claim and applies it exactly once") {
        val ledger = WorksiteEnterpriseCapitalLedger()
        val policy = policy(totalShares = 2, sharePriceCents = 50_000, maxSharesPerOwner = 1)
        ledger.ensureCompany(policy, 1_000)
        val buyId = operationId("buy", 7)
        ledger.prepareSharePurchase(ActivityKind.FARM, COMPANY, OWNER_A, 1, buyId, 2_000)
        ledger.completeMoneyOperation(buyId, EnterpriseProviderOutcome.SUCCESS, 100)
        ledger.advance(policy, 1_000 + policy.fundingDurationMillis, 100, WorksiteEnterpriseSnapshot())

        val withdrawId = operationId("withdraw", 8)
        ledger.prepareAccountWithdrawal(ActivityKind.FARM, COMPANY, OWNER_A, withdrawId, 3_000).apply {
            outcome shouldBe EnterpriseMoneyPreparationOutcome.PREPARED
            amountCents shouldBe 50_000
        }
        ledger.view(ActivityKind.FARM, COMPANY, OWNER_A)?.accountAvailableCents shouldBe 0
        ledger.completeMoneyOperation(withdrawId, EnterpriseProviderOutcome.SUCCESS, 100)
        ledger.completeMoneyOperation(withdrawId, EnterpriseProviderOutcome.SUCCESS, 100)

        ledger.view(ActivityKind.FARM, COMPANY, OWNER_A)?.accountBalanceCents shouldBe 0
        ledger.snapshot().operations.getValue(withdrawId).state shouldBe WorksiteEnterpriseMoneyOperationState.APPLIED
    }

    test("invalid persisted money conservation is rejected") {
        val badCompany = WorksiteEnterpriseCapitalCompany(
            activity = ActivityKind.FARM,
            companyId = COMPANY,
            phase = WorksiteEnterpriseCapitalPhase.FUNDING,
            fundingOpenedAt = 1_000,
            fundingClosesAt = 10_000,
            totalShares = 10,
            sharePriceCents = 50_000,
            maxSharesPerOwner = 5,
            licenseBurnPercent = 50,
            licenseWeeks = 12,
            reserveTargetWeeks = 1,
            issuedShares = 1,
            shareholdings = mapOf(OWNER_A to 1),
            escrowCents = 1,
        )

        shouldThrow<IllegalArgumentException> {
            WorksiteEnterpriseCapitalLedger().replace(
                WorksiteEnterpriseFinancingSnapshot(companies = mapOf("farm:$COMPANY" to badCompany)),
            )
        }
    }

    test("capital terms cannot be hot-repriced after funding opens") {
        val ledger = WorksiteEnterpriseCapitalLedger()
        ledger.ensureCompany(policy(sharePriceCents = 50_000), 1_000)

        shouldThrow<IllegalArgumentException> {
            ledger.advance(
                policy(sharePriceCents = 60_000),
                now = 2_000,
                currentWeekStartEpochDay = 100,
                revenueSnapshot = WorksiteEnterpriseSnapshot(),
            )
        }.message shouldBe "Enterprise capital terms are immutable after funding opens"
    }

    test("license pacing unlocks cumulative envelope and carries unused capacity") {
        val ledger = activeLedger()
        ledger.unlockedGrossCents(ActivityKind.FARM, COMPANY, 99, 1_000_000) shouldBe 0
        ledger.unlockedGrossCents(ActivityKind.FARM, COMPANY, 100, 1_000_000) shouldBe 83_333
        ledger.unlockedGrossCents(ActivityKind.FARM, COMPANY, 107, 1_000_000) shouldBe 166_666
        ledger.unlockedGrossCents(ActivityKind.FARM, COMPANY, 184, 1_000_000) shouldBe 1_000_000
        ledger.unlockedGrossCents(ActivityKind.FARM, "missing", 107, 1_000_000) shouldBe null
    }

    test("expired company liquidates treasury once, including offline owners") {
        val ledger = activeLedger()
        val policy = policy(totalShares = 4, sharePriceCents = 1_000_000, maxSharesPerOwner = 4)
        ledger.advance(policy, now = 9_000, currentWeekStartEpochDay = 184, revenueSnapshot = WorksiteEnterpriseSnapshot()) shouldBe true
        val first = ledger.snapshot()
        first.companies.getValue("farm:$COMPANY").phase shouldBe WorksiteEnterpriseCapitalPhase.EXPIRED
        first.companies.getValue("farm:$COMPANY").liquidationCompleted shouldBe true
        first.companies.getValue("farm:$COMPANY").treasuryCents shouldBe 0
        first.investmentCreditsCents.getValue(OWNER_A) shouldBe 800_000

        ledger.advance(policy, now = 10_000, currentWeekStartEpochDay = 184, revenueSnapshot = WorksiteEnterpriseSnapshot()) shouldBe false
        ledger.snapshot().investmentCreditsCents.getValue(OWNER_A) shouldBe 800_000
    }

    test("pending reservation delays expiry liquidation") {
        val ledger = activeLedger()
        val reservation = WorksiteEnterpriseReservation(
            operationId = "enterprise:farm:communal_farm:${UUID(0, 18)}",
            activity = ActivityKind.FARM, companyId = COMPANY, worksiteId = COMPANY,
            orderId = "bakery_supply", sequence = 18, grossTariffCents = 1_000_000,
            reservedAt = 2_000, businessWeekStartEpochDay = 100,
        )
        val pending = WorksiteEnterpriseSnapshot(reservations = mapOf("farm:$COMPANY" to reservation))
        ledger.advance(policy(totalShares = 4, sharePriceCents = 1_000_000, maxSharesPerOwner = 4), 9_000, 184, pending) shouldBe false
        ledger.snapshot().companies.getValue("farm:$COMPANY").phase shouldBe WorksiteEnterpriseCapitalPhase.ACTIVE
        ledger.advance(policy(totalShares = 4, sharePriceCents = 1_000_000, maxSharesPerOwner = 4), 9_001, 184, WorksiteEnterpriseSnapshot()) shouldBe true
        ledger.snapshot().companies.getValue("farm:$COMPANY").liquidationCompleted shouldBe true
    }

    test("legacy expired company without watermark liquidates exactly once") {
        val company = WorksiteEnterpriseCapitalCompany(
            activity = ActivityKind.FARM, companyId = COMPANY,
            phase = WorksiteEnterpriseCapitalPhase.EXPIRED, fundingOpenedAt = 1,
            fundingClosesAt = 2, totalShares = 2, sharePriceCents = 50_000,
            maxSharesPerOwner = 2, licenseBurnPercent = 50, licenseWeeks = 12,
            reserveTargetWeeks = 1, issuedShares = 2, shareholdings = mapOf(OWNER_A to 2),
            treasuryCents = 100_001, activatedWeekStartEpochDay = 0,
            licenseEndsWeekStartEpochDay = 84, lastClosedWeekStartEpochDay = 77,
        )
        val ledger = WorksiteEnterpriseCapitalLedger()
        val gson = Gson()
        val legacyJson = gson.toJsonTree(company).asJsonObject.also { it.remove("liquidationCompleted") }
        val restoredCompany = gson.fromJson(legacyJson, WorksiteEnterpriseCapitalCompany::class.java)
        restoredCompany.liquidationCompleted shouldBe false
        ledger.replace(WorksiteEnterpriseFinancingSnapshot(companies = mapOf("farm:$COMPANY" to restoredCompany)))
        val p = policy(totalShares = 2, sharePriceCents = 50_000, maxSharesPerOwner = 2)
        ledger.advance(p, 9_000, 84, WorksiteEnterpriseSnapshot()) shouldBe true
        ledger.snapshot().investmentCreditsCents.getValue(OWNER_A) shouldBe 100_001
        val reloaded = WorksiteEnterpriseCapitalLedger().also {
            it.replace(gson.fromJson(gson.toJson(ledger.snapshot()), WorksiteEnterpriseFinancingSnapshot::class.java))
        }
        reloaded.advance(p, 9_001, 84, WorksiteEnterpriseSnapshot()) shouldBe false
        reloaded.snapshot().investmentCreditsCents.getValue(OWNER_A) shouldBe 100_001
        ledger.snapshot().investmentCreditsCents.getValue(OWNER_A) shouldBe 100_001
    }
})

private fun activeLedger(): WorksiteEnterpriseCapitalLedger = WorksiteEnterpriseCapitalLedger().also { ledger ->
    val policy = policy(totalShares = 4, sharePriceCents = 1_000_000, maxSharesPerOwner = 4)
    ledger.ensureCompany(policy, 1_000)
    val operationId = operationId("buy", 99)
    ledger.prepareSharePurchase(ActivityKind.FARM, COMPANY, OWNER_A, 4, operationId, 2_000)
    ledger.completeMoneyOperation(operationId, EnterpriseProviderOutcome.SUCCESS, 100)
}

private fun policy(
    totalShares: Int = 10,
    sharePriceCents: Long = 50_000,
    maxSharesPerOwner: Int = 5,
): WorksiteEnterpriseCapitalPolicy = WorksiteEnterpriseCapitalPolicy(
    activity = ActivityKind.FARM,
    companyId = COMPANY,
    totalShares = totalShares,
    sharePriceCents = sharePriceCents,
    maxSharesPerOwner = maxSharesPerOwner,
    fundingDurationMillis = 86_400_000,
    licenseBurnPercent = 50,
    licenseWeeks = 12,
    reserveTargetWeeks = 1,
    retainedReportWeeks = 16,
    defaultDividendPercent = 50,
    defaultWeeklyUpkeepCents = 100_000,
)

private fun operationId(kind: String, seed: Int): String =
    "enterprise:$kind:$COMPANY:${UUID(0, seed.toLong())}"

private const val COMPANY = "communal_farm"
private val OWNER_A = UUID.fromString("00000000-0000-0000-0000-000000000001")
private val OWNER_B = UUID.fromString("00000000-0000-0000-0000-000000000002")
private val WORKER = UUID.fromString("00000000-0000-0000-0000-000000000003")

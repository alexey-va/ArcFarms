package ru.ruscrafting.farms.domain.enterprise

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.ActivityKind
import java.util.UUID

class WorksiteEnterpriseLedgerTest : FunSpec({
    val alice = UUID(0, 1)
    val bob = UUID(0, 2)

    test("shared kernel reserves and settles one farm order with exact money conservation") {
        val ledger = WorksiteEnterpriseLedger()
        val policy = policy(ActivityKind.FARM)

        ledger.reserve(policy, order("farm_a", 7, 1_000_000)).outcome shouldBe
            EnterpriseReservationOutcome.RESERVED
        val decision = ledger.settle(
            policy = policy,
            worksiteId = "farm_a",
            sequence = 7,
            weekStartEpochDay = 20_695,
            contributors = mapOf(alice to 3, bob to 1),
        )

        decision shouldBe EnterpriseSettlementDecision(
            outcome = EnterpriseSettlementOutcome.SETTLED,
            changed = true,
            grossRevenueCents = 1_000_000,
            operatingBurnCents = 200_000,
            workerBonusCents = 240_000,
            retainedProfitCents = 560_000,
        )
        val week = ledger.snapshot().weeks.values.single()
        week.projectedWorkerCreditsCents.shouldContainExactly(mapOf(alice to 180_000L, bob to 60_000L))
        week.projectedDividendCents(policy) shouldBe 230_000L
        week.grossRevenueCents shouldBe
            week.operatingBurnCents + week.workerBonusCents + week.retainedProfitCents
    }

    test("duplicate completion cannot create a second settlement") {
        val ledger = WorksiteEnterpriseLedger()
        val policy = policy(ActivityKind.FARM)
        ledger.reserve(policy, order("farm_a", 4, 500_000))
        ledger.settle(policy, "farm_a", 4, 20_695, mapOf(alice to 1)).outcome shouldBe
            EnterpriseSettlementOutcome.SETTLED

        ledger.settle(policy, "farm_a", 4, 20_695, mapOf(alice to 1)).outcome shouldBe
            EnterpriseSettlementOutcome.DUPLICATE
        ledger.snapshot().weeks.values.single().completedOrders shouldBe 1
        ledger.snapshot().settledGrossByCompany.getValue("farm:communal") shouldBe 500_000L
    }

    test("an accepted order keeps its financial policy across a configuration reload") {
        val ledger = WorksiteEnterpriseLedger()
        val acceptedPolicy = policy(
            ActivityKind.FARM,
            operatingCostPercent = 20,
            workerBonusPercent = 30,
            dividendPercent = 25,
            weeklyUpkeepCents = 100_000,
        )
        val reloadedPolicy = policy(
            ActivityKind.FARM,
            operatingCostPercent = 30,
            workerBonusPercent = 50,
            dividendPercent = 75,
            weeklyUpkeepCents = 0,
        )
        ledger.reserve(acceptedPolicy, order("farm_a", 5, 1_000_000))

        val decision = ledger.settle(reloadedPolicy, "farm_a", 5, 20_695, mapOf(alice to 1))

        decision.operatingBurnCents shouldBe 200_000L
        decision.workerBonusCents shouldBe 240_000L
        decision.retainedProfitCents shouldBe 560_000L
        ledger.snapshot().weeks.values.single().projectedDividendCents(reloadedPolicy) shouldBe 115_000L
        ledger.companyView(reloadedPolicy, 20_695).apply {
            operatingCostPercent shouldBe 20
            workerBonusPercent shouldBe 30
            dividendPercent shouldBe 25
            weeklyUpkeepCents shouldBe 100_000L
            projectedDividendPoolCents shouldBe 115_000L
        }
    }

    test("an order settled after rollover remains in its acceptance week") {
        val ledger = WorksiteEnterpriseLedger()
        val policy = policy(ActivityKind.FARM)
        ledger.reserve(policy, order("farm_a", 6, 1_000_000, weekStartEpochDay = 20_695))

        ledger.settle(policy, "farm_a", 6, 20_702, mapOf(alice to 1)).outcome shouldBe
            EnterpriseSettlementOutcome.SETTLED

        ledger.snapshot().weeks.values.single().weekStartEpochDay shouldBe 20_695L
    }

    test("the first accepted order fixes policy for the rest of the business week") {
        val ledger = WorksiteEnterpriseLedger()
        val original = policy(ActivityKind.FARM, envelope = 3_000_000, workerBonusPercent = 10)
        val reloaded = policy(ActivityKind.FARM, envelope = 3_000_000, workerBonusPercent = 50)
        ledger.reserve(original, order("farm_a", 1, 1_000_000)).outcome shouldBe EnterpriseReservationOutcome.RESERVED
        ledger.settle(original, "farm_a", 1, 20_695, mapOf(alice to 1))

        ledger.reserve(reloaded, order("farm_a", 2, 1_000_000)).outcome shouldBe EnterpriseReservationOutcome.RESERVED
        val sameWeek = ledger.settle(reloaded, "farm_a", 2, 20_695, mapOf(alice to 1))
        ledger.reserve(reloaded, order("farm_a", 3, 1_000_000, weekStartEpochDay = 20_702)).outcome shouldBe
            EnterpriseReservationOutcome.RESERVED
        val nextWeek = ledger.settle(reloaded, "farm_a", 3, 20_702, mapOf(alice to 1))

        sameWeek.workerBonusCents shouldBe 80_000L
        nextWeek.workerBonusCents shouldBe 400_000L
    }

    test("a company id reload settles the already accepted reservation under its original company") {
        val ledger = WorksiteEnterpriseLedger()
        val accepted = policy(ActivityKind.FARM, companyId = "communal")
        val renamed = policy(ActivityKind.FARM, companyId = "new_company")
        ledger.reserve(accepted, order("farm_a", 7, 1_000_000))

        ledger.settle(renamed, "farm_a", 7, 20_695, mapOf(alice to 1)).outcome shouldBe
            EnterpriseSettlementOutcome.SETTLED

        ledger.snapshot().settledGrossByCompany shouldBe mapOf("farm:communal" to 1_000_000L)
        ledger.snapshot().unreservedCompletionsByCompany shouldBe emptyMap()
        ledger.snapshot().weeks.values.single().companyId shouldBe "communal"
    }

    test("license envelope rejects an uncovered order without inventing revenue") {
        val ledger = WorksiteEnterpriseLedger()
        val policy = policy(ActivityKind.FARM, envelope = 1_000_000)

        ledger.reserve(policy, order("farm_a", 1, 800_000)).outcome shouldBe EnterpriseReservationOutcome.RESERVED
        val rejected = ledger.reserve(policy, order("farm_b", 1, 300_000))

        rejected.outcome shouldBe EnterpriseReservationOutcome.ENVELOPE_EXHAUSTED
        rejected.availableCents shouldBe 200_000L
        ledger.snapshot().settledGrossByCompany shouldBe emptyMap()
        ledger.snapshot().rejectedReservationsByCompany.getValue("farm:communal") shouldBe 1L
        ledger.reserve(policy, order("farm_b", 1, 300_000)).changed shouldBe false
        ledger.snapshot().rejectedReservationsByCompany.getValue("farm:communal") shouldBe 1L
    }

    test("new worksite sequence releases its stale reservation before reserving again") {
        val ledger = WorksiteEnterpriseLedger()
        val policy = policy(ActivityKind.FARM)
        ledger.reserve(policy, order("farm_a", 10, 500_000))

        ledger.reserve(policy, order("farm_a", 11, 600_000)).outcome shouldBe EnterpriseReservationOutcome.RESERVED

        ledger.snapshot().reservations.values.single().sequence shouldBe 11L
        ledger.snapshot().releasedReservationsByCompany.getValue("farm:communal") shouldBe 1L
    }

    test("cancellation releases only the exact active reservation") {
        val ledger = WorksiteEnterpriseLedger()
        val policy = policy(ActivityKind.FARM)
        ledger.reserve(policy, order("farm_a", 10, 500_000))

        ledger.releaseReservation(ActivityKind.FARM, "farm_a", 9) shouldBe false
        ledger.hasReservation(ActivityKind.FARM, "farm_a", 10) shouldBe true
        ledger.releaseReservation(ActivityKind.FARM, "farm_a", 10) shouldBe true
        ledger.releaseReservation(ActivityKind.FARM, "farm_a", 10) shouldBe false

        ledger.snapshot().reservations shouldBe emptyMap()
        ledger.snapshot().releasedReservationsByCompany.getValue("farm:communal") shouldBe 1L
    }

    test("late completion cannot remove a newer active reservation") {
        val ledger = WorksiteEnterpriseLedger()
        val policy = policy(ActivityKind.FARM)
        ledger.reserve(policy, order("farm_a", 10, 500_000))
        ledger.reserve(policy, order("farm_a", 11, 600_000))

        ledger.settle(policy, "farm_a", 10, 20_695, mapOf(alice to 1)).outcome shouldBe
            EnterpriseSettlementOutcome.STALE_SEQUENCE

        ledger.snapshot().reservations.values.single().sequence shouldBe 11L
        ledger.snapshot().weeks.values.single().completedOrders shouldBe 0
    }

    test("administrative completion releases the reservation but creates no company income") {
        val ledger = WorksiteEnterpriseLedger()
        val policy = policy(ActivityKind.FARM)
        ledger.reserve(policy, order("farm_a", 3, 1_000_000))

        ledger.excludeCompletion(policy, "farm_a", 3).outcome shouldBe EnterpriseSettlementOutcome.EXCLUDED

        ledger.snapshot().reservations shouldBe emptyMap()
        ledger.snapshot().weeks.values.single().completedOrders shouldBe 0
        ledger.snapshot().excludedCompletionsByCompany.getValue("farm:communal") shouldBe 1L
    }

    test("activity namespace lets farm and mine adapters reuse identical local ids safely") {
        val ledger = WorksiteEnterpriseLedger()
        val farm = policy(ActivityKind.FARM)
        val mine = policy(ActivityKind.MINE)

        ledger.reserve(farm, order("communal", 1, 500_000)).outcome shouldBe EnterpriseReservationOutcome.RESERVED
        ledger.reserve(mine, order("communal", 1, 500_000)).outcome shouldBe EnterpriseReservationOutcome.RESERVED

        ledger.snapshot().reservations.keys shouldBe setOf("farm:communal", "mine:communal")
    }

    test("snapshot replacement preserves exact-once watermark across restart") {
        val policy = policy(ActivityKind.FARM)
        val first = WorksiteEnterpriseLedger()
        first.reserve(policy, order("farm_a", 2, 500_000))
        first.settle(policy, "farm_a", 2, 20_695, mapOf(alice to 1))

        val recovered = WorksiteEnterpriseLedger().also { it.replace(first.snapshot()) }

        recovered.settle(policy, "farm_a", 2, 20_695, mapOf(alice to 1)).outcome shouldBe
            EnterpriseSettlementOutcome.DUPLICATE
        recovered.snapshot() shouldBe first.snapshot()
    }

    test("a smaller retention window prunes reports immediately and idempotently") {
        val ledger = WorksiteEnterpriseLedger()
        val original = policy(ActivityKind.FARM, retainedReportWeeks = 4)
        repeat(4) { index ->
            val sequence = index.toLong() + 1L
            val week = 20_695L + index * 7L
            ledger.reserve(original, order("farm_a", sequence, 100_000, week))
            ledger.settle(original, "farm_a", sequence, week, mapOf(alice to 1))
        }

        ledger.snapshot().weeks.size shouldBe 4
        ledger.pruneReports(original.copy(retainedReportWeeks = 2)) shouldBe true
        ledger.snapshot().weeks.values.map { it.weekStartEpochDay }.sorted() shouldBe listOf(20_709L, 20_716L)
        ledger.pruneReports(original.copy(retainedReportWeeks = 2)) shouldBe false
    }

    test("retention preserves an old report while an accepted order can still settle into it") {
        val ledger = WorksiteEnterpriseLedger()
        val original = policy(ActivityKind.FARM, envelope = 5_000_000, retainedReportWeeks = 4)
        val oldestWeek = 20_695L
        ledger.reserve(original, order("farm_a", 1, 100_000, oldestWeek))
        ledger.settle(original, "farm_a", 1, oldestWeek, mapOf(alice to 1))
        ledger.reserve(original, order("farm_b", 1, 100_000, oldestWeek))
        repeat(3) { index ->
            val sequence = index.toLong() + 2L
            val week = oldestWeek + (index + 1L) * 7L
            ledger.reserve(original, order("farm_a", sequence, 100_000, week))
            ledger.settle(original, "farm_a", sequence, week, mapOf(alice to 1))
        }

        ledger.pruneReports(original.copy(retainedReportWeeks = 2)) shouldBe true

        ledger.snapshot().weeks.values.map { it.weekStartEpochDay }.sorted() shouldBe
            listOf(oldestWeek, oldestWeek + 14L, oldestWeek + 21L)
        ledger.snapshot().weeks.values.single { it.weekStartEpochDay == oldestWeek }.completedOrders shouldBe 1
        ledger.settle(
            original.copy(retainedReportWeeks = 4),
            "farm_b",
            1,
            oldestWeek + 28L,
            mapOf(bob to 1),
        ).outcome shouldBe EnterpriseSettlementOutcome.SETTLED
        ledger.snapshot().weeks.values.single { it.weekStartEpochDay == oldestWeek }.completedOrders shouldBe 2
    }

    test("snapshot replacement rejects money and contributor corruption") {
        val badMoney = WorksiteEnterpriseWeek(
            activity = ActivityKind.FARM,
            companyId = "communal",
            weekStartEpochDay = 20_695,
            completedOrders = 1,
            grossRevenueCents = 100,
        )
        shouldThrow<IllegalArgumentException> {
            WorksiteEnterpriseLedger().replace(
                WorksiteEnterpriseSnapshot(weeks = mapOf("farm:communal:20695" to badMoney)),
            )
        }

        val badCredits = badMoney.copy(
            operatingBurnCents = 20,
            workerBonusCents = 30,
            retainedProfitCents = 50,
            uniqueContributors = setOf(alice),
            projectedWorkerCreditsCents = mapOf(alice to 31),
        )
        shouldThrow<IllegalArgumentException> {
            WorksiteEnterpriseLedger().replace(
                WorksiteEnterpriseSnapshot(weeks = mapOf("farm:communal:20695" to badCredits)),
            )
        }
    }

    test("paced reservation cap rolls forward and never removes an accepted reservation") {
        val ledger = WorksiteEnterpriseLedger()
        val policy = policy(ActivityKind.FARM, envelope = 1_000_000)

        ledger.reserve(policy, order("farm_a", 1, 400_000), availableGrossLimitCents = 0)
            .outcome shouldBe EnterpriseReservationOutcome.ENVELOPE_EXHAUSTED
        ledger.reserve(policy, order("farm_a", 2, 400_000), availableGrossLimitCents = 500_000)
            .outcome shouldBe EnterpriseReservationOutcome.RESERVED
        ledger.reserve(policy, order("farm_a", 2, 400_000), availableGrossLimitCents = 0)
            .outcome shouldBe EnterpriseReservationOutcome.ALREADY_RESERVED
        ledger.hasReservation(ActivityKind.FARM, "farm_a", 2) shouldBe true
    }
})

private fun policy(
    activity: ActivityKind,
    companyId: String = "communal",
    envelope: Long = 2_000_000,
    operatingCostPercent: Int = 20,
    workerBonusPercent: Int = 30,
    dividendPercent: Int = 50,
    weeklyUpkeepCents: Long = 100_000,
    retainedReportWeeks: Int = 16,
): WorksiteEnterprisePolicy =
    WorksiteEnterprisePolicy(
        activity = activity,
        companyId = companyId,
        licenseGrossEnvelopeCents = envelope,
        operatingCostPercent = operatingCostPercent,
        workerBonusPercent = workerBonusPercent,
        dividendPercent = dividendPercent,
        weeklyUpkeepCents = weeklyUpkeepCents,
        retainedReportWeeks = retainedReportWeeks,
    )

private fun order(
    worksiteId: String,
    sequence: Long,
    gross: Long,
    weekStartEpochDay: Long = 20_695,
): ActiveWorksiteEnterpriseOrder =
    ActiveWorksiteEnterpriseOrder(
        worksiteId = worksiteId,
        orderId = "bakery_supply",
        sequence = sequence,
        grossTariffCents = gross,
        reservedAt = 1_800_000_000_000,
        businessWeekStartEpochDay = weekStartEpochDay,
    )

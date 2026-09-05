package ru.ruscrafting.farms.domain.enterprise

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.ActivityKind
import java.util.UUID

class WorksiteEnterpriseParticipationTest : FunSpec({
    val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val worker = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val ledger = WorksiteEnterpriseParticipationLedger()

    beforeTest { ledger.replace(null) }

    test("one shareholder ballot is accepted for next week and duplicate is rejected") {
        ledger.advance(ActivityKind.FARM, "communal_farm", 100, mapOf(owner to 10))
        ledger.vote(ActivityKind.FARM, "communal_farm", owner, WorksiteEnterprisePlan.CHALLENGE, 107, mapOf(owner to 10)).outcome shouldBe WorksiteEnterpriseVoteOutcome.ACCEPTED
        ledger.vote(ActivityKind.FARM, "communal_farm", owner, WorksiteEnterprisePlan.TEAM, 107, mapOf(owner to 10)).outcome shouldBe WorksiteEnterpriseVoteOutcome.ALREADY_VOTED
    }

    test("stale week and inactive advisory votes are rejected") {
        ledger.advance(ActivityKind.FARM, "communal_farm", 100, emptyMap())
        ledger.vote(ActivityKind.FARM, "communal_farm", worker, WorksiteEnterprisePlan.TEAM, 114, emptyMap()).outcome shouldBe WorksiteEnterpriseVoteOutcome.STALE_WEEK
        ledger.vote(ActivityKind.FARM, "communal_farm", worker, WorksiteEnterprisePlan.TEAM, 107, emptyMap()).outcome shouldBe WorksiteEnterpriseVoteOutcome.NOT_ELIGIBLE
    }

    test("completion watermark is exact once across restart and milestones count orders") {
        ledger.advance(ActivityKind.FARM, "communal_farm", 100, emptyMap())
        ledger.recordCompleted(ActivityKind.FARM, "communal_farm", "communal_farm", 1, 100, mapOf(worker to 4)) shouldBe true
        ledger.recordCompleted(ActivityKind.FARM, "communal_farm", "communal_farm", 1, 100, mapOf(worker to 4)) shouldBe false
        val restored = WorksiteEnterpriseParticipationLedger()
        restored.replace(ledger.snapshot())
        restored.recordCompleted(ActivityKind.FARM, "communal_farm", "communal_farm", 1, 100, mapOf(worker to 4)) shouldBe false
        restored.view(ActivityKind.FARM, "communal_farm", worker).project.apply {
            contributions shouldBe 1
            personalContributions shouldBe 4
            personalCompletedOrders shouldBe 1
        }
    }

    test("quorum and tie preserve plan; current holdings are used at rollover") {
        ledger.advance(ActivityKind.FARM, "communal_farm", 100, mapOf(owner to 10))
        ledger.vote(ActivityKind.FARM, "communal_farm", owner, WorksiteEnterprisePlan.TEAM, 107, mapOf(owner to 10))
        ledger.advance(ActivityKind.FARM, "communal_farm", 107, mapOf(owner to 1)).plan shouldBe WorksiteEnterprisePlan.TEAM
        ledger.advance(ActivityKind.FARM, "communal_farm", 114, mapOf(owner to 1)).plan shouldBe WorksiteEnterprisePlan.TEAM
    }
})

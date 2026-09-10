package ru.ruscrafting.farms.paper.mine.incident.scenario

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.util.UUID

class MineScenarioLeaseRecoveryTest : FunSpec({
    test("reclaims expired and offline leases while preserving active and completed targets") {
        val expiredOwner = UUID.randomUUID()
        val offlineOwner = UUID.randomUUID()
        val activeOwner = UUID.randomUUID()
        val contributor = UUID.randomUUID()
        val planned = ObjectiveTargetPool.plan(
            WorksiteObjectiveKey("mine", "scenario_0", 1),
            required = 2,
            candidates = (0 until 4).map { index ->
                ObjectiveTargetCandidate(
                    "target_$index",
                    WorksitePosition("world", index, 64, 0),
                    ObjectiveTargetRole("carry"),
                    index.toLong(),
                )
            },
        )
        val state = planned.copy(
            targets = listOf(
                planned.targets[0].copy(
                    status = ObjectiveTargetStatus.LEASED,
                    leasedBy = expiredOwner,
                    leaseExpiresAt = 1_000L,
                ),
                planned.targets[1].copy(
                    status = ObjectiveTargetStatus.LEASED,
                    leasedBy = offlineOwner,
                    leaseExpiresAt = 10_000L,
                ),
                planned.targets[2].copy(
                    status = ObjectiveTargetStatus.LEASED,
                    leasedBy = activeOwner,
                    leaseExpiresAt = 10_000L,
                ),
                planned.targets[3].copy(status = ObjectiveTargetStatus.COMPLETED),
            ),
            contributions = mapOf(contributor to 3),
        )

        val reclaimed = reclaimMineScenarioLeases(state, now = 2_000L) { it == activeOwner }

        reclaimed.targets[0].status shouldBe ObjectiveTargetStatus.AVAILABLE
        reclaimed.targets[1].status shouldBe ObjectiveTargetStatus.AVAILABLE
        reclaimed.targets[2].status shouldBe ObjectiveTargetStatus.LEASED
        reclaimed.targets[2].leasedBy shouldBe activeOwner
        reclaimed.targets[3].status shouldBe ObjectiveTargetStatus.COMPLETED
        reclaimed.completed shouldBe state.completed
        reclaimed.contributions shouldBe state.contributions
    }
})

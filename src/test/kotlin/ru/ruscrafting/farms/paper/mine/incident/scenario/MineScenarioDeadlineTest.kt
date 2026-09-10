package ru.ruscrafting.farms.paper.mine.incident.scenario

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftEvent
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

class MineScenarioDeadlineTest : FunSpec({
    test("lift breakdown aborts at the hard deadline and consumes its incident slot") {
        val ordinary = ObjectiveTargetPool.plan(
            WorksiteObjectiveKey("mine", "ordinary", 4),
            required = 1,
            candidates = listOf(ObjectiveTargetCandidate(
                "ore_0", WorksitePosition("world", 0, 64, 0), ObjectiveTargetRole("ore"), 0L,
            )),
        )
        val state = MineShiftState(
            phase = MinePhase.INCIDENT,
            sequence = 4,
            incidentCursor = 1,
            resumePhase = MinePhase.MINING,
            resumeObjective = ordinary,
            incident = MineIncidentState(MineIncidentType.LIFT_BREAKDOWN, required = 3, startedAt = 1_000L),
        )

        liftBreakdownDeadlineExceeded(state.incident!!, 181_000L) shouldBe true
        val aborted = MineShiftEngine.abortIncident(state)

        aborted.accepted shouldBe true
        aborted.state.phase shouldBe MinePhase.MINING
        aborted.state.incident shouldBe null
        aborted.state.incidentCursor shouldBe 2
        aborted.state.objective shouldBe ordinary
        aborted.events shouldBe listOf(MineShiftEvent.INCIDENT_ABORTED)
    }

    test("deadline is exclusive to lift breakdown and does not trigger early") {
        val lift = MineIncidentState(MineIncidentType.LIFT_BREAKDOWN, required = 1, startedAt = 1_000L)
        val caveIn = lift.copy(type = MineIncidentType.CAVE_IN)

        liftBreakdownDeadlineExceeded(lift, 180_999L) shouldBe false
        liftBreakdownDeadlineExceeded(lift, 181_000L) shouldBe true
        liftBreakdownDeadlineExceeded(caveIn, 181_000L) shouldBe false
    }
})

package ru.ruscrafting.farms.domain.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.FarmEventTypeDefinition
import ru.ruscrafting.farms.domain.FarmEventTypeRegistry
import java.util.UUID

class LumberIncidentEngineTest : FunSpec({
    val actor = UUID(0, 1)
    val other = UUID(0, 2)
    val piles = lumberSmolderPositions()

    test("the first round is normal and every second completed round alternates repairs at its exact pass boundary") {
        for (completed in 0L..7L) for (passes in 0..2) {
            val ready = lumberIncidentBoundary(completed + 1, completed, passes)
            val scheduled = LumberIncidentEngine.schedule(ready, piles)
            val expected = when {
                completed % 4 == 1L && passes == 1 -> LumberIncidentType.FEED_JAM
                completed % 4 == 3L && passes == 2 -> LumberIncidentType.SAWDUST_SMOLDER
                else -> null
            }
            scheduled.incident?.type shouldBe expected
            if (expected == null) scheduled shouldBe ready else {
                scheduled.incidentHandledRoundId shouldBe ready.roundId
                LumberStateValidation.validate(scheduled)
            }
        }
        LumberIncidentType.entries.forEach { type ->
            (FarmEventTypeRegistry.definition(type) is FarmEventTypeDefinition.FieldEvent) shouldBe true
        }
    }

    test("repair holds every baseline action and completion resumes the same workpiece without repeating the event") {
        val ready = lumberIncidentBoundary()
        var state = LumberIncidentEngine.schedule(ready, piles)
        for (action in LumberAction.entries) LumberEngine.act(state, LumberInput(action), actor, 90_000, 5) shouldBe state
        LumberEngine.tick(state, 90_000, 60, emptyList()) shouldBe state
        state = LumberIncidentEngine.startCrank(state, actor)
        val first = LumberIncidentEngine.completeTurn(state, actor, state.incidentGeneration)
        first.incident?.crankTurns shouldBe 1
        LumberIncidentEngine.completeTurn(first, other, first.incidentGeneration) shouldBe first
        val resumed = LumberIncidentEngine.completeTurn(first, actor, first.incidentGeneration)
        resumed.incident shouldBe null
        resumed.tree shouldBe ready.tree
        resumed.cart shouldBe ready.cart
        resumed.phase shouldBe ready.phase
        resumed.sawPasses shouldBe ready.sawPasses
        resumed.completedRounds shouldBe ready.completedRounds
        LumberIncidentEngine.schedule(resumed, piles) shouldBe resumed
        LumberEngine.act(resumed, LumberInput(LumberAction.SAW_LEVER), actor, 90_000, 5).sawRunning shouldBe true
    }

    test("safe admin replacement increments generation and retires stale operators even for the same event") {
        val ready = lumberIncidentBoundary(completedRounds = 0)
        var first = LumberIncidentEngine.start(ready, LumberIncidentType.FEED_JAM, force = true)
        first = LumberIncidentEngine.startCrank(first, actor)
        first = LumberIncidentEngine.completeTurn(first, actor, first.incidentGeneration)
        val replaced = LumberIncidentEngine.start(first, LumberIncidentType.FEED_JAM, force = true)
        replaced.incidentGeneration shouldBe first.incidentGeneration + 1
        replaced.incident?.crankTurns shouldBe 0
        replaced.incident?.crankOperator shouldBe null
        LumberIncidentEngine.completeTurn(replaced, actor, first.incidentGeneration) shouldBe replaced
        for (phase in LumberPhase.entries.filterNot { it in setOf(LumberPhase.SAWING, LumberPhase.COLLECTING_BOARDS) }) {
            val moving = ready.copy(phase = phase)
            LumberIncidentEngine.start(moving, LumberIncidentType.FEED_JAM, force = true) shouldBe moving
        }
        val running = ready.copy(sawRunning = true)
        LumberIncidentEngine.start(running, LumberIncidentType.FEED_JAM, force = true) shouldBe running
        val cartHeld = lumberIncidentBoundary(passes = 2).copy(boardCartLoaded = true, boardsCarrier = actor)
        LumberIncidentEngine.start(cartHeld, LumberIncidentType.FEED_JAM, force = true) shouldBe cartHeld
    }

    test("three distinct physical piles count once and old generations cannot extinguish replacement targets") {
        val ready = lumberIncidentBoundary(roundId = 4, completedRounds = 3, passes = 2)
        var state = LumberIncidentEngine.schedule(ready, piles)
        val generation = state.incidentGeneration
        state = LumberIncidentEngine.extinguish(state, "pile_0", actor, generation)
        state.incident?.hotspots?.completed shouldBe 1
        LumberIncidentEngine.extinguish(state, "pile_0", actor, generation) shouldBe state
        LumberIncidentEngine.extinguish(state, "pile_1", actor, generation - 1) shouldBe state
        state = LumberIncidentEngine.extinguish(state, "pile_1", other, generation)
        state = LumberIncidentEngine.extinguish(state, "pile_2", actor, generation)
        state.incident shouldBe null
        state.phase shouldBe LumberPhase.COLLECTING_BOARDS
        state.sawPasses shouldBe 2
        state.completedRounds shouldBe 3
        LumberIncidentEngine.schedule(state, piles) shouldBe state
    }

    test("release and restart keep completed repair progress while clearing the operator") {
        var state = LumberIncidentEngine.startCrank(LumberIncidentEngine.schedule(lumberIncidentBoundary(), piles), actor)
        state = LumberIncidentEngine.completeTurn(state, actor, state.incidentGeneration)
        val released = LumberEngine.release(state, actor, requireNotNull(state.cart))
        released.incident?.crankTurns shouldBe 1
        released.incident?.crankOperator shouldBe null
        val restored = LumberEngine.recover(state, requireNotNull(state.cart), 9_000)
        restored.incident shouldBe released.incident
        restored.incidentGeneration shouldBe state.incidentGeneration
        restored.incidentHandledRoundId shouldBe state.incidentHandledRoundId
        val finished = LumberIncidentEngine.completeTurn(LumberIncidentEngine.startCrank(restored, other), other, restored.incidentGeneration)
        val next = LumberEngine.start(finished.copy(phase = LumberPhase.IDLE), requireNotNull(state.tree), requireNotNull(state.cart), 10_000)
        next.incidentGeneration shouldBe state.incidentGeneration
    }

    test("the second saw pass schedules smolder before any crane and clearing it starts a fresh output dwell") {
        val ready = lumberIncidentBoundary(roundId = 4, completedRounds = 3)
        val running = LumberEngine.act(ready, LumberInput(LumberAction.SAW_LEVER), actor, 1_000, 5)
        var state = LumberIncidentEngine.schedule(LumberEngine.tick(running, 13_000, 60, emptyList()), piles)
        state.phase shouldBe LumberPhase.COLLECTING_BOARDS
        state.incident?.type shouldBe LumberIncidentType.SAWDUST_SMOLDER
        LumberEngine.tick(state, 90_000, 60, emptyList()) shouldBe state
        repeat(3) { state = LumberIncidentEngine.extinguish(state, "pile_$it", actor, state.incidentGeneration, 90_000) }
        state.incident shouldBe null
        state.phaseStartedAt shouldBe 90_000L
        LumberEngine.tick(state, 90_999, 60, emptyList()) shouldBe state
        val crane = LumberIncidentEngine.schedule(LumberEngine.tick(state, 91_000, 60, emptyList()), piles)
        crane.phase shouldBe LumberPhase.CRANE_TRANSFER
        crane.incident shouldBe null
        crane.tree shouldBe ready.tree
        crane.sawPasses shouldBe 2
        val completed = LumberEngine.tick(crane, 105_000, 60, emptyList())
        completed.completedRounds shouldBe 4L
        completed.incidentGeneration shouldBe state.incidentGeneration
        completed.incidentHandledRoundId shouldBe state.incidentHandledRoundId
        completed.contributors shouldBe state.contributors
    }
})

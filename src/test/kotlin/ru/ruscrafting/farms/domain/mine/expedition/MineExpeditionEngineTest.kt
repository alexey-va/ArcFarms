package ru.ruscrafting.farms.domain.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.MineWorkshopHeat
import ru.ruscrafting.farms.domain.MineIncidentType

class MineExpeditionEngineTest : FunSpec({
    val placement = MineExpeditionPlacement("rc_arcfarms_expeditions", 100, -32, 200, 73L)

    test("factory programs use different machines and survive JSON without changing ten checkpoint credits") {
        val plan=MinePermanentExpeditionLayout.build(MineExpeditionKind.DEAD_FACTORY,73)
        val routes=(0..2).map { program ->
            var state=MineExpeditionEngine.initial(MineIncidentType.DEAD_FACTORY,placement,program)
            val ids=MineExpeditionObjectives.targets(plan,state,null).map { it.id }
            val gson=com.google.gson.Gson()
            state=gson.fromJson(gson.toJson(state),MineExpeditionState::class.java)
            state.validate();state.factoryProgram shouldBe program
            var credits=0
            while(state.stage!=MineExpeditionStage.COMPLETE) {
                val now=if(state.stage==MineExpeditionStage.FACTORY_HEAT) state.heatStartedAt+4_000 else 100L
                val next=MineExpeditionEngine.completeTarget(state,MineExpeditionEngine.currentTarget(state)!!,now)
                next.accepted shouldBe true
                credits+=MineExpeditionEngine.progressDelta(MineIncidentType.DEAD_FACTORY,state,next.state)
                state=next.state
            }
            credits shouldBe 10
            ids
        }
        routes.distinct().size shouldBe 3
        val legacy="""{"placement":{"world":"world","originX":0,"originY":64,"originZ":0,"seed":1,"geometryVersion":3},"stage":"FACTORY_WATER","completed":[],"motionStep":0,"branch":0,"heatStartedAt":0}"""
        com.google.gson.Gson().fromJson(legacy,MineExpeditionState::class.java).apply { validate() }.factoryProgram shouldBe 0
    }

    test("required progress and idempotent descent actions are deterministic") {
        MineExpeditionEngine.required(MineIncidentType.LAST_DESCENT) shouldBe 12
        var state = MineExpeditionEngine.initial(MineIncidentType.LAST_DESCENT, placement)
        MineExpeditionEngine.action(state) shouldBe MineExpeditionAction.MOTION
        MineExpeditionEngine.advanceMotion(state, routeSteps = 2, steps = 1).state.motionStep shouldBe 1
        val middle = MineExpeditionEngine.advanceMotion(state, routeSteps = 2, steps = 2)
        middle.accepted shouldBe true
        state = middle.state
        state.stage shouldBe MineExpeditionStage.DESCENT_COUNTERWEIGHTS
        MineExpeditionEngine.progress(MineIncidentType.LAST_DESCENT, state) shouldBe 1

        state = (0..2).fold(state) { current, target ->
            MineExpeditionEngine.completeTarget(current, target, 100L).state
        }
        state.stage shouldBe MineExpeditionStage.DESCENT_POWER_CELLS
        MineExpeditionEngine.completeTarget(state, 0, 101L).accepted shouldBe true
        state = (0..2).fold(state) { current, target ->
            MineExpeditionEngine.completeTarget(current, target, 104L).state
        }
        state.stage shouldBe MineExpeditionStage.DESCENT_BOTTOM
        MineExpeditionEngine.completeTarget(state, 0, 105L).accepted shouldBe false
        state = MineExpeditionEngine.advanceMotion(state, routeSteps = 1).state
        state.stage shouldBe MineExpeditionStage.DESCENT_CORE_VALVES
        state = (0..2).fold(state) { current, target ->
            MineExpeditionEngine.completeTarget(current, target, 106L).state
        }
        state.stage shouldBe MineExpeditionStage.DESCENT_ENGINE
        val finished = MineExpeditionEngine.completeTarget(state, 0, 107L)
        finished.finished shouldBe true
        MineExpeditionEngine.progress(MineIncidentType.LAST_DESCENT, finished.state) shouldBe 12
    }

    test("ark branch is persisted and wrong stage or duplicate work is rejected") {
        var state = MineExpeditionEngine.initial(MineIncidentType.DRILLING_ARK, placement)
        state = (0..1).fold(state) { current, target ->
            MineExpeditionEngine.completeTarget(current, target, 100L).state
        }
        state = MineExpeditionEngine.advanceMotion(state, routeSteps = 3, steps = 3).state
        state.stage shouldBe MineExpeditionStage.ARK_BRANCH
        MineExpeditionEngine.completeTarget(state, 0, 100L).accepted shouldBe false
        state = MineExpeditionEngine.chooseBranch(state, 2).state
        state.branch shouldBe 2
        state.stage shouldBe MineExpeditionStage.ARK_JAM
        MineExpeditionEngine.chooseBranch(state, 1).accepted shouldBe false
        val first = MineExpeditionEngine.completeTarget(state, 0, 101L)
        first.accepted shouldBe true
        MineExpeditionEngine.completeTarget(first.state, 0, 102L).accepted shouldBe false
        MineExpeditionEngine.progress(MineIncidentType.DRILLING_ARK, first.state) shouldBe 5
    }

    test("ark completes every persisted stage including survey cores and home motion") {
        var state = MineExpeditionEngine.initial(MineIncidentType.DRILLING_ARK, placement)
        state = (0..1).fold(state) { current, target -> MineExpeditionEngine.completeTarget(current, target, 100L).state }
        state = MineExpeditionEngine.advanceMotion(state, routeSteps = 2, steps = 2).state
        state = MineExpeditionEngine.chooseBranch(state, 1).state
        state = (0..2).fold(state) { current, target -> MineExpeditionEngine.completeTarget(current, target, 101L).state }
        state = (0..1).fold(state) { current, target -> MineExpeditionEngine.completeTarget(current, target, 102L).state }
        state = MineExpeditionEngine.advanceMotion(state, routeSteps = 2, steps = 2).state
        state = (0..2).fold(state) { current, target -> MineExpeditionEngine.completeTarget(current, target, 103L).state }
        val finished = MineExpeditionEngine.advanceMotion(state, routeSteps = 2, steps = 2)
        finished.finished shouldBe true
        finished.state.stage shouldBe MineExpeditionStage.COMPLETE
        MineExpeditionEngine.progress(MineIncidentType.DRILLING_ARK, finished.state) shouldBe 14
    }

    test("factory heat window can be reheated after a restart or missed attempt") {
        var state = MineExpeditionEngine.initial(MineIncidentType.DEAD_FACTORY, placement)
        state = (0..2).fold(state) { current, target ->
            MineExpeditionEngine.completeTarget(current, target, 100L).state
        }
        state = (0..2).fold(state) { current, target ->
            MineExpeditionEngine.completeTarget(current, target, 200L).state
        }
        state.stage shouldBe MineExpeditionStage.FACTORY_HEAT
        MineExpeditionEngine.canFinishHeat(state, 4_199L) shouldBe false
        MineExpeditionEngine.canFinishHeat(state, 4_200L) shouldBe true
        MineExpeditionEngine.completeTarget(state, 0, 9_000L).accepted shouldBe false

        state = MineExpeditionEngine.reheat(state, 9_000L)
        val afterRestart = state.copy()
        MineExpeditionEngine.canFinishHeat(afterRestart, 13_000L) shouldBe true
        state = MineExpeditionEngine.completeTarget(afterRestart, 0, 13_000L).state
        state = MineExpeditionEngine.completeTarget(state, 0, 13_001L).state
        state = MineExpeditionEngine.completeTarget(state, 0, 13_002L).state
        val finished = MineExpeditionEngine.completeTarget(state, 0, 13_003L)
        finished.finished shouldBe true
        MineExpeditionEngine.progress(MineIncidentType.DEAD_FACTORY, finished.state) shouldBe 10
    }

    test("connected factory heat completion uses the ready air-control state") {
        val state = MineExpeditionState(placement, MineExpeditionStage.FACTORY_HEAT)
        MineExpeditionEngine.completeFactoryHeat(state, MineWorkshopHeat(running = true, elapsedMillis = 3_999), 4_000)
            .accepted shouldBe false
        val step = MineExpeditionEngine.completeFactoryHeat(
            state,
            MineWorkshopHeat(elapsedMillis = MineWorkshopHeat.REQUIRED_MILLIS),
            12_000,
        )
        step.accepted shouldBe true
        step.state.stage shouldBe MineExpeditionStage.FACTORY_POUR
    }
})

package ru.ruscrafting.farms.domain.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.ruscrafting.farms.domain.MineIncidentType

class MineExpeditionMotionTest : FunSpec({
    test("descent stages use lift stops and bounded adjacent motion") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.LAST_DESCENT, 11L)
        val placement = MineExpeditionPlacement("rc_arcfarms_expeditions", 0, 0, 0, 11L)
        val middle = MineExpeditionEngine.initial(MineIncidentType.LAST_DESCENT, placement)
        val middleRoute = MineExpeditionMotion.points(plan, middle)
        middleRoute.first() shouldBe plan.stations.getValue("lift_top")
        middleRoute.last() shouldBe plan.stations.getValue("lift_middle")
        middleRoute.zipWithNext().all { (a, b) -> distance(a, b) == 1 } shouldBe true
        val bottom = middle.copy(stage = MineExpeditionStage.DESCENT_BOTTOM, motionStep = 0)
        val bottomRoute = MineExpeditionMotion.points(plan, bottom)
        bottomRoute.first() shouldBe plan.stations.getValue("lift_middle")
        bottomRoute.last() shouldBe plan.stations.getValue("lift_bottom")
        MineExpeditionMotion.steps(plan, middle) shouldNotBe 0
    }

    test("ark branch is selected only after the shared fork and home reverses it") {
        val plan = MineExpeditionGenerator.plan(MineExpeditionKind.DRILLING_ARK, 12L)
        val placement = MineExpeditionPlacement("rc_arcfarms_expeditions", 0, 0, 0, 12L)
        val fork = MineExpeditionEngine.initial(MineIncidentType.DRILLING_ARK, placement)
            .copy(stage = MineExpeditionStage.ARK_FORK)
        val forkRoute = MineExpeditionMotion.points(plan, fork)
        forkRoute.first() shouldBe plan.stations.getValue("ark_start")
        forkRoute.last() shouldBe plan.stations.getValue("ark_mid")
        val chamber = fork.copy(stage = MineExpeditionStage.ARK_CHAMBER, branch = 2)
        val home = chamber.copy(stage = MineExpeditionStage.ARK_HOME, motionStep = 0)
        val chamberRoute = MineExpeditionMotion.points(plan, chamber)
        val homeRoute = MineExpeditionMotion.points(plan, home)
        chamberRoute.first() shouldBe plan.stations.getValue("ark_mid")
        chamberRoute.last() shouldBe plan.stations.getValue("ark_end")
        homeRoute.first() shouldBe plan.stations.getValue("ark_end")
        homeRoute.last() shouldBe plan.stations.getValue("ark_start")
        chamberRoute.zipWithNext().all { (a, b) -> distance(a, b) == 1 } shouldBe true
        homeRoute.zipWithNext().all { (a, b) -> distance(a, b) == 1 } shouldBe true
        chamberRoute shouldNotBe MineExpeditionMotion.points(plan, chamber.copy(branch = 1))
    }
}) {
    companion object {
        private fun distance(left: ExpeditionPoint, right: ExpeditionPoint): Int =
            kotlin.math.abs(left.x - right.x) + kotlin.math.abs(left.y - right.y) + kotlin.math.abs(left.z - right.z)
    }
}

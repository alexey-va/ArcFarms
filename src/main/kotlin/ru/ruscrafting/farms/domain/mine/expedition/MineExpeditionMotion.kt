package ru.ruscrafting.farms.domain.mine.expedition

import kotlin.math.abs

/**
 * Replayable route selection and interpolation for moving expedition machines.
 * This layer has no Bukkit knowledge and never mutates a plan or persisted
 * state. Callers may use [points] to move a real block blueprint in bounded
 * cardinal steps and [position] to animate a small visual carrier.
 */
object MineExpeditionMotion {
    /** Route points for the currently moving stage, expanded to adjacent blocks. */
    fun points(plan: MineExpeditionPlan, state: MineExpeditionState): List<ExpeditionPoint> {
        val waypoints = waypoints(plan, state)
        require(waypoints.size >= 2) { "Stage ${state.stage} has no movement route" }
        return expand(waypoints)
    }

    fun steps(plan: MineExpeditionPlan, state: MineExpeditionState): Int = points(plan, state).lastIndex

    /** Current feet coordinate, clamped so a reconstructed state is safe. */
    fun position(plan: MineExpeditionPlan, state: MineExpeditionState): ExpeditionPoint {
        val route = points(plan, state)
        return route[state.motionStep.coerceIn(0, route.lastIndex)]
    }

    fun position(plan: MineExpeditionPlan, state: MineExpeditionState, step: Int): ExpeditionPoint {
        val route = points(plan, state)
        return route[step.coerceIn(0, route.lastIndex)]
    }

    /** Returns a bounded next state; the engine remains the authority on stage transitions. */
    fun advance(state: MineExpeditionState, plan: MineExpeditionPlan, steps: Int = 1): MineExpeditionStep {
        return MineExpeditionEngine.advanceMotion(state, MineExpeditionMotion.steps(plan, state), steps)
    }

    private fun waypoints(plan: MineExpeditionPlan, state: MineExpeditionState): List<ExpeditionPoint> = when (state.stage) {
        MineExpeditionStage.DESCENT_MIDDLE -> plan.routes.getValue("lift").take(2)
        MineExpeditionStage.DESCENT_BOTTOM -> plan.routes.getValue("lift").drop(1).take(2)
        MineExpeditionStage.DESCENT_ENGINE -> {
            require(MineExpeditionEngine.isModernLastDescent(state)) {
                "Legacy descent engine has no movement route"
            }
            plan.routes.getValue("lift").asReversed()
        }
        MineExpeditionStage.ARK_FORK -> {
            val start = plan.stations.getValue("ark_start")
            val mid = plan.stations.getValue("ark_mid")
            listOf(start, mid)
        }
        MineExpeditionStage.ARK_CHAMBER -> selectedArk(plan, state.branch).dropWhile {
            it != plan.stations.getValue("ark_mid")
        }
        MineExpeditionStage.ARK_HOME -> selectedArk(plan, state.branch).asReversed()
        else -> error("Stage ${state.stage} does not move an expedition machine")
    }

    private fun selectedArk(plan: MineExpeditionPlan, branch: Int): List<ExpeditionPoint> {
        val route = when (branch) {
            1 -> plan.routes.getValue("ark_left")
            2 -> plan.routes.getValue("ark_right")
            else -> error("Ark branch must be chosen before this movement stage")
        }
        return route
    }

    private fun expand(waypoints: List<ExpeditionPoint>): List<ExpeditionPoint> {
        val expanded = mutableListOf<ExpeditionPoint>()
        waypoints.zipWithNext().forEach { (from, to) ->
            val segment = cardinalLine(from, to)
            if (expanded.isEmpty()) expanded += segment else expanded += segment.drop(1)
        }
        require(expanded.zipWithNext().all { (a, b) -> manhattan(a, b) == 1 }) {
            "Expedition motion route is not cardinal"
        }
        return expanded
    }

    private fun cardinalLine(from: ExpeditionPoint, to: ExpeditionPoint): List<ExpeditionPoint> {
        val result = mutableListOf(from)
        var point = from
        fun advance(axis: Char, target: Int) {
            while (when (axis) {
                'x' -> point.x != target
                'y' -> point.y != target
                else -> point.z != target
            }) {
                point = if (axis == 'x') {
                    point.offset(dx = if (target > point.x) 1 else -1)
                } else if (axis == 'y') {
                    point.offset(dy = if (target > point.y) 1 else -1)
                } else {
                    point.offset(dz = if (target > point.z) 1 else -1)
                }
                result += point
            }
        }
        // Lift stops change only Y. Ark branches keep their forward +Z motion
        // after an X dock shift; HOME deliberately traverses the selected route
        // in reverse because the persisted stage is an explicit return trip.
        advance('y', to.y)
        advance('x', to.x)
        advance('z', to.z)
        return result
    }

    private fun manhattan(left: ExpeditionPoint, right: ExpeditionPoint): Int =
        abs(left.x - right.x) + abs(left.y - right.y) + abs(left.z - right.z)
}

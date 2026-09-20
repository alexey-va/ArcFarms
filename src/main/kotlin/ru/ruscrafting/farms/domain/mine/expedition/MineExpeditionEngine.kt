package ru.ruscrafting.farms.domain.mine.expedition

import ru.ruscrafting.farms.domain.MineIncidentType

/**
 * Pure progression for off-site mine scenes. Physical controllers own reachability,
 * inventory and participant checks; this object only accepts an already verified action.
 */
object MineExpeditionEngine {
    const val MAX_MOTION_STEPS_PER_CALL = 64
    const val HEAT_MILLIS = 4_000L
    const val HEAT_WINDOW_MILLIS = 4_000L

    private val descentStages = listOf(
        MineExpeditionStage.DESCENT_MIDDLE,
        MineExpeditionStage.DESCENT_COUNTERWEIGHTS,
        MineExpeditionStage.DESCENT_POWER_CELLS,
        MineExpeditionStage.DESCENT_BOTTOM,
        MineExpeditionStage.DESCENT_CORE_VALVES,
        MineExpeditionStage.DESCENT_ENGINE,
    )
    private val arkStages = listOf(
        MineExpeditionStage.ARK_FUEL,
        MineExpeditionStage.ARK_FORK,
        MineExpeditionStage.ARK_BRANCH,
        MineExpeditionStage.ARK_JAM,
        MineExpeditionStage.ARK_COOLANT,
        MineExpeditionStage.ARK_CHAMBER,
        MineExpeditionStage.ARK_CORES,
        MineExpeditionStage.ARK_HOME,
    )
    private val factoryStages = listOf(
        MineExpeditionStage.FACTORY_WATER,
        MineExpeditionStage.FACTORY_COAL,
        MineExpeditionStage.FACTORY_HEAT,
        MineExpeditionStage.FACTORY_POUR,
        MineExpeditionStage.FACTORY_CRANE,
        MineExpeditionStage.FACTORY_INSTALL,
    )

    fun supports(type: MineIncidentType): Boolean = kind(type) != null

    fun kind(type: MineIncidentType): MineExpeditionKind? = when (type) {
        MineIncidentType.LAST_DESCENT -> MineExpeditionKind.LAST_DESCENT
        MineIncidentType.DRILLING_ARK -> MineExpeditionKind.DRILLING_ARK
        MineIncidentType.DEAD_FACTORY -> MineExpeditionKind.DEAD_FACTORY
        else -> null
    }

    fun initial(type: MineIncidentType, placement: MineExpeditionPlacement): MineExpeditionState = MineExpeditionState(
        placement = placement,
        stage = when (kind(type)) {
            MineExpeditionKind.LAST_DESCENT -> MineExpeditionStage.DESCENT_MIDDLE
            MineExpeditionKind.DRILLING_ARK -> MineExpeditionStage.ARK_FUEL
            MineExpeditionKind.DEAD_FACTORY -> MineExpeditionStage.FACTORY_WATER
            null -> error("Not a mine expedition incident: $type")
        },
    )

    /** Number of durable action credits expected by MineIncidentState.required. */
    fun required(type: MineIncidentType): Int = stages(type).sumOf(::checkpointWeight)

    /** Progress is derived from the typed stage so incident progress cannot drift from the scene. */
    fun progress(type: MineIncidentType, state: MineExpeditionState): Int {
        val sequence = stages(type)
        if (state.stage == MineExpeditionStage.COMPLETE) return required(type)
        require(state.stage in sequence) { "Expedition stage ${state.stage} does not belong to $type" }
        val index = sequence.indexOf(state.stage)
        return sequence.take(index).sumOf(::checkpointWeight) + when (action(state)) {
            MineExpeditionAction.TARGET, MineExpeditionAction.HEAT -> state.completed.size
            MineExpeditionAction.MOTION, MineExpeditionAction.BRANCH, MineExpeditionAction.COMPLETE -> 0
        }
    }

    /** Credits only newly completed scene checkpoints to the ordinary incident coordinator. */
    fun progressDelta(
        type: MineIncidentType,
        before: MineExpeditionState,
        after: MineExpeditionState,
    ): Int = (progress(type, after) - progress(type, before)).coerceAtLeast(0)

    fun action(state: MineExpeditionState): MineExpeditionAction = when (state.stage) {
        MineExpeditionStage.DESCENT_MIDDLE,
        MineExpeditionStage.DESCENT_BOTTOM,
        MineExpeditionStage.ARK_FORK,
        MineExpeditionStage.ARK_CHAMBER,
        MineExpeditionStage.ARK_HOME,
            -> MineExpeditionAction.MOTION

        MineExpeditionStage.ARK_BRANCH -> MineExpeditionAction.BRANCH
        MineExpeditionStage.FACTORY_HEAT -> MineExpeditionAction.HEAT
        MineExpeditionStage.COMPLETE -> MineExpeditionAction.COMPLETE
        else -> MineExpeditionAction.TARGET
    }

    /** Target count for the current target/heat stage; motion and branch use their own methods. */
    fun targetCount(state: MineExpeditionState): Int = when (state.stage) {
        MineExpeditionStage.DESCENT_COUNTERWEIGHTS,
        MineExpeditionStage.DESCENT_POWER_CELLS,
        MineExpeditionStage.DESCENT_CORE_VALVES,
        MineExpeditionStage.ARK_JAM,
        MineExpeditionStage.ARK_CORES,
            -> 3
        MineExpeditionStage.ARK_FUEL, MineExpeditionStage.ARK_COOLANT -> 2
        MineExpeditionStage.DESCENT_ENGINE,
        MineExpeditionStage.FACTORY_HEAT,
        MineExpeditionStage.FACTORY_POUR,
        MineExpeditionStage.FACTORY_CRANE,
        MineExpeditionStage.FACTORY_INSTALL,
            -> 1
        MineExpeditionStage.FACTORY_WATER, MineExpeditionStage.FACTORY_COAL -> 3
        else -> 0
    }

    fun currentTarget(state: MineExpeditionState): Int? {
        if (action(state) !in setOf(MineExpeditionAction.TARGET, MineExpeditionAction.HEAT)) return null
        val count = targetCount(state)
        return (0 until count).firstOrNull { it !in state.completed }
    }

    /** A normalized value for UI motion bars; route length is supplied by the generated plan. */
    fun motionProgress(state: MineExpeditionState, routeSteps: Int): Double {
        require(routeSteps in 1..1_000_000)
        require(action(state) == MineExpeditionAction.MOTION) { "Motion progress requested for ${state.stage}" }
        return (state.motionStep.toDouble() / routeSteps).coerceIn(0.0, 1.0)
    }

    fun completeTarget(current: MineExpeditionState, target: Int, now: Long): MineExpeditionStep {
        require(now >= 0L)
        val operation = action(current)
        val total = targetCount(current)
        if (operation !in setOf(MineExpeditionAction.TARGET, MineExpeditionAction.HEAT) ||
            target !in 0 until total || target in current.completed
        ) return MineExpeditionStep(current, false)
        if (operation == MineExpeditionAction.HEAT && !canFinishHeat(current, now)) {
            return MineExpeditionStep(current, false)
        }
        val completed = current.completed + target
        if (completed.size < total) return MineExpeditionStep(current.copy(completed = completed), true)
        return nextStage(current.copy(completed = completed), now)
    }

    /** Advance at most a bounded number of route steps; the caller must enforce presence and reachability. */
    fun advanceMotion(current: MineExpeditionState, routeSteps: Int, steps: Int = 1): MineExpeditionStep {
        require(routeSteps in 1..1_000_000)
        require(steps in 1..MAX_MOTION_STEPS_PER_CALL)
        if (action(current) != MineExpeditionAction.MOTION || current.motionStep >= routeSteps) {
            return MineExpeditionStep(current, false)
        }
        val nextMotion = (current.motionStep + steps).coerceAtMost(routeSteps)
        if (nextMotion < routeSteps) return MineExpeditionStep(current.copy(motionStep = nextMotion), true)
        return nextStage(current.copy(motionStep = nextMotion), 0L)
    }

    fun chooseBranch(current: MineExpeditionState, branch: Int): MineExpeditionStep {
        if (action(current) != MineExpeditionAction.BRANCH || branch !in 1..2) {
            return MineExpeditionStep(current, false)
        }
        val step = nextStage(current, 0L)
        return step.copy(state = step.state.copy(branch = branch))
    }

    fun canFinishHeat(current: MineExpeditionState, now: Long): Boolean =
        current.stage == MineExpeditionStage.FACTORY_HEAT &&
            now - current.heatStartedAt in HEAT_MILLIS..(HEAT_MILLIS + HEAT_WINDOW_MILLIS)

    /** A missed window starts a new attempt without losing the factory batch. */
    fun reheat(current: MineExpeditionState, now: Long): MineExpeditionState {
        require(now >= 0L)
        return if (current.stage == MineExpeditionStage.FACTORY_HEAT &&
            now - current.heatStartedAt > HEAT_MILLIS + HEAT_WINDOW_MILLIS
        ) current.copy(heatStartedAt = now) else current
    }

    private fun stages(type: MineIncidentType): List<MineExpeditionStage> = when (kind(type)) {
        MineExpeditionKind.LAST_DESCENT -> descentStages
        MineExpeditionKind.DRILLING_ARK -> arkStages
        MineExpeditionKind.DEAD_FACTORY -> factoryStages
        null -> error("Not a mine expedition incident: $type")
    }

    private fun checkpointWeight(stage: MineExpeditionStage): Int = when (actionFor(stage)) {
        MineExpeditionAction.MOTION, MineExpeditionAction.BRANCH -> 1
        MineExpeditionAction.TARGET, MineExpeditionAction.HEAT -> targetCountFor(stage)
        MineExpeditionAction.COMPLETE -> 0
    }

    private fun actionFor(stage: MineExpeditionStage): MineExpeditionAction = when (stage) {
        MineExpeditionStage.DESCENT_MIDDLE,
        MineExpeditionStage.DESCENT_BOTTOM,
        MineExpeditionStage.ARK_FORK,
        MineExpeditionStage.ARK_CHAMBER,
        MineExpeditionStage.ARK_HOME,
            -> MineExpeditionAction.MOTION
        MineExpeditionStage.ARK_BRANCH -> MineExpeditionAction.BRANCH
        MineExpeditionStage.FACTORY_HEAT -> MineExpeditionAction.HEAT
        MineExpeditionStage.COMPLETE -> MineExpeditionAction.COMPLETE
        else -> MineExpeditionAction.TARGET
    }

    private fun targetCountFor(stage: MineExpeditionStage): Int = when (stage) {
        MineExpeditionStage.DESCENT_COUNTERWEIGHTS,
        MineExpeditionStage.DESCENT_POWER_CELLS,
        MineExpeditionStage.DESCENT_CORE_VALVES,
        MineExpeditionStage.ARK_JAM,
        MineExpeditionStage.ARK_CORES,
            -> 3
        MineExpeditionStage.ARK_FUEL, MineExpeditionStage.ARK_COOLANT -> 2
        MineExpeditionStage.DESCENT_ENGINE,
        MineExpeditionStage.FACTORY_HEAT,
        MineExpeditionStage.FACTORY_POUR,
        MineExpeditionStage.FACTORY_CRANE,
        MineExpeditionStage.FACTORY_INSTALL,
            -> 1
        MineExpeditionStage.FACTORY_WATER, MineExpeditionStage.FACTORY_COAL -> 3
        else -> 0
    }

    private fun nextStage(current: MineExpeditionState, now: Long): MineExpeditionStep {
        val next = when (current.stage) {
            MineExpeditionStage.DESCENT_MIDDLE -> MineExpeditionStage.DESCENT_COUNTERWEIGHTS
            MineExpeditionStage.DESCENT_COUNTERWEIGHTS -> MineExpeditionStage.DESCENT_POWER_CELLS
            MineExpeditionStage.DESCENT_POWER_CELLS -> MineExpeditionStage.DESCENT_BOTTOM
            MineExpeditionStage.DESCENT_BOTTOM -> MineExpeditionStage.DESCENT_CORE_VALVES
            MineExpeditionStage.DESCENT_CORE_VALVES -> MineExpeditionStage.DESCENT_ENGINE
            MineExpeditionStage.DESCENT_ENGINE -> MineExpeditionStage.COMPLETE
            MineExpeditionStage.ARK_FUEL -> MineExpeditionStage.ARK_FORK
            MineExpeditionStage.ARK_FORK -> MineExpeditionStage.ARK_BRANCH
            MineExpeditionStage.ARK_BRANCH -> MineExpeditionStage.ARK_JAM
            MineExpeditionStage.ARK_JAM -> MineExpeditionStage.ARK_COOLANT
            MineExpeditionStage.ARK_COOLANT -> MineExpeditionStage.ARK_CHAMBER
            MineExpeditionStage.ARK_CHAMBER -> MineExpeditionStage.ARK_CORES
            MineExpeditionStage.ARK_CORES -> MineExpeditionStage.ARK_HOME
            MineExpeditionStage.ARK_HOME -> MineExpeditionStage.COMPLETE
            MineExpeditionStage.FACTORY_WATER -> MineExpeditionStage.FACTORY_COAL
            MineExpeditionStage.FACTORY_COAL -> MineExpeditionStage.FACTORY_HEAT
            MineExpeditionStage.FACTORY_HEAT -> MineExpeditionStage.FACTORY_POUR
            MineExpeditionStage.FACTORY_POUR -> MineExpeditionStage.FACTORY_CRANE
            MineExpeditionStage.FACTORY_CRANE -> MineExpeditionStage.FACTORY_INSTALL
            MineExpeditionStage.FACTORY_INSTALL -> MineExpeditionStage.COMPLETE
            MineExpeditionStage.COMPLETE -> return MineExpeditionStep(current, false)
        }
        return MineExpeditionStep(
            state = current.copy(
                stage = next,
                completed = emptySet(),
                motionStep = 0,
                heatStartedAt = if (next == MineExpeditionStage.FACTORY_HEAT) now else 0L,
            ),
            accepted = true,
            finished = next == MineExpeditionStage.COMPLETE,
        )
    }
}

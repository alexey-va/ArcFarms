package ru.ruscrafting.farms.domain.mine.expedition

import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed

/** Optional randomized side jobs for the connected dead-factory line. */
enum class MineFactoryExperiment {
    ROCK_JAM,
    MOULD,
    MANUAL_CRANE,
    DRIVE_REPAIR,
    ROUTING,
    COOLING,
}

/** Durable experiment selection and completion state. */
data class MineFactoryExperimentPlan(
    val selected: Set<MineFactoryExperiment> = emptySet(),
    val resolved: Set<MineFactoryExperiment> = emptySet(),
    val product: Int = 0,
) {
    init { validate() }

    fun validate() {
        require(resolved.all { it in selected }) {
            "Resolved factory experiments must be selected"
        }
        require(product in 0..2) { "Factory experiment product is invalid" }
    }
}

/** Deterministic selection and stage-gated side-job transitions. */
object MineFactoryExperiments {
    private const val COUNT_SALT = 0x4D46455850434E54L
    private const val PRODUCT_SALT = 0x4D46455850524F44L
    private const val ORDER_SALT = 0x4D4645584F524445L

    /**
     * Current side jobs.  The enum deliberately retains the retired values so
     * Gson can still read journals written by older plugin versions, but they
     * must not be selected or gate a newly loaded run.
     */
    val supported: Set<MineFactoryExperiment> = linkedSetOf(
        MineFactoryExperiment.ROCK_JAM,
        MineFactoryExperiment.MANUAL_CRANE,
        MineFactoryExperiment.COOLING,
    )

    /** Select zero to two distinct experiments, or use an explicit test/admin selection. */
    fun select(seed: Long, forced: Set<MineFactoryExperiment>? = null): MineFactoryExperimentPlan {
        val selected = forced?.filterTo(linkedSetOf()) { isSupported(it) } ?: run {
            val count = Math.floorMod(WorksiteDeterministicSeed.orderScore(seed, COUNT_SALT), 3L).toInt()
            supported
                .sortedWith(compareBy<MineFactoryExperiment> {
                    WorksiteDeterministicSeed.orderScore(seed, ORDER_SALT + it.ordinal)
                }.thenBy { it.ordinal })
                .take(count)
                .toCollection(linkedSetOf())
        }
        val product = Math.floorMod(WorksiteDeterministicSeed.orderScore(seed, PRODUCT_SALT), 3L).toInt()
        return MineFactoryExperimentPlan(selected = selected, product = product)
    }

    /** True only for side jobs belonging to the current policy. */
    fun isSupported(experiment: MineFactoryExperiment): Boolean = experiment in supported

    /**
     * Removes retired side jobs from an already persisted plan without
     * changing the product or any ordinary expedition checkpoint.
     */
    fun normalize(plan: MineFactoryExperimentPlan): MineFactoryExperimentPlan {
        val selected = plan.selected.filterTo(linkedSetOf()) { isSupported(it) }
        val resolved = plan.resolved.filterTo(linkedSetOf()) { isSupported(it) }.intersect(selected)
        return if (selected == plan.selected && resolved == plan.resolved) plan
        else plan.copy(selected = selected, resolved = resolved)
    }

    /**
     * Normalizes a connected dead-factory state in place. A missing plan is
     * treated as an empty current-policy plan; an existing plan is filtered
     * instead of being reselected, so a reload never introduces new work or
     * changes completed progress.
     */
    fun normalizeConnected(state: MineExpeditionState): MineExpeditionState {
        val plan = state.factoryExperiments?.let(::normalize) ?: MineFactoryExperimentPlan()
        return if (plan == state.factoryExperiments) state
        else state.copy(factoryExperiments = plan)
    }

    /** Side jobs currently actionable at the persisted stage/checkpoint. */
    fun pending(state: MineExpeditionState): Set<MineFactoryExperiment> {
        val plan = state.factoryExperiments ?: return emptySet()
        return plan.selected.asSequence()
            .filter { isSupported(it) }
            .filter { it !in plan.resolved }
            .filter { eligible(state, it) }
            .toCollection(linkedSetOf())
    }

    /** A completed physical transfer also owns its ordinary destination checkpoint. */
    fun resolve(
        current: MineExpeditionState,
        experiment: MineFactoryExperiment,
        now: Long,
    ): MineExpeditionStep {
        require(now >= 0L)
        val plan = current.factoryExperiments ?: return MineExpeditionStep(current, false)
        if (experiment !in pending(current)) return MineExpeditionStep(current, false)

        val marked = current.copy(
            factoryExperiments = plan.copy(resolved = plan.resolved + experiment),
        )
        val destination = when (experiment) {
            MineFactoryExperiment.MANUAL_CRANE -> 0
            MineFactoryExperiment.ROUTING -> 2
            else -> null
        }
        if (destination == null) {
            return MineExpeditionStep(marked, true)
        }

        val landed = MineExpeditionEngine.completeTarget(marked, destination, now)
        if (!landed.accepted) return MineExpeditionStep(current, false)
        return landed.copy(
            state = landed.state.copy(
                factoryExperiments = landed.state.factoryExperiments
                    ?.copy(resolved = landed.state.factoryExperiments.resolved + experiment),
            ),
        )
    }

    /** Guards for the ordinary numbered target API. */
    internal fun blocksTarget(state: MineExpeditionState, target: Int): Boolean {
        if (state.factoryExperiments == null) return false
        if (state.stage == MineExpeditionStage.FACTORY_WATER ||
            state.stage == MineExpeditionStage.FACTORY_COAL
        ) {
            val next = if (skipsDriveRepair(state) &&
                state.stage == MineExpeditionStage.FACTORY_WATER &&
                1 !in state.completed
            ) {
                1
            } else {
                (0 until 3).firstOrNull { it !in state.completed }
            }
            if (next != null && target != next) return true
        }
        return when {
            MineFactoryExperiment.ROCK_JAM in pending(state) &&
                state.stage == MineExpeditionStage.FACTORY_COAL && target == 1 -> true
            MineFactoryExperiment.ROUTING in pending(state) &&
                state.stage == MineExpeditionStage.FACTORY_COAL && target == 2 -> true
            MineFactoryExperiment.COOLING in pending(state) &&
                state.stage == MineExpeditionStage.FACTORY_HEAT && target == 0 -> true
            MineFactoryExperiment.MANUAL_CRANE in pending(state) &&
                state.stage == MineExpeditionStage.FACTORY_CRANE && target == 0 -> true
            MineFactoryExperiment.MOULD in pending(state) &&
                state.stage == MineExpeditionStage.FACTORY_INSTALL && target == 0 -> true
            else -> false
        }
    }

    /** Every connected persisted plan uses the current policy; the old gear job is retired. */
    internal fun skipsDriveRepair(state: MineExpeditionState): Boolean =
        state.factoryExperiments != null

    /** Water valve 1 replaces the omitted gear loop while preserving both credits. */
    internal fun completedTargets(
        state: MineExpeditionState,
        target: Int,
    ): Set<Int> = if (skipsDriveRepair(state) &&
        state.stage == MineExpeditionStage.FACTORY_WATER && target == 1
    ) {
        state.completed + 0 + 1
    } else {
        state.completed + target
    }

    /** The existing target-0 gear loop resolves DRIVE_REPAIR without a second objective. */
    internal fun recordGearResolution(
        state: MineExpeditionState,
        target: Int,
    ): MineExpeditionState {
        val plan = state.factoryExperiments ?: return state
        if (state.stage != MineExpeditionStage.FACTORY_WATER || target != 0 ||
            MineFactoryExperiment.DRIVE_REPAIR !in plan.selected
        ) return state
        return state.copy(factoryExperiments = plan.copy(resolved = plan.resolved + MineFactoryExperiment.DRIVE_REPAIR))
    }

    private fun eligible(state: MineExpeditionState, experiment: MineFactoryExperiment): Boolean = when (experiment) {
        MineFactoryExperiment.ROCK_JAM -> state.stage == MineExpeditionStage.FACTORY_COAL &&
            0 in state.completed && 1 !in state.completed
        MineFactoryExperiment.ROUTING -> state.stage == MineExpeditionStage.FACTORY_COAL &&
            1 in state.completed && 2 !in state.completed
        MineFactoryExperiment.COOLING -> state.stage == MineExpeditionStage.FACTORY_HEAT
        MineFactoryExperiment.MOULD -> state.stage == MineExpeditionStage.FACTORY_INSTALL
        MineFactoryExperiment.MANUAL_CRANE -> state.stage == MineExpeditionStage.FACTORY_CRANE
        // The regular water-stage gear target owns this experiment's durable bit.
        MineFactoryExperiment.DRIVE_REPAIR -> false
    }
}

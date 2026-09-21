package ru.ruscrafting.farms.domain.mine.expedition

/** Different commissioning jobs on the same permanent factory. Checkpoint/reward budget is unchanged. */
object MineFactoryProgram {
    val machines = mapOf(
        "decor_crusher_left" to ExpeditionPoint(-17,5,11),
        "decor_crusher_right" to ExpeditionPoint(17,5,11),
        "decor_pump_left" to ExpeditionPoint(-27,5,-17),
        "decor_pump_right" to ExpeditionPoint(27,5,-17),
    )
    /** Separate controls stay beside the service aisle, outside the machine envelope. */
    val controls = machines.mapKeys { (id,_) -> id.replace("decor_", "control_") }
        .mapValues { (id,at) -> at.offset(if(id.endsWith("left")) 6 else -6,0,if(id.contains("crusher")) 5 else 0) }
    fun machine(id:String):String = id.replace("control_", "decor_")

    /**
     * Geometry 3 factories may expose the continuous crusher line.  Older
     * journals keep the original three commissioning variants and do not have
     * this station, so the station itself is the compatibility discriminator.
     */
    fun usesConnectedCrusherLine(plan: MineExpeditionPlan): Boolean = "crusher_feed" in plan.stations

    /** The third charge checkpoint is consumed by the belt at the furnace inlet. */
    fun chargeTransferPending(state: MineExpeditionState): Boolean =
        state.stage == MineExpeditionStage.FACTORY_COAL &&
            0 in state.completed && 1 in state.completed && 2 !in state.completed

    /** A connected crane has placed the billet; the conveyor now owns the press stroke. */
    fun pressTransferPending(plan: MineExpeditionPlan, state: MineExpeditionState): Boolean =
        usesConnectedCrusherLine(plan) && state.stage == MineExpeditionStage.FACTORY_INSTALL

    private fun commissioningControls(state: MineExpeditionState): List<String> = when(state.factoryProgram) {
        1 -> listOf("water_valve_0","control_pump_left","control_crusher_left")
        2 -> listOf("water_valve_2","control_pump_right","control_crusher_right")
        else -> listOf("water_valve_0","water_valve_1","water_valve_2")
    }

    /** Completed commissioning survives disappearing objectives and stage changes, including restart. */
    fun runningMachines(
        state: MineExpeditionState,
        startingControls: Set<String>,
        plan: MineExpeditionPlan? = null,
    ): Set<String> {
        if (plan != null && usesConnectedCrusherLine(plan)) {
            return connectedRunningMachines(state, startingControls)
        }
        return when(state.stage) {
        MineExpeditionStage.FACTORY_WATER -> commissioningControls(state).mapIndexedNotNull { index, id ->
            if (id in controls && (index in state.completed || id in startingControls)) machine(id) else null
        }.toSet()
        MineExpeditionStage.FACTORY_COAL, MineExpeditionStage.FACTORY_HEAT,
        MineExpeditionStage.FACTORY_POUR, MineExpeditionStage.FACTORY_CRANE,
        MineExpeditionStage.FACTORY_INSTALL -> machines.keys
        else -> emptySet()
        }
    }

    fun targets(plan: MineExpeditionPlan,state: MineExpeditionState): List<MineExpeditionObjective> {
        if (usesConnectedCrusherLine(plan)) return connectedTargets(plan, state)
        return commissioningControls(state).mapIndexedNotNull { index,id ->
            if(index in state.completed) null else MineExpeditionObjective(id,plan.stations[id] ?: controls.getValue(id),
                if(id.startsWith("control_")) MineExpeditionInteraction.OPERATE else MineExpeditionInteraction.VALVE,
                "GRINDSTONE",index)
        }
    }

    private fun connectedRunningMachines(
        state: MineExpeditionState,
        startingControls: Set<String>,
    ): Set<String> {
        return when (state.stage) {
            MineExpeditionStage.FACTORY_WATER -> buildSet {
                if (1 in state.completed || "water_valve_1" in startingControls) add("decor_pump_left")
                if (2 in state.completed || "control_crusher_left" in startingControls) add("decor_crusher_left")
            }
            MineExpeditionStage.FACTORY_COAL,
            MineExpeditionStage.FACTORY_HEAT,
            MineExpeditionStage.FACTORY_POUR,
            MineExpeditionStage.FACTORY_CRANE,
            MineExpeditionStage.FACTORY_INSTALL,
                -> setOf("decor_pump_left", "decor_crusher_left")
            else -> emptySet()
        }
    }

    private fun connectedTargets(
        plan: MineExpeditionPlan,
        state: MineExpeditionState,
    ): List<MineExpeditionObjective> {
        val next = if (MineFactoryExperiments.skipsDriveRepair(state) && 1 !in state.completed) {
            // The valve owns both the omitted gear checkpoint and its own credit.
            1
        } else {
            (0 until 3).firstOrNull { it !in state.completed }
        } ?: return emptyList()
        fun station(id: String): ExpeditionPoint = MineFactoryLine.effectiveStation(plan, id)
        return when (next) {
            0 -> listOf(
                MineExpeditionObjective(
                    "repair_supply_${state.factoryProgram}", station("repair_supply_${state.factoryProgram}"),
                    MineExpeditionInteraction.PICKUP, "IRON_NUGGET", 0,
                ),
                MineExpeditionObjective(
                    "crusher_repair", station("crusher_repair"),
                    MineExpeditionInteraction.DELIVER, "IRON_NUGGET", 0,
                ),
            )
            1 -> listOf(
                MineExpeditionObjective(
                    "water_valve_1", station("water_valve_1"),
                    MineExpeditionInteraction.VALVE, "GRINDSTONE", 1,
                ),
            )
            2 -> listOf(
                MineExpeditionObjective(
                    "control_crusher_left", station("control_crusher_left"),
                    MineExpeditionInteraction.OPERATE, "GRINDSTONE", 2,
                ),
            )
            else -> emptyList()
        }
    }
}

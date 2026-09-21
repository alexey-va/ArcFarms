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
    fun targets(plan: MineExpeditionPlan,state: MineExpeditionState): List<MineExpeditionObjective> {
        val ids=when(state.factoryProgram) {
            1 -> listOf("water_valve_0","control_pump_left","control_crusher_left")
            2 -> listOf("water_valve_2","control_pump_right","control_crusher_right")
            else -> listOf("water_valve_0","water_valve_1","water_valve_2")
        }
        return ids.mapIndexedNotNull { index,id ->
            if(index in state.completed) null else MineExpeditionObjective(id,plan.stations[id] ?: controls.getValue(id),
                if(id.startsWith("control_")) MineExpeditionInteraction.OPERATE else MineExpeditionInteraction.VALVE,
                "GRINDSTONE",index)
        }
    }
}

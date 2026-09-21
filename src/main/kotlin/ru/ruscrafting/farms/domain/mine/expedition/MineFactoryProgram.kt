package ru.ruscrafting.farms.domain.mine.expedition

/** Different commissioning jobs on the same permanent factory. Checkpoint/reward budget is unchanged. */
object MineFactoryProgram {
    val machines = mapOf(
        "decor_crusher_left" to ExpeditionPoint(-17,5,11),
        "decor_crusher_right" to ExpeditionPoint(17,5,11),
        "decor_pump_left" to ExpeditionPoint(-27,5,-17),
        "decor_pump_right" to ExpeditionPoint(27,5,-17),
    )
    fun targets(plan: MineExpeditionPlan,state: MineExpeditionState): List<MineExpeditionObjective> {
        val ids=when(state.factoryProgram) {
            1 -> listOf("water_valve_0","decor_pump_left","decor_crusher_left")
            2 -> listOf("water_valve_2","decor_pump_right","decor_crusher_right")
            else -> listOf("water_valve_0","water_valve_1","water_valve_2")
        }
        return ids.mapIndexedNotNull { index,id ->
            if(index in state.completed) null else MineExpeditionObjective(id,plan.stations[id] ?: machines.getValue(id),
                if(id.startsWith("decor_")) MineExpeditionInteraction.OPERATE else MineExpeditionInteraction.VALVE,
                "GRINDSTONE",index)
        }
    }
}

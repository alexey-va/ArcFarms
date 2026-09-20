package ru.ruscrafting.farms.domain.mine.expedition

enum class MineExpeditionInteraction { CRANK, PICKUP, DELIVER, OPERATE, BREAK, BRANCH, MOTION }

data class MineExpeditionObjective(
    val id: String,
    val position: ExpeditionPoint,
    val interaction: MineExpeditionInteraction,
    val material: String,
    val target: Int = -1,
)

/** Physical affordances for each stage, shared by input handling, glow and guidance. */
object MineExpeditionObjectives {
    fun targets(plan: MineExpeditionPlan, state: MineExpeditionState, machine: ExpeditionPoint?): List<MineExpeditionObjective> {
        fun point(name: String) = plan.stations.getValue(name)
        fun objective(id: String, mode: MineExpeditionInteraction, material: String, target: Int = -1,
            position: ExpeditionPoint = point(id)) = MineExpeditionObjective(id, position, mode, material, target)
        fun many(prefix: String, count: Int, mode: MineExpeditionInteraction, material: String) =
            (0 until count).filter { it !in state.completed }.map { index -> objective("${prefix}_$index", mode, material, index) }
        fun carry(source: String, destination: String, material: String, delivery: ExpeditionPoint = point(destination)) = listOf(
            objective(source, MineExpeditionInteraction.PICKUP, material),
            objective(destination, MineExpeditionInteraction.DELIVER, material, position = delivery),
        )
        val deck = machine ?: plan.stations["ark_start"] ?: plan.spawn
        return when (state.stage) {
            MineExpeditionStage.DESCENT_MIDDLE, MineExpeditionStage.DESCENT_BOTTOM,
            MineExpeditionStage.ARK_FORK, MineExpeditionStage.ARK_CHAMBER, MineExpeditionStage.ARK_HOME -> listOf(
                objective("drive", MineExpeditionInteraction.MOTION, "LEVER", position = deck.offset(2, 0, -1)))
            MineExpeditionStage.DESCENT_COUNTERWEIGHTS -> many("counterweight", 3, MineExpeditionInteraction.CRANK, "GRINDSTONE")
            MineExpeditionStage.DESCENT_POWER_CELLS -> carry("power_supply", "power_socket", "COPPER_BLOCK")
            MineExpeditionStage.DESCENT_CORE_VALVES -> many("core_valve", 3, MineExpeditionInteraction.CRANK, "HEAVY_CORE")
            MineExpeditionStage.DESCENT_ENGINE -> listOf(objective("core_start", MineExpeditionInteraction.OPERATE, "LIGHTNING_ROD", 0))
            MineExpeditionStage.ARK_FUEL -> carry("fuel_supply", "boiler", "COAL_BLOCK", deck.offset(-2, 0, -2))
            MineExpeditionStage.ARK_BRANCH -> listOf(
                objective("branch_left", MineExpeditionInteraction.BRANCH, "RAIL", 1, deck.offset(-2, 0, 2)),
                objective("branch_right", MineExpeditionInteraction.BRANCH, "RAIL", 2, deck.offset(2, 0, 2)),
            )
            MineExpeditionStage.ARK_JAM -> (0..2).filter { it !in state.completed }.map { index ->
                objective("jam_$index", MineExpeditionInteraction.BREAK, "TUFF", index, deck.offset(index - 1, 1, 8)) }
            MineExpeditionStage.ARK_COOLANT -> carry("coolant_supply", "cooling", "WATER_BUCKET", deck.offset(2, 0, -2))
            MineExpeditionStage.ARK_CORES -> many("survey", 3, MineExpeditionInteraction.PICKUP, "AMETHYST_CLUSTER") +
                objective("core_rack", MineExpeditionInteraction.DELIVER, "AMETHYST_SHARD", position = deck.offset(-2, 0, -4))
            MineExpeditionStage.FACTORY_WATER -> many("water_valve", 3, MineExpeditionInteraction.CRANK, "GRINDSTONE")
            MineExpeditionStage.FACTORY_COAL -> carry("fuel_supply", "furnace_input", "COAL_BLOCK")
            MineExpeditionStage.FACTORY_HEAT -> listOf(objective("furnace_control", MineExpeditionInteraction.OPERATE, "BLAZE_POWDER", 0))
            MineExpeditionStage.FACTORY_POUR -> listOf(objective("pour_control", MineExpeditionInteraction.CRANK, "LAVA_BUCKET", 0))
            MineExpeditionStage.FACTORY_CRANE -> listOf(objective("crane_control", MineExpeditionInteraction.CRANK, "IRON_CHAIN", 0))
            MineExpeditionStage.FACTORY_INSTALL -> carry("crane_control", "assembly_socket", "HEAVY_CORE")
            MineExpeditionStage.COMPLETE -> emptyList()
        }
    }
}

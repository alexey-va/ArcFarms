package ru.ruscrafting.farms.domain

import kotlin.math.abs

enum class MineScenarioAction { BREAK, INTERACT, ORDERED_INTERACT, CARRY, ESCORT, DEFEND, STEER, HERD, SUSTAIN }
enum class MineScenarioScope { FLOOR, MULTI_FLOOR, MINE_WIDE }

data class MineScenarioFloor(val id: String, val y: Double) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{1,31}"))) { "Invalid mine scenario floor id: $id" }
        require(y.isFinite()) { "Mine scenario floor Y must be finite: $id" }
    }
}

data class MineScenarioStageView(
    val index: Int,
    val definition: MineScenarioDefinition,
    val progressWithinStage: Int,
)

data class MineScenarioStage(
    val id: String,
    val action: MineScenarioAction,
    val required: Int,
    val targetCount: Int,
    val material: String,
    val durationSeconds: Int = 0,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{1,31}"))) { "Invalid mine scenario stage id: $id" }
        require(required in 1..8) { "Mine scenario stage quota is out of bounds: $id" }
        require(targetCount in required..8) { "Mine scenario stage target count is invalid: $id" }
        require(material.matches(Regex("[A-Z][A-Z0-9_]{1,31}"))) { "Invalid mine scenario material: $material" }
        require(durationSeconds == 0 || durationSeconds in 10..20) { "Mine scenario stage duration is out of bounds: $id" }
    }
}

data class MineScenarioDefinition(
    val id: String,
    val stages: List<MineScenarioStage>,
    val palette: String,
    val scope: MineScenarioScope = MineScenarioScope.FLOOR,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{1,31}"))) { "Invalid mine scenario id: $id" }
        require(stages.size in 2..3) { "Mine scenario must have two or three stages: $id" }
        require(stages.map { it.id }.distinct().size == stages.size) { "Mine scenario stages must be distinct: $id" }
        require(palette.matches(Regex("[a-z][a-z0-9_-]{1,31}"))) { "Invalid mine scenario palette: $id" }
    }

    val totalRequired: Int get() = stages.sumOf(MineScenarioStage::required)

    fun stageAt(totalProgress: Int): MineScenarioStageView? {
        require(totalProgress >= 0) { "Mine scenario progress cannot be negative" }
        if (totalProgress >= totalRequired) return null
        var progress = totalProgress
        stages.forEachIndexed { index, stage ->
            if (progress < stage.required) return MineScenarioStageView(index, this, progress)
            progress -= stage.required
        }
        return null
    }
}

object MineScenarioCatalog {
    private val all = listOf(
        MineScenarioDefinition("creature_nest", listOf(
            stage("defend", MineScenarioAction.DEFEND, 3, 3, "IRON_BARS", 15),
            stage("seal", MineScenarioAction.BREAK, 1, 1, "SCULK"),
        ), "invasion", MineScenarioScope.MINE_WIDE),
        MineScenarioDefinition("cave_in", listOf(
            stage("clear", MineScenarioAction.BREAK, 2, 4, "COBBLESTONE"),
            stage("supports", MineScenarioAction.INTERACT, 1, 2, "OAK_PLANKS"),
        ), "cavein", MineScenarioScope.FLOOR),
        MineScenarioDefinition("flooding", listOf(
            stage("pumps", MineScenarioAction.INTERACT, 2, 4, "CAULDRON"),
            stage("drain", MineScenarioAction.SUSTAIN, 1, 1, "SPONGE", 20),
        ), "flood", MineScenarioScope.FLOOR),
        MineScenarioDefinition("gas_leak", listOf(
            stage("vents", MineScenarioAction.ORDERED_INTERACT, 2, 4, "IRON_BARS"),
            stage("ventilation", MineScenarioAction.SUSTAIN, 1, 1, "IRON_BLOCK", 15),
        ), "gas", MineScenarioScope.FLOOR),
        MineScenarioDefinition("power_failure", listOf(
            stage("fuse", MineScenarioAction.BREAK, 1, 2, "REDSTONE_LAMP"),
            stage("circuits", MineScenarioAction.ORDERED_INTERACT, 2, 4, "COPPER_BLOCK"),
        ), "power", MineScenarioScope.FLOOR),
        MineScenarioDefinition("injured_miner", listOf(
            stage("jacks", MineScenarioAction.INTERACT, 1, 2, "IRON_BLOCK"),
            stage("stretcher", MineScenarioAction.CARRY, 1, 1, "OAK_PLANKS"),
        ), "rescue", MineScenarioScope.MULTI_FLOOR),
        MineScenarioDefinition("runaway_cart", listOf(
            stage("switches", MineScenarioAction.STEER, 2, 4, "RAIL"),
            stage("brake", MineScenarioAction.INTERACT, 1, 1, "LEVER"),
            stage("escort", MineScenarioAction.ESCORT, 1, 1, "IRON_BLOCK"),
        ), "cart", MineScenarioScope.MULTI_FLOOR),
        MineScenarioDefinition("convoy", listOf(
            stage("track", MineScenarioAction.BREAK, 2, 4, "RAIL"),
            stage("escort", MineScenarioAction.ESCORT, 1, 1, "IRON_BLOCK", 15),
        ), "convoy", MineScenarioScope.MULTI_FLOOR),
        MineScenarioDefinition("lift_breakdown", listOf(
            stage("exit", MineScenarioAction.INTERACT, 1, 2, "LEVER"),
            stage("parts", MineScenarioAction.CARRY, 1, 1, "IRON_BLOCK"),
            stage("test", MineScenarioAction.ORDERED_INTERACT, 1, 2, "REDSTONE_BLOCK"),
        ), "lift", MineScenarioScope.MINE_WIDE),
        MineScenarioDefinition("bat_swarm", listOf(
            stage("flight", MineScenarioAction.HERD, 2, 4, "TORCH", 10),
            stage("seal", MineScenarioAction.INTERACT, 1, 1, "COBBLESTONE"),
        ), "bats", MineScenarioScope.FLOOR),
        MineScenarioDefinition("fungal_bloom", listOf(
            stage("samples", MineScenarioAction.BREAK, 2, 4, "RED_MUSHROOM_BLOCK"),
            stage("spores", MineScenarioAction.BREAK, 1, 2, "BROWN_MUSHROOM_BLOCK"),
        ), "fungal", MineScenarioScope.FLOOR),
        MineScenarioDefinition("root_invasion", listOf(
            stage("roots", MineScenarioAction.BREAK, 2, 4, "MANGROVE_ROOTS"),
            stage("heart", MineScenarioAction.BREAK, 1, 1, "MOSS_BLOCK"),
        ), "roots", MineScenarioScope.FLOOR),
        MineScenarioDefinition("lava_breach", listOf(
            stage("barriers", MineScenarioAction.CARRY, 2, 4, "STONE"),
            stage("sluices", MineScenarioAction.ORDERED_INTERACT, 2, 4, "IRON_BARS"),
        ), "lava", MineScenarioScope.FLOOR),
        MineScenarioDefinition("ancient_door", listOf(
            stage("gears", MineScenarioAction.CARRY, 1, 2, "GOLD_BLOCK"),
            stage("lock", MineScenarioAction.ORDERED_INTERACT, 1, 2, "STONE_BRICKS"),
        ), "ancient", MineScenarioScope.FLOOR),
        MineScenarioDefinition("old_warehouse", listOf(
            stage("crates", MineScenarioAction.CARRY, 2, 4, "OAK_PLANKS"),
            stage("winch", MineScenarioAction.INTERACT, 1, 1, "LEVER"),
        ), "warehouse", MineScenarioScope.FLOOR),
        MineScenarioDefinition("drill_trial", listOf(
            stage("coolant", MineScenarioAction.CARRY, 1, 2, "IRON_BLOCK"),
            stage("controls", MineScenarioAction.SUSTAIN, 2, 2, "REDSTONE_BLOCK", 20),
            stage("breakthrough", MineScenarioAction.BREAK, 1, 1, "DEEPSLATE"),
        ), "drill", MineScenarioScope.FLOOR),
    )

    val definitions: Map<String, MineScenarioDefinition> = all.associateBy(MineScenarioDefinition::id)

    fun definition(id: String): MineScenarioDefinition? = definitions[id]

    fun resolveFloor(floors: List<MineScenarioFloor>, y: Double): MineScenarioFloor? {
        require(y.isFinite()) { "Mine scenario lookup Y must be finite" }
        return floors.minWithOrNull(compareBy<MineScenarioFloor>({ abs(it.y - y) }, { it.id }))
    }

    fun requiresDifferentFloorDestination(id: String): Boolean = definition(id)?.scope == MineScenarioScope.MULTI_FLOOR

    fun destinationAllowed(id: String, sourceFloorId: String, destinationFloorId: String): Boolean {
        if (definition(id) == null) return false
        return !requiresDifferentFloorDestination(id) || sourceFloorId != destinationFloorId
    }

    private fun stage(
        id: String,
        action: MineScenarioAction,
        required: Int,
        targetCount: Int,
        material: String,
        durationSeconds: Int = 0,
    ) = MineScenarioStage(id, action, required, targetCount, material, durationSeconds)
}

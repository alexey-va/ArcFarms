package ru.ruscrafting.farms.domain.mine.expedition

/** Durable anchor for a generated scene. Coordinates are in the named world. */
data class MineExpeditionPlacement(
    val world: String,
    val originX: Int,
    val originY: Int,
    val originZ: Int,
    val seed: Long,
    val geometryVersion: Int = CURRENT_GEOMETRY_VERSION,
) {
    init { validate() }

    fun validate() {
        require(world.matches(WORLD_ID)) {
            "Expedition world is invalid"
        }
        require(originX in WORLD_MIN..WORLD_MAX && originZ in WORLD_MIN..WORLD_MAX) {
            "Expedition origin is outside world limits"
        }
        require(originY in -2_048..2_048) { "Expedition origin height is invalid" }
        require(geometryVersion in 1..CURRENT_GEOMETRY_VERSION) { "Unsupported expedition geometry version" }
    }

    companion object {
        const val CURRENT_GEOMETRY_VERSION = 1
        const val WORLD_MIN = -29_999_984
        const val WORLD_MAX = 29_999_984
        private val WORLD_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

/** The persisted stage is the single source of truth for physical scene actions. */
enum class MineExpeditionStage {
    DESCENT_MIDDLE,
    DESCENT_COUNTERWEIGHTS,
    DESCENT_POWER_CELLS,
    DESCENT_BOTTOM,
    DESCENT_CORE_VALVES,
    DESCENT_ENGINE,

    ARK_FUEL,
    ARK_FORK,
    ARK_BRANCH,
    ARK_JAM,
    ARK_COOLANT,
    ARK_CHAMBER,
    ARK_CORES,
    ARK_HOME,

    FACTORY_WATER,
    FACTORY_COAL,
    FACTORY_HEAT,
    FACTORY_POUR,
    FACTORY_CRANE,
    FACTORY_INSTALL,

    COMPLETE,
}

enum class MineExpeditionAction {
    /** Carry, turn, clear, extract, pour, operate, or install one numbered target. */
    TARGET,
    /** Advance the bounded route; the owner supplies the route length. */
    MOTION,
    /** Persist one of the two drilling crawler branches. */
    BRANCH,
    /** Finish only while the timed heat window is open. */
    HEAT,
    COMPLETE,
}

data class MineExpeditionState(
    val placement: MineExpeditionPlacement,
    val stage: MineExpeditionStage,
    /** Numbered physical targets already completed in the current stage. */
    val completed: Set<Int> = emptySet(),
    /** Route step reached in the current motion stage. */
    val motionStep: Int = 0,
    /** 0 means unset, 1 means left, and 2 means right. */
    val branch: Int = 0,
    /** Start of the current factory heat attempt. */
    val heatStartedAt: Long = 0L,
) {
    init { validate() }

    fun validate() {
        placement.validate()
        require(completed.size <= 256 && completed.all { it in 0..255 }) {
            "Expedition target state is invalid"
        }
        require(motionStep in 0..1_000_000) { "Expedition motion step is invalid" }
        require(branch in 0..2) { "Expedition branch is invalid" }
        require(heatStartedAt >= 0L) { "Expedition heat timestamp is invalid" }
        require(stage == MineExpeditionStage.FACTORY_HEAT || heatStartedAt == 0L) {
            "Heat timestamp belongs to a non-heat expedition stage"
        }
        require(stage != MineExpeditionStage.ARK_BRANCH || branch == 0) {
            "An unchosen drilling branch cannot carry a branch value"
        }
    }
}

data class MineExpeditionStep(
    val state: MineExpeditionState,
    val accepted: Boolean,
    val finished: Boolean = false,
)

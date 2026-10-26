package ru.ruscrafting.farms.domain.lumber

import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.util.UUID

object LumberTiming {
    const val FALL_TICKS = 60
    const val CART_LOAD_TICKS = 60
    const val SAWMILL_LOAD_TICKS = 120
    const val BOARD_PICKUP_TICKS = 40
    const val SAW_PASS_TICKS = 240
}

enum class LumberPhase {
    IDLE, CHOPPING, FALLING, DELIMBING, LOADING_CART, HAULING,
    LOADING_SAWMILL, SAWING, COLLECTING_BOARDS, DELIVERING,
}

data class LumberBlockSnapshot(val position: WorksitePosition, val blockData: String)

/** Existing terrain is captured for presentation and validation; it is never written back. */
data class LumberTreeSnapshot(
    val root: WorksitePosition,
    val logs: List<LumberBlockSnapshot>,
    val leaves: List<LumberBlockSnapshot>,
    val fallX: Int = 1,
    val fallZ: Int = 0,
    val cartHome: LumberPose? = null,
) {
    val blocks: List<LumberBlockSnapshot> get() = logs + leaves
    val maskedBlocks: List<LumberBlockSnapshot>
        get() = logs.filter { it.position.y >= root.y + 2 } + leaves
}

data class LumberPose(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float = 0f)

/** Legacy cart stores the log anchor, then the board cart; cartCarrier remains the log's leash holder. */
data class LumberState(
    val roundId: Long = 0L,
    val phase: LumberPhase = LumberPhase.IDLE,
    val phaseStartedAt: Long = 0L,
    val tree: LumberTreeSnapshot? = null,
    val chopHits: Int = 0,
    val branches: WorksiteObjectiveState? = null,
    val cart: LumberPose? = null,
    val cartCarrier: UUID? = null,
    val sawPasses: Int = 0,
    val sawRunning: Boolean = false,
    val boardsCarrier: UUID? = null,
    val boardCartLoaded: Boolean = false,
    val progressVersion: Long = 0L,
    val completedRounds: Long = 0L,
    val contributors: Map<UUID, Int> = emptyMap(),
)

enum class LumberAction {
    CHOP_TREE, CUT_BRANCH, LOAD_CART, TAKE_CART, LOAD_SAWMILL, SAW_LEVER, COLLECT_BOARDS, DELIVER_BOARDS,
}

data class LumberInput(val action: LumberAction, val index: Int = 0)

/** Renderer receives an immutable frame; all phase and saw clocks remain backend-owned. */
data class LumberVisualView(
    val state: LumberState,
    val phaseAgeTicks: Int,
    val viewers: Set<UUID>,
    val sawElapsedTicks: Int,
    val now: Long,
)

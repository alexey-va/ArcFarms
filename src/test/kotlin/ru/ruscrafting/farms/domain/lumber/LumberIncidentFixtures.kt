package ru.ruscrafting.farms.domain.lumber

import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.util.UUID

internal fun lumberIncidentBoundary(roundId: Long = 2, completedRounds: Long = 1, passes: Int = 1): LumberState {
    val root = WorksitePosition("rc_atelier_cedar_sawmill", 139, 73, 202)
    val tree = LumberTreeSnapshot(root,
        (0..9).map { LumberBlockSnapshot(root.copy(y = root.y + it), "minecraft:oak_log[axis=y]") },
        listOf(LumberBlockSnapshot(root.copy(x = 140, y = 82), "minecraft:oak_leaves[persistent=false]")))
    var branches = ObjectiveTargetPool.plan(WorksiteObjectiveKey(LumberEngine.ZONE_ID, "branches", roundId), 3,
        (0..2).map { ObjectiveTargetCandidate("branch_$it", root.copy(x = root.x + 2 + it), ObjectiveTargetRole("branch"), it.toLong()) })
    repeat(3) { branches = ObjectiveTargetPool.complete(branches, "branch_$it", UUID(0, 1)).state }
    return LumberState(roundId = roundId, phase = if (passes == 2) LumberPhase.COLLECTING_BOARDS else LumberPhase.SAWING,
        tree = tree, chopHits = 5, branches = branches, cart = if (passes == 2) null else LumberPose(root.world, 177.0, 73.0, 200.0),
        sawPasses = passes, completedRounds = completedRounds)
}

internal fun lumberSmolderPositions(world: String = "rc_atelier_cedar_sawmill") =
    listOf(170, 173, 175).map { WorksitePosition(world, 186, 73, it) }

package ru.ruscrafting.farms.domain.lumber

/** File-boundary validation is explicit because Gson does not execute Kotlin constructors. */
object LumberStateValidation {
    fun validate(state: LumberState) {
        require(state.roundId in 0 until Long.MAX_VALUE && state.progressVersion in 0 until Long.MAX_VALUE)
        require(state.completedRounds in 0 until Long.MAX_VALUE && state.phaseStartedAt >= 0)
        require(state.phase in LumberPhase.entries && state.chopHits in 0..100 && state.sawPasses in 0..2)
        require(state.contributors.size <= 10_000 && state.contributors.values.all { it in 0..100_000 })
        require(state.phase == LumberPhase.IDLE || state.tree != null) { "Active lumber round has no tree" }
        require(state.cartCarrier == null || state.phase == LumberPhase.HAULING)
        require(state.boardsCarrier == null || state.phase == LumberPhase.DELIVERING)
        require(!state.boardCartLoaded || state.phase in setOf(LumberPhase.COLLECTING_BOARDS, LumberPhase.DELIVERING) && state.cart != null)
        require(!state.sawRunning || state.phase == LumberPhase.SAWING && state.sawPasses < 2)
        state.tree?.let { tree ->
            ru.ruscrafting.farms.domain.worksite.WorksitePosition(tree.root.world, tree.root.x, tree.root.y, tree.root.z)
            require(tree.logs.size in 10..256 && tree.leaves.size in 1..768) { "Lumber tree snapshot exceeds bounds" }
            require(kotlin.math.abs(tree.fallX) + kotlin.math.abs(tree.fallZ) == 1)
            require(tree.blocks.map { it.position }.distinct().size == tree.blocks.size)
            val logs = tree.logs.map { it.position }.toSet()
            require((0..9).all { tree.root.copy(y = tree.root.y + it) in logs }) { "Lumber stock has no continuous usable trunk" }
            require(tree.logs.all { it.blockData.substringBefore('[').endsWith("_log") } &&
                tree.leaves.all { it.blockData.substringBefore('[').endsWith("_leaves") }) { "Lumber snapshot has foreign materials" }
            tree.blocks.forEach { block ->
                require(block.position.world == tree.root.world && block.blockData.length in 1..512)
                ru.ruscrafting.farms.domain.worksite.WorksitePosition(block.position.world, block.position.x, block.position.y, block.position.z)
                require(block.position.y in tree.root.y..tree.root.y + 44 &&
                    kotlin.math.abs(block.position.x - tree.root.x) <= 4 && kotlin.math.abs(block.position.z - tree.root.z) <= 4)
            }
            tree.cartHome?.let { home -> require(home.world == tree.root.world && listOf(home.x, home.y, home.z).all(Double::isFinite) && home.yaw.isFinite()) }
        }
        state.cart?.let { require(listOf(it.x, it.y, it.z).all(Double::isFinite) && it.yaw.isFinite()) }
        state.branches?.let { objective ->
            require(objective.required == 3 && objective.targets.size == 3 && objective.reserve.isEmpty())
            require(objective.key.sequence == state.roundId)
            require(objective.key.zoneId == LumberEngine.ZONE_ID && objective.key.objectiveId == "branches")
            require(objective.targets.map { it.id }.toSet() == setOf("branch_0", "branch_1", "branch_2"))
            require(objective.targets.all { it.status in setOf(ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus.AVAILABLE,
                ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus.COMPLETED) && it.role.value == "branch" && it.leasedBy == null && it.leaseExpiresAt == 0L })
            require(objective.targets.map { it.position }.distinct().size == 3)
        }
        if (state.phase == LumberPhase.DELIMBING) require(state.branches != null)
        if (state.phase in setOf(LumberPhase.LOADING_CART, LumberPhase.HAULING, LumberPhase.LOADING_SAWMILL,
            LumberPhase.SAWING, LumberPhase.COLLECTING_BOARDS, LumberPhase.DELIVERING)) require(state.branches?.completed == 3)
        if (state.phase in setOf(LumberPhase.COLLECTING_BOARDS, LumberPhase.DELIVERING)) require(state.sawPasses == 2)
    }
}

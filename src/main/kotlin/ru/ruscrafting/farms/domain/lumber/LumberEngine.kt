package ru.ruscrafting.farms.domain.lumber

import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.util.UUID

/** Closed beta records only local progress. No rewards, economy, network seals or global statistics. */
object LumberEngine {
    const val ZONE_ID = "cedar_sawmill"
    const val SAW_PASS_TICKS = LumberTiming.SAW_PASS_TICKS
    const val BRANCH_COUNT = 3

    fun start(previous: LumberState, tree: LumberTreeSnapshot, cart: LumberPose, now: Long): LumberState {
        if (previous.phase != LumberPhase.IDLE) return previous
        return LumberState(
            roundId = previous.roundId + 1, phase = LumberPhase.CHOPPING, phaseStartedAt = now,
            tree = tree, cart = cart, progressVersion = previous.progressVersion + 1,
            completedRounds = previous.completedRounds,
            incidentHandledRoundId = previous.incidentHandledRoundId, incidentGeneration = previous.incidentGeneration,
        )
    }

    fun act(current: LumberState, input: LumberInput, player: UUID, now: Long, chopRequired: Int): LumberState {
        if (current.incident != null) return current
        val next = when (input.action) {
            LumberAction.CHOP_TREE -> {
                if (current.phase != LumberPhase.CHOPPING) return current
                val hits = (current.chopHits + 1).coerceAtMost(chopRequired)
                current.copy(chopHits = hits, phase = if (hits == chopRequired) LumberPhase.FALLING else current.phase)
            }
            LumberAction.CUT_BRANCH -> {
                if (current.phase != LumberPhase.DELIMBING || input.index !in 0 until BRANCH_COUNT) return current
                val objective = current.branches ?: return current
                val result = ObjectiveTargetPool.complete(objective, "branch_${input.index}", player)
                if (!result.accepted) return current
                current.copy(branches = result.state,
                    phase = if (result.state.completed == BRANCH_COUNT) LumberPhase.LOADING_CART else current.phase)
            }
            LumberAction.LOAD_CART -> {
                if (current.phase != LumberPhase.LOADING_CART) return current
                current.copy(phase = LumberPhase.HAULING, cartCarrier = player)
            }
            LumberAction.TAKE_CART -> {
                if (current.phase != LumberPhase.HAULING || current.cartCarrier != null) return current
                current.copy(cartCarrier = player)
            }
            LumberAction.LOAD_SAWMILL -> {
                if (current.phase != LumberPhase.HAULING || current.cartCarrier != player) return current
                current.copy(phase = LumberPhase.LOADING_SAWMILL, cartCarrier = null)
            }
            LumberAction.SAW_LEVER -> {
                if (current.phase != LumberPhase.SAWING || current.sawRunning || current.sawPasses >= 2) return current
                current.copy(sawRunning = true)
            }
            // Retired client inputs cannot reintroduce a parallel manual delivery.
            LumberAction.COLLECT_BOARDS, LumberAction.DELIVER_BOARDS,
            LumberAction.START_CRANK, LumberAction.EXTINGUISH_SAWDUST -> return current
        }
        return next.copy(
            phaseStartedAt = if (next.phase != current.phase || input.action == LumberAction.SAW_LEVER) now else current.phaseStartedAt,
            progressVersion = current.progressVersion + 1,
            contributors = next.contributors + (player to ((next.contributors[player] ?: 0) + 1).coerceAtMost(100_000)),
        )
    }

    fun tick(current: LumberState, now: Long, fallTicks: Int, branchPositions: List<WorksitePosition>): LumberState {
        if (current.incident != null) return current
        val ageTicks = ((now - current.phaseStartedAt).coerceAtLeast(0) / 50L)
        val next = when {
            current.phase == LumberPhase.FALLING && ageTicks >= fallTicks -> {
                require(branchPositions.size == BRANCH_COUNT)
                val candidates = branchPositions.mapIndexed { index, point ->
                    ObjectiveTargetCandidate("branch_$index", point, ObjectiveTargetRole("branch"), index.toLong())
                }
                current.copy(phase = LumberPhase.DELIMBING, branches = ObjectiveTargetPool.plan(
                    WorksiteObjectiveKey(ZONE_ID, "branches", current.roundId), BRANCH_COUNT, candidates))
            }
            current.phase == LumberPhase.LOADING_SAWMILL && ageTicks >= LumberTiming.SAWMILL_LOAD_TICKS -> current.copy(phase = LumberPhase.SAWING)
            current.phase == LumberPhase.SAWING && current.sawRunning && ageTicks >= SAW_PASS_TICKS -> {
                val passes = current.sawPasses + 1
                current.copy(sawPasses = passes, sawRunning = false,
                    phase = if (passes == 2) LumberPhase.COLLECTING_BOARDS else LumberPhase.SAWING,
                    cart = if (passes == 2) null else current.cart)
            }
            current.phase == LumberPhase.COLLECTING_BOARDS && ageTicks >= LumberTiming.OUTPUT_SETTLE_TICKS ->
                current.copy(phase = LumberPhase.CRANE_TRANSFER, cart = null, cartCarrier = null, boardsCarrier = null, boardCartLoaded = false)
            current.phase == LumberPhase.CRANE_TRANSFER && ageTicks >= LumberTiming.CRANE_TRANSFER_TICKS ->
                LumberState(roundId = current.roundId, completedRounds = current.completedRounds + 1,
                    contributors = current.contributors, incidentHandledRoundId = current.incidentHandledRoundId,
                    incidentGeneration = current.incidentGeneration)
            else -> return current
        }
        return next.copy(phaseStartedAt = now, progressVersion = current.progressVersion + 1)
    }

    fun release(current: LumberState, player: UUID, cartHome: LumberPose): LumberState = LumberIncidentEngine.release(when (player) {
        current.cartCarrier -> current.copy(cartCarrier = null, cart = validCart(current.cart, cartHome), progressVersion = current.progressVersion + 1)
        current.boardsCarrier -> current.copy(phase = LumberPhase.COLLECTING_BOARDS, boardsCarrier = null,
            cart = null, boardCartLoaded = false,
            progressVersion = current.progressVersion + 1)
        else -> current
    }, player)

    fun recover(current: LumberState, cartHome: LumberPose, now: Long): LumberState = current.copy(
        phase = if (current.phase == LumberPhase.DELIVERING) LumberPhase.COLLECTING_BOARDS else current.phase,
        cartCarrier = null, boardsCarrier = null,
        // Legacy board phases retained the hauled log's pose, so recover their cart at the output.
        cart = if (current.phase in setOf(LumberPhase.COLLECTING_BOARDS, LumberPhase.CRANE_TRANSFER, LumberPhase.DELIVERING)) null else validCart(current.cart, cartHome),
        boardCartLoaded = false, sawRunning = false, phaseStartedAt = now,
        incident = current.incident?.copy(crankOperator = null),
    )

    private fun validCart(cart: LumberPose?, fallback: LumberPose): LumberPose = cart?.takeIf {
        it.world == fallback.world && listOf(it.x, it.y, it.z).all(Double::isFinite) && it.yaw.isFinite()
    } ?: fallback
}

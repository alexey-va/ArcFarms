package ru.ruscrafting.farms.domain.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.util.UUID

class LumberEngineTest : FunSpec({
    val worker = UUID.randomUUID()
    val other = UUID.randomUUID()
    val root = WorksitePosition("cedar", 110, 72, 180)
    val tree = LumberTreeSnapshot(root, (0..9).map { LumberBlockSnapshot(root.copy(y = 72 + it), "minecraft:oak_log[axis=y]") },
        listOf(LumberBlockSnapshot(root.copy(x = 111, y = 81), "minecraft:oak_leaves[persistent=false]")))
    val cart = LumberPose("cedar", 112.5, 72.0, 182.5)
    val branches = (0..2).map { root.copy(x = 113 + it * 2) }

    test("one workpiece follows the complete beta journey without duplicate or foreign delivery") {
        var now = 1_000L
        var state = LumberEngine.start(LumberState(), tree, cart, now)
        val round = state.roundId
        fun act(action: LumberAction, player: UUID = worker, index: Int = 0) {
            state = LumberEngine.act(state, LumberInput(action, index), player, now, 2)
        }
        act(LumberAction.SAW_LEVER)
        state.phase shouldBe LumberPhase.CHOPPING
        act(LumberAction.CHOP_TREE); act(LumberAction.CHOP_TREE)
        state.phase shouldBe LumberPhase.FALLING
        now += 3_000
        state = LumberEngine.tick(state, now, 60, branches)
        repeat(3) { act(LumberAction.CUT_BRANCH, index = it) }
        val finishedBranches = state
        act(LumberAction.CUT_BRANCH, index = 0)
        state shouldBe finishedBranches
        state.phase shouldBe LumberPhase.LOADING_CART
        act(LumberAction.LOAD_CART)
        act(LumberAction.TAKE_CART)
        state.cartCarrier shouldBe worker
        act(LumberAction.TAKE_CART, other)
        state.cartCarrier shouldBe worker
        act(LumberAction.LOAD_SAWMILL, other)
        state.phase shouldBe LumberPhase.HAULING
        act(LumberAction.LOAD_SAWMILL)
        now += LumberTiming.SAWMILL_LOAD_TICKS * 50L; state = LumberEngine.tick(state, now, 60, branches)
        repeat(2) {
            act(LumberAction.SAW_LEVER)
            val running = state
            act(LumberAction.SAW_LEVER)
            state shouldBe running
            now += 25_000; state = LumberEngine.tick(state, now, 60, branches)
        }
        state.phase shouldBe LumberPhase.COLLECTING_BOARDS
        act(LumberAction.COLLECT_BOARDS)
        act(LumberAction.DELIVER_BOARDS)
        state.completedRounds shouldBe 0
        now += 1_000
        act(LumberAction.DELIVER_BOARDS, other)
        state.phase shouldBe LumberPhase.DELIVERING
        act(LumberAction.DELIVER_BOARDS)
        state.phase shouldBe LumberPhase.IDLE
        state.roundId shouldBe round
        state.completedRounds shouldBe 1
        val delivered = state
        act(LumberAction.DELIVER_BOARDS)
        state shouldBe delivered
    }

    test("release and restart return logical cargo without replacing the workpiece or progress") {
        val started = LumberEngine.start(LumberState(), tree, cart, 1000)
        val lastSafeCart = cart.copy(x = cart.x + 12)
        val loaded = started.copy(phase = LumberPhase.HAULING, cartCarrier = worker, chopHits = 2, cart = lastSafeCart)
        LumberEngine.release(loaded, worker, cart).let {
            it.cartCarrier shouldBe null; it.roundId shouldBe loaded.roundId; it.tree shouldBe tree
            it.cart shouldBe lastSafeCart
        }
        val boards = loaded.copy(phase = LumberPhase.DELIVERING, cartCarrier = null, boardsCarrier = worker, sawPasses = 2)
        val recovered = LumberEngine.recover(boards, cart, 5000)
        recovered.phase shouldBe LumberPhase.COLLECTING_BOARDS
        recovered.boardsCarrier shouldBe null
        recovered.sawPasses shouldBe 2
        recovered.roundId shouldBe loaded.roundId
        recovered.cart shouldBe lastSafeCart
        LumberEngine.recover(loaded.copy(cart = null), cart, 5000).cart shouldBe cart
    }

    test("mask preserves the actual two-block stump and validation rejects oversized or missing trees") {
        tree.maskedBlocks.none { it.position.y < root.y + 2 } shouldBe true
        tree.logs.size shouldBe 10
        LumberStateValidation.validate(LumberEngine.start(LumberState(), tree, cart, 0))
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            LumberStateValidation.validate(LumberState(phase = LumberPhase.CHOPPING))
        }
    }
})

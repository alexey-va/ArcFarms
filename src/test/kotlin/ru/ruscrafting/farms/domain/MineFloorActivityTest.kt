package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.util.UUID

class MineFloorActivityTest : FunSpec({
    test("winch only accepts bounded walking in its safe pull lane") {
        var pull = MineFloorActivity.Pull(MineFloorActivity.PULL_X, MineFloorActivity.PULL_START_Z)
        repeat(7) { step ->
            pull = requireNotNull(MineFloorActivity.pull(pull, MineFloorActivity.PULL_X,
                MineFloorActivity.PULL_START_Z + (step + 1) * .4))
        }
        pull.distance shouldBe (MineFloorActivity.PULL_DISTANCE plusOrMinus .0001)
        MineFloorActivity.pull(pull, MineFloorActivity.PULL_X + 2, pull.z) shouldBe null
        MineFloorActivity.pull(pull, pull.x, pull.z + 2) shouldBe null
        MineFloorActivity.pull(pull, Double.NaN, pull.z) shouldBe null
    }

    test("standing and walking back cannot manufacture additional winch travel") {
        val pull = MineFloorActivity.Pull(MineFloorActivity.PULL_X, 2.65, 1.0)
        MineFloorActivity.pull(pull, pull.x, pull.z)?.distance shouldBe 1.0
        MineFloorActivity.pull(pull, pull.x, pull.z - .4)?.distance shouldBe 1.0
    }

    test("pump requires a held downstroke followed by release and refuses spam or stale holds") {
        val entered = MineFloorActivity.pump(MineFloorActivity.Pump(), true, 1_000)
        entered.accepted shouldBe false
        MineFloorActivity.pump(entered.state, true, 1_900).accepted shouldBe false
        MineFloorActivity.pump(entered.state, false, 1_100).accepted shouldBe false
        MineFloorActivity.pump(entered.state, false, 3_000).accepted shouldBe false
        val first = MineFloorActivity.pump(entered.state, false, 1_500)
        first.accepted shouldBe true
        MineFloorActivity.pump(first.state, false, 1_600).accepted shouldBe false
        val tooSoon = MineFloorActivity.pump(first.state, true, 1_550)
        MineFloorActivity.pump(tooSoon.state, false, 1_900).accepted shouldBe false
        val next = MineFloorActivity.pump(first.state, true, 2_000)
        MineFloorActivity.pump(next.state, false, 2_500).accepted shouldBe true
    }

    test("both floor jobs use bounded shared shift contribution and resume mining exactly once") {
        val player = UUID.randomUUID()
        for ((type, steps) in listOf(MineIncidentType.FREIGHT_WINCH to 4, MineIncidentType.DRAINAGE_PUMP to 6)) {
            val original = MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, mined = 7)
            var state = MineShiftEngine.startIncident(original, type, steps, 1_000).state
            repeat(steps) { state = MineShiftEngine.workIncident(state, player).state }
            state.incident?.progress shouldBe steps
            state.contributors[player] shouldBe steps
            MineShiftEngine.workIncident(state, player).accepted shouldBe false
            state = MineShiftEngine.resolveIncident(state).state
            state.phase shouldBe MinePhase.MINING
            state.mined shouldBe 7
            MineShiftEngine.resolveIncident(state).accepted shouldBe false
        }
    }
})

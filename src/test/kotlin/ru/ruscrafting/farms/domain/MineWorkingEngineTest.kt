package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

class MineWorkingEngineTest : FunSpec({
    val placement = MineWorkingPlacement(WorksitePosition("mine", 0, 50, 0), 0, "lower")

    test("excavation cannot count a repeated block and ends only after the supports") {
        var state = MineWorkingEngine.initial(MineIncidentType.TUNNEL_DRIVE, placement)
        val first = MineWorkingEngine.completeTarget(state, 0, 3, 100)
        first.accepted shouldBe true
        first.finished shouldBe false
        state = first.state
        MineWorkingEngine.completeTarget(state, 0, 3, 101).accepted shouldBe false
        state = MineWorkingEngine.completeTarget(state, 2, 3, 102).state
        state = MineWorkingEngine.completeTarget(state, 1, 3, 103).state
        state.stage shouldBe MineWorkingStage.SUPPORT
        state.completed shouldBe emptySet()
        val supported = MineWorkingEngine.completeTarget(state, 0, 1, 104)
        supported.finished shouldBe true
    }

    test("rails form a continuous line before a cart can traverse it") {
        var state = MineWorkingEngine.initial(MineIncidentType.RAIL_EXTENSION, placement)
        state = MineWorkingEngine.completeTarget(state, 0, 1, 100).state
        state.stage shouldBe MineWorkingStage.LAY_TRACK
        MineWorkingEngine.completeTarget(state, 1, 2, 101).accepted shouldBe false
        state = MineWorkingEngine.completeTarget(state, 0, 2, 102).state
        state = MineWorkingEngine.completeTarget(state, 1, 2, 103).state
        state.stage shouldBe MineWorkingStage.TEST_TRACK
        MineWorkingEngine.completeTarget(state, 1, 2, 104).accepted shouldBe false
        state = MineWorkingEngine.completeTarget(state, 0, 2, 105).state
        MineWorkingEngine.completeTarget(state, 1, 2, 106).finished shouldBe true
    }

    test("workshop requires hauling processing heat timing and shipment for every batch") {
        var state = MineWorkingEngine.initial(MineIncidentType.ORE_WORKSHOP, placement)
        repeat(MineWorkingEngine.BATCHES) { batch ->
            state.batch shouldBe batch
            state = MineWorkingEngine.completeTarget(state, 0, 1, 100).state
            state.stage shouldBe MineWorkingStage.CRUSH
            repeat(MineWorkingEngine.CRUSH_STROKES) { stroke ->
                state = MineWorkingEngine.completeTarget(state, stroke, MineWorkingEngine.CRUSH_STROKES, 200).state
            }
            state.stage shouldBe MineWorkingStage.HEAT
            MineWorkingEngine.completeTarget(state, 0, 1, 201).accepted shouldBe false
            MineWorkingEngine.completeTarget(state, 0, 1, 9_000).accepted shouldBe false
            state = MineWorkingEngine.reheat(state, 9_000)
            state = MineWorkingEngine.completeTarget(state, 0, 1, 13_000).state
            state.stage shouldBe MineWorkingStage.SHIP
            val shipped = MineWorkingEngine.completeTarget(state, 0, 1, 13_001)
            shipped.finished shouldBe (batch == MineWorkingEngine.BATCHES - 1)
            state = shipped.state
        }
    }

    test("direction rotation preserves floor elevation and connected steps") {
        (0..3).forEach { direction ->
            val rotated = placement.copy(direction = direction)
            rotated.position(0, 0, 0) shouldBe placement.entrance
            val first = rotated.position(0, 1, 1)
            val second = rotated.position(0, 1, 2)
            first.y shouldBe 51
            kotlin.math.abs(first.x - second.x) + kotlin.math.abs(first.z - second.z) shouldBe 1
        }
    }
})

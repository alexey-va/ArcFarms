package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

class TypedEngineEventTest : FunSpec({
    test("each shift engine exposes only its module event type") {
        val farm: EngineResult<FarmShiftState, FarmShiftEvent> = FarmShiftEngine.start(
            current = FarmShiftState(),
            order = FarmOrder("typed_farm", linkedMapOf("WHEAT" to 1)),
            patch = listOf(FarmPlotPosition("world", 0, 64, 0)),
            preparationCrop = "WHEAT",
            now = 1_000L,
        )
        val lumber: EngineResult<LumberShiftState, LumberShiftEvent> = LumberShiftEngine.start(
            current = LumberShiftState(),
            species = "OAK",
            rules = LumberRules(1, 1, 1, 0),
            now = 1_000L,
        )
        val mine: EngineResult<MineShiftState, MineShiftEvent> = MineShiftEngine.start(
            current = MineShiftState(),
            rules = MineRules(2, 1, 1, 0),
            now = 1_000L,
        )

        farm.events shouldContainExactly listOf(FarmShiftEvent.STARTED)
        lumber.events shouldContainExactly listOf(LumberShiftEvent.STARTED)
        mine.events shouldContainExactly listOf(MineShiftEvent.STARTED)
    }
})

package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.config.FarmCropLayoutSettings
import ru.ruscrafting.farms.domain.FarmPlotPosition

class FarmCropLayoutPlannerTest : FunSpec({
    val settings = FarmCropLayoutSettings(
        enabled = true,
        weights = linkedMapOf("WHEAT" to 1, "CARROTS" to 1),
        smallComponentMaxSize = 4,
        smallComponentMergeDistance = 4,
    )

    test("one connected bed receives one crop and nearby tiny islands inherit it") {
        val first = line(0, 12, z = 0)
        val tiny = line(2, 2, z = 3)
        val second = line(40, 12, z = 0)

        val plan = FarmCropLayoutPlanner.plan(first + tiny + second, settings)

        first.map(plan::get).distinct().size shouldBe 1
        tiny.map(plan::get).distinct() shouldBe first.map(plan::get).distinct()
        second.map(plan::get).distinct().size shouldBe 1
        second.first().let(plan::get) shouldBe "CARROTS"
        first.first().let(plan::get) shouldBe "WHEAT"
    }

    test("layout is stable regardless of scan order") {
        val beds = line(0, 10, z = 0) + line(30, 8, z = 0) + line(60, 6, z = 0)
        FarmCropLayoutPlanner.plan(beds, settings) shouldBe
            FarmCropLayoutPlanner.plan(beds.reversed(), settings)
    }

    test("disabled layout leaves existing farm authorship untouched") {
        FarmCropLayoutPlanner.plan(
            line(0, 10, z = 0),
            settings.copy(enabled = false),
        ) shouldBe emptyMap()
    }
})

private fun line(startX: Int, size: Int, z: Int): List<FarmPlotPosition> =
    (startX until startX + size).map { x -> FarmPlotPosition("farm", x, 64, z) }

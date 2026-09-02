package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class FarmRaidBlastPlannerTest : FunSpec({
    test("grenade preview selects a bounded nearest patch without changing the field model") {
        val plots = (-3..3).flatMap { x -> (-3..3).map { z -> FarmPlotPosition("world", x, 64, z) } }
        val center = FarmPointPosition("world", 0.5, 66.0, 0.5)

        val selected = FarmRaidBlastPlanner.select(plots, center, radius = 2.1, limit = 8)

        selected shouldHaveSize 8
        selected.first() shouldBe FarmPlotPosition("world", 0, 64, 0)
        selected.all { plot ->
            val dx = plot.x + 0.5 - center.x
            val dz = plot.z + 0.5 - center.z
            dx * dx + dz * dz <= 2.1 * 2.1
        } shouldBe true
    }
})

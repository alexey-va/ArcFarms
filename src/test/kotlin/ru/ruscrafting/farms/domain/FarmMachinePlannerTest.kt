package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class FarmMachinePlannerTest : FunSpec({
    test("machinery reaches every same-height bed around the horse without a route") {
        val field = (0..12).flatMap { x ->
            (0..12).map { z -> FarmPlotPosition("world", x, 64, z) }
        }

        val reached = FarmMachinePlanner.plotsInWorkingRadius(
            candidates = field,
            world = "world",
            machineX = 6.5,
            machineY = 65.0,
            machineZ = 6.5,
            radius = 4.0,
        )

        reached shouldBe field.filter { plot ->
            val dx = plot.x - 6
            val dz = plot.z - 6
            dx * dx + dz * dz <= 16
        }.toSet()
    }

    test("machinery ignores another world and a vertically separate terrace") {
        val candidates = listOf(
            FarmPlotPosition("world", 1, 64, 1),
            FarmPlotPosition("world", 1, 67, 1),
            FarmPlotPosition("other", 1, 64, 1),
        )

        FarmMachinePlanner.plotsInWorkingRadius(
            candidates,
            world = "world",
            machineX = 1.5,
            machineY = 65.0,
            machineZ = 1.5,
            radius = 8.0,
        ).shouldContainExactly(FarmPlotPosition("world", 1, 64, 1))
    }

    test("machine radius and patch size stay bounded") {
        shouldThrow<IllegalArgumentException> {
            FarmMachinePlanner.plotsInWorkingRadius(
                emptyList(),
                world = "world",
                machineX = 0.0,
                machineY = 64.0,
                machineZ = 0.0,
                radius = 17.0,
            )
        }
        shouldThrow<IllegalArgumentException> {
            FarmMachinePlanner.plotsInWorkingRadius(
                List(MAX_FARM_PATCH_PLOTS + 1) { FarmPlotPosition("world", it, 64, 0) },
                world = "world",
                machineX = 0.0,
                machineY = 65.0,
                machineZ = 0.0,
                radius = 8.0,
            )
        }
    }
})

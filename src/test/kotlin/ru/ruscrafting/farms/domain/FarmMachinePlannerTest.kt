package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmMachinePlannerTest : FunSpec({
    val field = (0 until 30).flatMap { x ->
        (0 until 12).map { z -> FarmPlotPosition("world", x, 64, z) }
    }

    test("machine route covers a large field in straight alternating passes") {
        val passes = FarmMachinePlanner.plan(field, workingWidth = 3, originX = -2.0, originZ = -2.0)

        passes.size shouldBe 4
        passes.flatMap(FarmMachinePass::plots).toSet() shouldBe field.toSet()
        passes.sumOf { it.plots.size } shouldBe field.size
        passes.all { pass -> pass.plots.maxOf(FarmPlotPosition::z) - pass.plots.minOf(FarmPlotPosition::z) <= 2 } shouldBe true
        passes.first().entry.x shouldBe 0
        passes.first().exit.x shouldBe 29
        passes[1].entry.x shouldBe 29
        passes[1].exit.x shouldBe 0
        passes[2].entry.x shouldBe 0
        passes[2].exit.x shouldBe 29
    }

    test("persisted endpoints reconstruct a complete non-overlapping field assignment") {
        val passes = FarmMachinePlanner.plan(field, workingWidth = 3, originX = -2.0, originZ = -2.0)
        val waypoints = passes.mapIndexed { index, pass ->
            index + 1 to FarmPointPosition(
                pass.exit.world,
                pass.exit.x + 0.5,
                pass.exit.y + 1.05,
                pass.exit.z + 0.5,
            )
        }

        val first = passes.first().entry
        val routeStart = FarmPointPosition(first.world, first.x + 0.5, first.y + 1.05, first.z + 0.5)
        val assignments = FarmMachinePlanner.assignToWaypoints(field, waypoints, routeStart)

        assignments.keys shouldBe (1..passes.size).toSet()
        assignments.values.flatten().toSet() shouldBe field.toSet()
        assignments.values.sumOf(Set<FarmPlotPosition>::size) shouldBe field.size
    }

    test("persisted route start keeps pass ownership stable after an admin trims the field") {
        val passes = FarmMachinePlanner.plan(field, workingWidth = 3, originX = -2.0, originZ = -2.0)
        val first = passes.first().entry
        val start = FarmPointPosition(first.world, first.x + 0.5, first.y + 1.05, first.z + 0.5)
        val waypoints = passes.mapIndexed { index, pass ->
            index + 1 to FarmPointPosition(pass.exit.world, pass.exit.x + 0.5, pass.exit.y + 1.05, pass.exit.z + 0.5)
        }
        val original = FarmMachinePlanner.assignToWaypoints(field, waypoints, start)
        val retained = field.filter { it.x in 10..19 }

        val trimmed = FarmMachinePlanner.assignToWaypoints(retained, waypoints, start)

        retained.forEach { plot ->
            trimmed.entries.single { plot in it.value }.key shouldBe original.entries.single { plot in it.value }.key
        }
    }

    test("one forgiving machine pass reaches the whole swath behind the horse") {
        val pass = field.filter { it.z in 0..4 }
        val destination = FarmPointPosition("world", 29.5, 65.05, 2.5)

        val reached = FarmMachinePlanner.plotsReachedToward(
            pass = pass,
            destination = destination,
            machineX = 10.5,
            machineZ = 5.5,
            laneTolerance = 4.0,
            leadDistance = 2.0,
        )

        reached shouldBe pass.filter { it.x <= 12 }.toSet()
        FarmMachinePlanner.plotsReachedToward(
            pass,
            destination,
            machineX = 10.5,
            machineZ = 8.0,
            laneTolerance = 4.0,
            leadDistance = 2.0,
        ) shouldBe emptySet()
        FarmMachinePlanner.guidanceLine(pass).size shouldBe 12
    }

    test("opposite endpoint reverses the same persisted pass for the planting run") {
        val pass = field.filter { it.z in 0..4 }
        val tillingDestination = FarmPointPosition("world", 29.5, 65.05, 2.5)

        FarmMachinePlanner.oppositeEndpoint(pass, tillingDestination) shouldBe
            FarmPointPosition("world", 0.5, 65.05, 2.5)
    }
})

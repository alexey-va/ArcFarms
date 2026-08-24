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

    test("machine only processes beds physically reached by its working width") {
        val pass = field.filter { it.z in 0..2 }

        val reached = FarmMachinePlanner.plotsUnderMachine(pass, machineX = 10.5, machineZ = 1.5, workingWidth = 3)

        reached.isNotEmpty() shouldBe true
        reached.all { it.x in 9..11 && it.z in 0..2 } shouldBe true
        FarmMachinePlanner.guidanceLine(pass).size shouldBe 12
    }
})

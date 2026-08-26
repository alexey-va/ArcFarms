package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

class FarmCarePlotAssignmentTest : FunSpec({
    test("assigns every plot to the nearest matching target") {
        val left = target(10, 0.5)
        val right = target(20, 10.5)
        val patch = (0..10).map { x -> FarmPlotPosition("world", x, 64, 0) }

        FarmCarePlotAssignment.assignedTo(patch, listOf(left, right), left.id)
            .shouldContainExactlyInAnyOrder((0..5).map { FarmPlotPosition("world", it, 64, 0) })
        FarmCarePlotAssignment.assignedTo(patch, listOf(left, right), right.id)
            .shouldContainExactlyInAnyOrder((6..10).map { FarmPlotPosition("world", it, 64, 0) })
    }

    test("uses target id as a stable tie breaker") {
        val lowerId = target(1, 0.5)
        val higherId = target(2, 2.5)
        val middle = FarmPlotPosition("world", 1, 64, 0)

        FarmCarePlotAssignment.assignedTo(listOf(middle), listOf(higherId, lowerId), lowerId.id)
            .shouldContainExactlyInAnyOrder(middle)
        FarmCarePlotAssignment.assignedTo(listOf(middle), listOf(higherId, lowerId), higherId.id)
            .shouldContainExactlyInAnyOrder()
    }
})

private fun target(id: Int, x: Double) = FarmCareTarget(
    id = id,
    role = FarmCareRole.VALVE,
    position = FarmPointPosition("world", x, 65.0, 0.5),
    required = 1,
)

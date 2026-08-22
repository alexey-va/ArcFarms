package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class FarmWaterFlowTrackerTest : FunSpec({
    test("overlapping pours keep shared water until the last flow settles") {
        val tracker = FarmWaterFlowTracker()
        val firstSource = FarmPlotPosition("world", 0, 65, 0)
        val secondSource = FarmPlotPosition("world", 2, 65, 0)
        val shared = FarmPlotPosition("world", 1, 65, 0)

        tracker.start(1, firstSource)
        tracker.start(2, secondSource)
        tracker.propagate(firstSource, shared) shouldBe setOf(1L)
        tracker.propagate(secondSource, shared) shouldBe setOf(2L)
        tracker.owners(shared) shouldBe setOf(1L, 2L)

        tracker.finish(1).shouldContainExactlyInAnyOrder(firstSource)
        tracker.owners(shared) shouldBe setOf(2L)
        tracker.finish(2).shouldContainExactlyInAnyOrder(secondSource, shared)
        tracker.isEmpty() shouldBe true
    }

    test("a new pour can start while another pour is still active") {
        val tracker = FarmWaterFlowTracker()

        tracker.start(10, FarmPlotPosition("world", 0, 65, 0))
        tracker.start(11, FarmPlotPosition("world", 6, 65, 0))

        tracker.isActive(10) shouldBe true
        tracker.isActive(11) shouldBe true
    }
})

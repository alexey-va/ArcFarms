package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class FarmBlockRestoreQueueTest : FunSpec({
    test("delayed crop repairs are ordered, deduplicated, and bounded") {
        val queue = FarmBlockRestoreQueue()
        val first = FarmFixedCropPosition("world", 1, 64, 1)
        val second = FarmFixedCropPosition("world", 2, 64, 1)
        queue.schedule(FarmFixedCropRestore(first, 2_000L))
        queue.schedule(FarmFixedCropRestore(second, 1_000L))
        queue.schedule(FarmFixedCropRestore(first, 1_500L))

        queue.pollDue(999L, 4) shouldBe emptyList()
        queue.pollDue(2_000L, 1).map(FarmFixedCropRestore::position) shouldContainExactly listOf(second)
        queue.pollDue(2_000L, 4).map(FarmFixedCropRestore::position) shouldContainExactly listOf(first)
    }
})

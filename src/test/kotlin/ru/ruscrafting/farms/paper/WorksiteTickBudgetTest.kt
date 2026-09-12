package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorksiteTickBudgetTest : FunSpec({
    test("caps operations without sampling the clock between checkpoints") {
        var clockReads = 0
        val budget = WorksiteTickBudget(
            maxOperations = 130,
            maxNanos = 2_000_000L,
            nowNanos = { clockReads++ ; 0L },
        )

        repeat(130) { budget.tryConsume() shouldBe true }
        budget.tryConsume() shouldBe false
        budget.operations shouldBe 130
        budget.remainingOperations shouldBe 0
        budget.stoppedByTime shouldBe false
        clockReads shouldBe 3 // construction, then before operation 65 and 129
    }

    test("yields at the 64-operation checkpoint when wall time is exhausted") {
        var clockReads = 0
        val budget = WorksiteTickBudget(
            maxOperations = 128,
            maxNanos = 10L,
            nowNanos = {
                clockReads++
                if (clockReads >= 2) 11L else 0L
            },
        )

        repeat(64) { budget.tryConsume() shouldBe true }
        budget.tryConsume() shouldBe false
        budget.operations shouldBe 64
        budget.remainingOperations shouldBe 64
        budget.stoppedByTime shouldBe true
        clockReads shouldBe 2
    }
})

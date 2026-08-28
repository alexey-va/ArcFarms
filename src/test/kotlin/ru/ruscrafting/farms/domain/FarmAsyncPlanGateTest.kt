package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmAsyncPlanGateTest : FunSpec({
    test("deduplicates planning within one shift") {
        val gate = FarmAsyncPlanGate()

        gate.acquire("farm", 7L) shouldBe true
        gate.acquire("farm", 7L) shouldBe false
        gate.release("farm", 7L)
        gate.acquire("farm", 7L) shouldBe true
    }

    test("late release from an old shift keeps the new shift guarded") {
        val gate = FarmAsyncPlanGate()

        gate.acquire("farm", 7L) shouldBe true
        gate.clearZone("farm")
        gate.acquire("farm", 8L) shouldBe true
        gate.release("farm", 7L)

        gate.acquire("farm", 8L) shouldBe false
    }
})

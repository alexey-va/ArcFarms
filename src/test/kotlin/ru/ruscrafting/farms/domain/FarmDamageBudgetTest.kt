package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmDamageBudgetTest : FunSpec({
    test("preserves configured reserve") {
        FarmDamageBudget.maximum(100, 90, 40, 1_000) shouldBe 60
    }

    test("honors proportional and absolute caps") {
        FarmDamageBudget.maximum(100, 20, 0, 50) shouldBe 20
        FarmDamageBudget.maximum(100, 90, 0, 12) shouldBe 12
        FarmDamageBudget.remaining(100, 9, 90, 0, 12) shouldBe 3
    }
})

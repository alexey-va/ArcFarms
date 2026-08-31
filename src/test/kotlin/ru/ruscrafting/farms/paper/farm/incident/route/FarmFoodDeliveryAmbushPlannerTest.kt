package ru.ruscrafting.farms.paper.farm.incident.route

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.ruscrafting.farms.domain.FarmPointPosition

class FarmFoodDeliveryAmbushPlannerTest : FunSpec({
    val route = (0..240).map { index -> FarmPointPosition("world", index.toDouble(), 64.0, 0.0) }

    test("one delivery keeps the same varied ambush checkpoints across reconciliation") {
        val first = FarmFoodDeliveryAmbushPlanner.checkpoints(route, 60.0, 3, 20.0, 20.0, 41L)
        val repeated = FarmFoodDeliveryAmbushPlanner.checkpoints(route, 60.0, 3, 20.0, 20.0, 41L)

        first shouldBe repeated
        first shouldHaveSize 3
        first.zipWithNext().all { (left, right) -> right - left >= 30 } shouldBe true
    }

    test("different delivery sequences vary the roadside ambush positions") {
        val first = FarmFoodDeliveryAmbushPlanner.checkpoints(route, 60.0, 3, 20.0, 20.0, 41L)
        val next = FarmFoodDeliveryAmbushPlanner.checkpoints(route, 60.0, 3, 20.0, 20.0, 42L)

        first shouldNotBe next
        first.all { it in 21..220 } shouldBe true
        next.all { it in 21..220 } shouldBe true
    }
})

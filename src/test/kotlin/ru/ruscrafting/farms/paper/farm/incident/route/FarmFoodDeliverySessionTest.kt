package ru.ruscrafting.farms.paper.farm.incident.route

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmFoodDeliverySessionTest : FunSpec({
    test("clearing a wave starts the respite interval at the clear time") {
        val session = FarmFoodDeliverySession(sequence = 3, routeName = "mill", monsterGoal = 12)
        session.brokenDown = true
        session.lastWaveAt = 1_000
        session.monsterIds += UUID.randomUUID()

        session.finishWaveIfCleared(20_000) shouldBe false
        session.monsterIds.clear()
        session.finishWaveIfCleared(20_000) shouldBe true

        session.brokenDown shouldBe false
        session.lastWaveAt shouldBe 20_000
        session.finishWaveIfCleared(30_000) shouldBe false
    }
})

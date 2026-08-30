package ru.ruscrafting.farms.paper.farm.incident.route

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmFoodDeliverySessionTest : FunSpec({
    test("clearing a wave reopens the route only after every attacker is gone") {
        val session = FarmFoodDeliverySession(sequence = 3, routeName = "mill")
        session.brokenDown = true
        session.monsterIds += UUID.randomUUID()

        session.finishWaveIfCleared() shouldBe false
        session.monsterIds.clear()
        session.finishWaveIfCleared() shouldBe true

        session.brokenDown shouldBe false
        session.finishWaveIfCleared() shouldBe false
    }

    test("dismount changes the seat role without ending route participation") {
        val playerId = UUID.randomUUID()
        val session = FarmFoodDeliverySession(sequence = 4, routeName = "mill", riderId = playerId)

        session.transitionToEscort(playerId)

        session.riderId shouldBe null
        session.escortIds shouldBe setOf(playerId)
        session.isParticipant(playerId) shouldBe true
        session.releaseParticipant(playerId) shouldBe true
        session.isParticipant(playerId) shouldBe false
    }
})

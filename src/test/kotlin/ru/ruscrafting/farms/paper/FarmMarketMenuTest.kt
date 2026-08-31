package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmMarketMenuTest : FunSpec({
    test("pending menu exposes decisions while active menu is read only") {
        FarmMarketMenu.decisionFor(FarmMarketMode.PENDING, 11) shouldBe FarmMarketDecision.ACCEPT
        FarmMarketMenu.decisionFor(FarmMarketMode.PENDING, 15) shouldBe FarmMarketDecision.DECLINE
        FarmMarketMenu.decisionFor(FarmMarketMode.PENDING, 22) shouldBe null
        FarmMarketMenu.decisionFor(FarmMarketMode.ACTIVE, 11) shouldBe null
        FarmMarketMenu.decisionFor(FarmMarketMode.ACTIVE, 22) shouldBe null
    }
})

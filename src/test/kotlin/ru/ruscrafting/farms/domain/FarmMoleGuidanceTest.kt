package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmMoleGuidanceTest : FunSpec({
    test("uses readable distance bands") {
        FarmMoleGuidance.proximity(20, 6, 14) shouldBe FarmMoleProximity.FAR
        FarmMoleGuidance.proximity(10, 6, 14) shouldBe FarmMoleProximity.CLOSER
        FarmMoleGuidance.proximity(4, 6, 14) shouldBe FarmMoleProximity.VERY_CLOSE
    }
})

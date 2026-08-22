package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmPestDamagePolicyTest : FunSpec({
    test("only an authorized active pest target can take player damage") {
        FarmPestDamagePolicy.allows(true, true, true, true) shouldBe true
        FarmPestDamagePolicy.allows(false, true, true, true) shouldBe false
        FarmPestDamagePolicy.allows(true, false, true, true) shouldBe false
        FarmPestDamagePolicy.allows(true, true, false, true) shouldBe false
        FarmPestDamagePolicy.allows(true, true, true, false) shouldBe false
    }
})

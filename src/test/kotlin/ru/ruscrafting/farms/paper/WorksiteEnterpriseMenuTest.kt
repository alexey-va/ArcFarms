package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorksiteEnterpriseMenuTest : FunSpec({
    test("enterprise money uses the same grouped typography as the visual proof") {
        formatEnterpriseMoney(24_875_000) shouldBe "248 750"
        formatEnterpriseMoney(248_750) shouldBe "2 487.5"
        formatEnterpriseMoney(100) shouldBe "1"
    }
})

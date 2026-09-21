package ru.ruscrafting.farms.domain.mine.expedition

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineFactoryPourTest : FunSpec({
    test("pour green window includes exactly 100 and 130 percent") {
        val pour = MineFactoryPour(0L)
        pour.ready(9_900L) shouldBe false
        pour.ready(10_000L) shouldBe true
        pour.ready(13_000L) shouldBe true
        pour.ready(13_001L) shouldBe false
        pour.overflow(13_000L) shouldBe false
        pour.overflow(13_001L) shouldBe true
    }
})

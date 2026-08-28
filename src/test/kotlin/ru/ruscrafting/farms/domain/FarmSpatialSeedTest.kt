package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldNotBe

class FarmSpatialSeedTest : FunSpec({
    test("nearby shift numbers produce distinct spatial seeds") {
        val values = (1L..64L).map { FarmSpatialSeed.mix(it, 101L) }

        values.toSet() shouldHaveSize values.size
        values.zipWithNext().forEach { (left, right) -> left shouldNotBe right }
    }

    test("activity salt changes placement for the same shift") {
        FarmSpatialSeed.mix(42L, 101L) shouldNotBe FarmSpatialSeed.mix(42L, 118L)
    }
})

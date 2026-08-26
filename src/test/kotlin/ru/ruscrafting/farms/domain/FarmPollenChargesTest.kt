package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmPollenChargesTest : FunSpec({
    val player = UUID(0, 42)

    test("charges belong to one farm sequence") {
        val charges = FarmPollenCharges()
        charges.grant(player, "farm", 7L, 2)

        charges.remaining(player, "farm", 7L) shouldBe 2
        charges.remaining(player, "farm", 8L) shouldBe 0
        charges.remaining(player, "other", 7L) shouldBe 0
    }

    test("consume cannot spend a stale charge") {
        val charges = FarmPollenCharges()
        charges.grant(player, "farm", 7L, 2)

        charges.consume(player, "farm", 8L) shouldBe false
        charges.consume(player, "farm", 7L) shouldBe true
        charges.remaining(player, "farm", 7L) shouldBe 1
    }

    test("leaving removes the player charge") {
        val charges = FarmPollenCharges()
        charges.grant(player, "farm", 7L, 2)

        charges.remove(player)

        charges.remaining(player, "farm", 7L) shouldBe 0
        charges.size() shouldBe 0
    }

    test("care cleanup clears charges for players outside the region too") {
        val charges = FarmPollenCharges()
        charges.grant(player, "farm", 7L, 2)
        charges.grant(UUID(0, 43), "farm", 7L, 1)
        charges.grant(UUID(0, 44), "other", 7L, 1)

        charges.clear("farm", 7L)

        charges.size() shouldBe 1
    }
})

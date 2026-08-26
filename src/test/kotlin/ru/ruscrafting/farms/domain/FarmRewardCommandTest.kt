package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class FarmRewardCommandTest : FunSpec({
    val playerId = UUID.fromString("00000000-0000-0000-0000-00000000002a")
    val context = FarmRewardCommandContext(
        playerName = "Farmer",
        playerId = playerId,
        zoneId = "communal_farm",
        sequence = 7,
        contribution = 321,
        rank = 2,
        grantId = "communal_farm:7:$playerId",
    )

    test("operator may use any command root and an escaped literal percent") {
        val resolved = TrustedFarmCommandTemplate.parse(
            "/custom.plugin:grant %player% --zone=%zone% --ratio=100%%",
        ).resolve(context)

        resolved.value shouldBe "custom.plugin:grant Farmer --zone=communal_farm --ratio=100%"
    }

    test("unknown placeholders fail during config parsing") {
        shouldThrow<IllegalArgumentException> {
            TrustedFarmCommandTemplate.parse("crate give %nickname% farm")
        }
    }

    test("dynamic substitution cannot split into another command argument") {
        shouldThrow<IllegalArgumentException> {
            TrustedFarmCommandTemplate.parse("say %player%").resolve(context.copy(playerName = "Farmer op Someone"))
        }
    }
})

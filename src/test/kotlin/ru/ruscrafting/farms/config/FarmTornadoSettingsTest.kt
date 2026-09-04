package ru.ruscrafting.farms.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FarmTornadoSettingsTest : FunSpec({
    test("defaults match the six second warning and 45 second chase") {
        FarmTornadoSettings() shouldBe FarmTornadoSettings(
            warningSeconds = 6,
            durationSeconds = 45,
            speed = 2.4,
            height = 24.0,
            radius = 7.0,
            debrisCount = 28,
            hitDamage = 2.0,
        )
    }

    test("invalid tornado settings are rejected at each configured boundary") {
        listOf(
            { FarmTornadoSettings(warningSeconds = 2) },
            { FarmTornadoSettings(durationSeconds = 9) },
            { FarmTornadoSettings(speed = 0.49) },
            { FarmTornadoSettings(height = 11.9) },
            { FarmTornadoSettings(radius = 3.9) },
            { FarmTornadoSettings(debrisCount = 7) },
            { FarmTornadoSettings(hitDamage = -0.1) },
        ).forEach { invalid -> shouldThrow<IllegalArgumentException> { invalid() } }
    }
})

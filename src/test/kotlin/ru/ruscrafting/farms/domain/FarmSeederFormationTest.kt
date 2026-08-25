package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe

class FarmSeederFormationTest : FunSpec({
    test("three pigs form a centered row ahead of the horse") {
        val positions = FarmSeederFormation.pigPositions(
            world = "world",
            horseX = 10.0,
            horseY = 65.0,
            horseZ = 20.0,
            directionX = 0.0,
            directionZ = 2.0,
            pigCount = 3,
            leadDistance = 2.5,
            spacing = 1.4,
        )

        positions.map(FarmMachinePosition::x) shouldBe listOf(11.4, 10.0, 8.6)
        positions.forEach { position ->
            position.y.shouldBeExactly(65.0)
            position.z.shouldBeExactly(22.5)
        }
    }

    test("formation direction is normalized and zero direction falls back safely") {
        FarmSeederFormation.pigPositions(
            "world", 0.0, 65.0, 0.0, 3.0, 4.0, 1, 5.0, 1.0,
        ).single() shouldBe FarmMachinePosition("world", 3.0, 65.0, 4.0)

        FarmSeederFormation.pigPositions(
            "world", 0.0, 65.0, 0.0, 0.0, 0.0, 1, 2.0, 1.0,
        ).single() shouldBe FarmMachinePosition("world", 0.0, 65.0, 2.0)
    }

    test("formation rejects an unbounded team") {
        shouldThrow<IllegalArgumentException> {
            FarmSeederFormation.pigPositions(
                "world", 0.0, 65.0, 0.0, 0.0, 1.0, 6, 2.0, 1.0,
            )
        }
    }
})

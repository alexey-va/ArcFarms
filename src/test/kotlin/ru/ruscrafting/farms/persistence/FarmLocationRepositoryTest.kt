package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.FarmLocationOverrides
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import java.nio.file.Files

class FarmLocationRepositoryTest : FunSpec({
    test("farm admin points survive a repository reopen") {
        val root = Files.createTempDirectory("arcfarms-location-test")
        val expected = FarmLocationOverrides(
            zones = mapOf(
                "communal_farm" to mapOf(
                    FarmPointKind.TOOL to FarmPointPosition("sp11", 210.5, 49.0, 448.5),
                    FarmPointKind.RECEIVING to FarmPointPosition("sp11", 201.65, 49.0, 453.46),
                    FarmPointKind.TRAVEL to FarmPointPosition("sp11", 216.5, 49.0, 453.5, 90f, 0f),
                ),
            ),
        )

        FarmLocationRepository(root).use { it.saveBlocking(expected) }
        FarmLocationRepository(root).use { it.load() shouldBe expected }
    }
})

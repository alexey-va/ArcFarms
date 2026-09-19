package ru.ruscrafting.farms.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.farms.domain.MineLocationPosition
import ru.ruscrafting.farms.domain.MineLocations
import ru.ruscrafting.farms.domain.MineZoneLocations
import java.nio.file.Files

class MineLocationRepositoryTest : FunSpec({
    test("mine workshop and side-working points survive an async repository reopen") {
        val root = Files.createTempDirectory("arcfarms-mine-location-test")
        val expected = MineLocations(
            zones = mapOf(
                "compact_mine" to MineZoneLocations(
                    workshop = mapOf(
                        "ore_input" to MineLocationPosition("rc_atelier_compact_mine", 72.5, 97.0, 27.5, 90f),
                        "ore_furnace" to MineLocationPosition("rc_atelier_compact_mine", 73.5, 97.0, 28.5, 180f),
                    ),
                    workings = mapOf(
                        "working_1" to MineLocationPosition("rc_atelier_compact_mine", 72.5, 97.0, 27.5),
                    ),
                ),
            ),
        )

        MineLocationRepository(root).use { it.saveAsync(expected).join() }
        MineLocationRepository(root).use { it.load() shouldBe expected }
    }
})

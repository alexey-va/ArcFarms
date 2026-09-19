package ru.ruscrafting.farms.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MineLocationsTest : FunSpec({
    test("capture snaps an arbitrary player heading to a cardinal yaw") {
        MineLocationPosition.capture("rc_atelier_compact_mine", 72.5, 97.0, 27.5, 44f).yaw shouldBe 0f
        MineLocationPosition.capture("rc_atelier_compact_mine", 72.5, 97.0, 27.5, -136f).yaw shouldBe -180f
        MineLocationPosition.capture("rc_atelier_compact_mine", 72.5, 97.0, 27.5, 720f).yaw shouldBe 0f
    }

    test("working point converts player feet and heading to the entrance floor") {
        val placement = MineLocationPosition("rc_atelier_compact_mine", 72.5, 97.0, 27.5, -90f).workingPlacement("middle", 42L)
        placement.entrance.y shouldBe 96
        placement.position(0, 1, 1).x shouldBe 73
        placement.position(0, 1, 1).y shouldBe 97
        placement.layoutSeed shouldBe 42L
    }

    test("persisted position rejects non-cardinal yaw") {
        shouldThrow<IllegalArgumentException> {
            MineLocationPosition("rc_atelier_compact_mine", 72.5, 97.0, 27.5, 12f)
        }
    }

    test("clearing a point removes an empty zone without inventing another point") {
        val locations = MineLocations(
            zones = mapOf(
                "mine" to MineZoneLocations(
                    workshop = mapOf("ore_input" to MineLocationPosition("world", 1.5, 2.0, 3.5)),
                ),
            ),
        )
        locations.without("mine", "ore_input").zones shouldBe emptyMap()
    }
})

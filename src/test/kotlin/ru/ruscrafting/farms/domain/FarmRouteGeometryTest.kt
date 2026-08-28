package ru.ruscrafting.farms.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class FarmRouteGeometryTest : FunSpec({
    test("projects onto a later segment across a sparse turn") {
        val points = listOf(
            FarmPointPosition("world", 0.0, 64.0, 0.0),
            FarmPointPosition("world", 20.0, 64.0, 0.0),
            FarmPointPosition("world", 20.0, 64.0, 20.0),
        )
        val result = FarmRouteGeometry.closest("world", 20.4, 64.0, 8.0, points, 0).shouldNotBeNull()
        result.segmentIndex shouldBe 1
        result.distance.shouldBeLessThan(0.5)
    }

    test("advances only after most of a segment is traversed") {
        val points = listOf(
            FarmPointPosition("world", 0.0, 64.0, 0.0),
            FarmPointPosition("world", 10.0, 64.0, 0.0),
            FarmPointPosition("world", 20.0, 64.0, 0.0),
        )
        val early = FarmRouteGeometry.closest("world", 7.1, 64.0, 0.0, points, 0).shouldNotBeNull()
        val reached = FarmRouteGeometry.closest("world", 7.3, 64.0, 0.0, points, 0).shouldNotBeNull()

        FarmRouteGeometry.reachedPoint(early, points.size) shouldBe 1
        FarmRouteGeometry.reachedPoint(reached, points.size) shouldBe 2
    }

    test("rejects a polyline from another world") {
        FarmRouteGeometry.closest(
            "nether",
            0.0,
            64.0,
            0.0,
            listOf(FarmPointPosition("world", 0.0, 64.0, 0.0), FarmPointPosition("world", 1.0, 64.0, 0.0)),
            0,
        ).shouldBeNull()
    }

    test("requires the cart to enter the configured three-dimensional destination radius") {
        val destination = FarmPointPosition("world", 100.0, 70.0, -20.0)

        FarmRouteGeometry.atDestination("world", 107.9, 70.0, -20.0, destination, 8.0) shouldBe true
        FarmRouteGeometry.atDestination("world", 108.1, 70.0, -20.0, destination, 8.0) shouldBe false
        FarmRouteGeometry.atDestination("world", 100.0, 79.0, -20.0, destination, 8.0) shouldBe false
        FarmRouteGeometry.atDestination("nether", 100.0, 70.0, -20.0, destination, 8.0) shouldBe false
    }
})

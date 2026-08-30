package ru.ruscrafting.farms.domain.placement

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlin.math.hypot

class WorksitePlacementPlannerTest : FunSpec({
    data class Candidate(val id: String, val point: WorksitePlacementPoint)

    val field = (0 until 60).flatMap { x ->
        (0 until 20).map { z -> Candidate("$x:$z", WorksitePlacementPoint("world", x.toDouble(), 64.0, z.toDouble())) }
    }

    test("balanced ring is reusable without farm-specific candidate types") {
        val selected = WorksitePlacementPlanner.select(
            candidates = field,
            request = WorksitePlacementRequest(count = 5, seed = 17L),
            profile = WorksitePlacementProfiles.balancedRing(minimumSpacing = 8.0),
            positionOf = Candidate::point,
        )

        selected.size shouldBe 5
        selected.none { normalizedRadius(it.point, field.map(Candidate::point)) < 0.30 } shouldBe true
        minimumSpacing(selected.map(Candidate::point)).shouldBeGreaterThanOrEqual(8.0)
    }

    test("events can switch to an independent farthest-point strategy") {
        val selected = WorksitePlacementPlanner.select(
            candidates = field,
            request = WorksitePlacementRequest(count = 5, seed = 17L),
            profile = WorksitePlacementProfiles.evenSpread(),
            positionOf = Candidate::point,
        )

        selected.size shouldBe 5
        minimumSpacing(selected.map(Candidate::point)).shouldBeGreaterThanOrEqual(19.0)
    }

    test("balanced ring falls back to every valid field point before relaxing spacing") {
        val sparse = listOf(
            Candidate("center", WorksitePlacementPoint("world", 0.0, 64.0, 0.0)),
            Candidate("east", WorksitePlacementPoint("world", 20.0, 64.0, 0.0)),
            Candidate("west", WorksitePlacementPoint("world", -20.0, 64.0, 0.0)),
            Candidate("north", WorksitePlacementPoint("world", 0.0, 64.0, -20.0)),
        )

        WorksitePlacementPlanner.select(
            candidates = sparse,
            request = WorksitePlacementRequest(count = 4, seed = 41L),
            profile = WorksitePlacementProfiles.balancedRing(minimumSpacing = 40.0),
            positionOf = Candidate::point,
        ).map(Candidate::id).toSet() shouldBe sparse.map(Candidate::id).toSet()
    }

    test("a placement request is deliberately scoped to one worksite world") {
        val candidates = listOf(
            Candidate("a", WorksitePlacementPoint("world", 0.0, 64.0, 0.0)),
            Candidate("b", WorksitePlacementPoint("other", 10.0, 64.0, 10.0)),
        )

        shouldThrow<IllegalArgumentException> {
            WorksitePlacementPlanner.select(
                candidates,
                WorksitePlacementRequest(count = 1, seed = 0L),
                WorksitePlacementProfiles.evenSpread(),
                Candidate::point,
            )
        }
    }
})

private fun minimumSpacing(points: List<WorksitePlacementPoint>): Double = points.minOf { first ->
    points.filterNot { it == first }.minOf { second ->
        hypot(first.x - second.x, first.z - second.z)
    }
}

private fun normalizedRadius(point: WorksitePlacementPoint, field: Collection<WorksitePlacementPoint>): Double {
    val centerX = (field.minOf(WorksitePlacementPoint::x) + field.maxOf(WorksitePlacementPoint::x)) / 2.0
    val centerZ = (field.minOf(WorksitePlacementPoint::z) + field.maxOf(WorksitePlacementPoint::z)) / 2.0
    val halfWidth = ((field.maxOf(WorksitePlacementPoint::x) - field.minOf(WorksitePlacementPoint::x)) / 2.0).coerceAtLeast(1.0)
    val halfDepth = ((field.maxOf(WorksitePlacementPoint::z) - field.minOf(WorksitePlacementPoint::z)) / 2.0).coerceAtLeast(1.0)
    return hypot((point.x - centerX) / halfWidth, (point.z - centerZ) / halfDepth)
}

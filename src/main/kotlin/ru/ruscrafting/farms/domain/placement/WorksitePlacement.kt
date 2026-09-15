package ru.ruscrafting.farms.domain.placement

import ru.ruscrafting.farms.domain.DomainIdentifiers

/** Platform-neutral horizontal point consumed by reusable worksite placement strategies. */
data class WorksitePlacementPoint(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(DomainIdentifiers.isWorld(world)) { "Invalid placement world: $world" }
        require(listOf(x, y, z).all(Double::isFinite)) { "Placement coordinates must be finite" }
    }
}

data class WorksitePlacementRequest(
    val count: Int,
    val seed: Long,
) {
    init {
        require(count >= 0) { "Placement count must not be negative" }
    }
}

/**
 * One replaceable spatial policy. Strategies receive canonical, unique points
 * from one worksite world and must return only points from that input.
 */
interface WorksitePlacementStrategy {
    val id: String

    fun select(
        candidates: List<WorksitePlacementPoint>,
        request: WorksitePlacementRequest,
        minimumSpacing: Double,
    ): List<WorksitePlacementPoint>
}

data class WorksitePlacementProfile(
    val strategy: WorksitePlacementStrategy,
    val minimumSpacing: Double = 0.0,
) {
    init {
        require(minimumSpacing.isFinite() && minimumSpacing >= 0.0) { "Placement spacing is invalid" }
    }
}

/** Named defaults let an event choose spatial intent without owning its algorithm. */
object WorksitePlacementProfiles {
    fun balancedRing(minimumSpacing: Double): WorksitePlacementProfile =
        WorksitePlacementProfile(BalancedRingPlacementStrategy, minimumSpacing)

    fun evenSpread(minimumSpacing: Double = 0.0): WorksitePlacementProfile =
        WorksitePlacementProfile(FarthestPointPlacementStrategy, minimumSpacing)
}

/**
 * Adapts any event-owned candidate type to a shared strategy and maps the
 * selected canonical points back to the original values.
 */
object WorksitePlacementPlanner {
    /**
     * Stable pseudo-random ordering for event candidate pools. The same seed can be replayed after a restart;
     * changing the objective sequence changes the sampled area without introducing event-local hash functions.
     */
    fun <T> seededOrder(
        candidates: Collection<T>,
        seed: Long,
        positionOf: (T) -> WorksitePlacementPoint,
    ): List<T> {
        val valuesByPoint = valuesByPoint(candidates, positionOf)
        return valuesByPoint.keys.sortedWith(
            compareBy<WorksitePlacementPoint> { point ->
                val coordinateSeed = java.lang.Double.doubleToLongBits(point.x) xor
                    java.lang.Long.rotateLeft(java.lang.Double.doubleToLongBits(point.y), 17) xor
                    java.lang.Long.rotateLeft(java.lang.Double.doubleToLongBits(point.z), 33) xor
                    point.world.hashCode().toLong()
                WorksitePlacementMix.mix(seed xor coordinateSeed)
            }.then(POINT_ORDER),
        ).map(valuesByPoint::getValue)
    }

    fun <T> select(
        candidates: Collection<T>,
        request: WorksitePlacementRequest,
        profile: WorksitePlacementProfile,
        positionOf: (T) -> WorksitePlacementPoint,
    ): List<T> {
        if (request.count == 0 || candidates.isEmpty()) return emptyList()
        val valuesByPoint = valuesByPoint(candidates, positionOf)
        val available = valuesByPoint.keys.sortedWith(POINT_ORDER)
        require(available.map(WorksitePlacementPoint::world).distinct().size == 1) {
            "A placement request must belong to exactly one worksite world"
        }
        if (available.size <= request.count) return available.map(valuesByPoint::getValue)

        val selected = profile.strategy.select(available, request, profile.minimumSpacing)
        require(selected.size == minOf(request.count, available.size)) {
            "Placement strategy '${profile.strategy.id}' returned ${selected.size} points for ${request.count} requested"
        }
        require(selected.distinct().size == selected.size && selected.all(valuesByPoint::containsKey)) {
            "Placement strategy '${profile.strategy.id}' returned duplicate or foreign points"
        }
        return selected.map(valuesByPoint::getValue)
    }

    private fun <T> valuesByPoint(
        candidates: Collection<T>,
        positionOf: (T) -> WorksitePlacementPoint,
    ): LinkedHashMap<WorksitePlacementPoint, T> = linkedMapOf<WorksitePlacementPoint, T>().also { values ->
        candidates.distinct().forEach { value ->
            val point = positionOf(value)
            require(values.put(point, value) == null) {
                "Placement candidates contain duplicate point $point"
            }
        }
    }

    internal val POINT_ORDER = compareBy<WorksitePlacementPoint>(
        WorksitePlacementPoint::world,
        WorksitePlacementPoint::y,
        WorksitePlacementPoint::x,
        WorksitePlacementPoint::z,
    )
}

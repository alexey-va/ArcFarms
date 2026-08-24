package ru.ruscrafting.farms.domain

import kotlin.math.abs
import kotlin.math.ceil

data class FarmMachinePass(
    val entry: FarmPlotPosition,
    val exit: FarmPlotPosition,
    val plots: List<FarmPlotPosition>,
)

/**
 * Builds bounded, straight, alternating field passes for horse-drawn machinery.
 *
 * The persisted care waypoints remain the source of truth after a restart. The
 * assignment function therefore reconstructs pass ownership from those saved
 * endpoints instead of depending on a transient in-memory route.
 */
object FarmMachinePlanner {
    fun plan(
        candidates: Collection<FarmPlotPosition>,
        workingWidth: Int,
        originX: Double,
        originZ: Double,
    ): List<FarmMachinePass> {
        require(workingWidth in 1..12) { "Farm machine working width must be in 1..12" }
        val plots = candidates.distinct().sortedWith(POSITION_ORDER)
        require(plots.isNotEmpty() && plots.size <= MAX_FARM_PATCH_PLOTS) {
            "Farm machine route must contain 1..$MAX_FARM_PATCH_PLOTS plots"
        }
        require(plots.map(FarmPlotPosition::world).distinct().size == 1) { "Farm machine route crosses worlds" }

        val axis = primaryAxis(plots)
        val rows = plots.groupBy { plot -> plot.y to axis.perpendicular(plot) }
            .entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        val rowsPerPass = maxOf(workingWidth, ceil(rows.size / MAX_MACHINE_PASSES.toDouble()).toInt())
        val unordered = rows.chunked(rowsPerPass).map { stripeRows ->
            val stripe = stripeRows.flatMap(Map.Entry<Pair<Int, Int>, List<FarmPlotPosition>>::value)
                .sortedWith(POSITION_ORDER)
            val low = endpoint(stripe, axis, minimum = true)
            val high = endpoint(stripe, axis, minimum = false)
            MachineStripe(stripe, low, high)
        }
        require(unordered.size <= MAX_MACHINE_PASSES) { "Farm machine route has too many passes" }

        return routeCandidates(unordered, originX, originZ).minWith(
            compareBy<RouteCandidate> { it.travelCost }
                .thenBy { it.passes.first().entry.x }
                .thenBy { it.passes.first().entry.z },
        ).passes
    }

    fun assignToWaypoints(
        candidates: Collection<FarmPlotPosition>,
        waypoints: Collection<Pair<Int, FarmPointPosition>>,
        routeStart: FarmPointPosition? = null,
    ): Map<Int, Set<FarmPlotPosition>> {
        val plots = candidates.distinct()
        require(plots.size <= MAX_FARM_PATCH_PLOTS) { "Farm machine assignment is unbounded" }
        val orderedWaypoints = waypoints.distinctBy(Pair<Int, FarmPointPosition>::first).sortedBy { it.first }
        if (plots.isEmpty() || orderedWaypoints.isEmpty()) return emptyMap()
        require(plots.map(FarmPlotPosition::world).distinct().size == 1) { "Farm machine assignment crosses worlds" }
        require(orderedWaypoints.all { (_, point) -> point.world == plots.first().world }) {
            "Farm machine waypoint crosses worlds"
        }
        val firstWaypoint = orderedWaypoints.first().second
        val axis = routeStart?.takeIf { it.world == plots.first().world }?.let { start ->
            val xDistance = abs(firstWaypoint.x - start.x)
            val zDistance = abs(firstWaypoint.z - start.z)
            when {
                xDistance > zDistance -> Axis.X
                zDistance > xDistance -> Axis.Z
                else -> primaryAxis(plots)
            }
        } ?: primaryAxis(plots)
        val assignments = orderedWaypoints.associate { it.first to linkedSetOf<FarmPlotPosition>() }
        plots.forEach { plot ->
            val selected = orderedWaypoints.minWith(
                compareBy<Pair<Int, FarmPointPosition>> { (_, point) ->
                    abs(axis.perpendicular(plot) + 0.5 - axis.perpendicular(point))
                }.thenBy { (_, point) -> abs(plot.y + 1.05 - point.y) }
                    .thenBy { it.first },
            )
            assignments.getValue(selected.first) += plot
        }
        return assignments
    }

    fun plotsUnderMachine(
        pass: Collection<FarmPlotPosition>,
        machineX: Double,
        machineZ: Double,
        workingWidth: Int,
    ): Set<FarmPlotPosition> {
        require(workingWidth in 1..12) { "Farm machine working width must be in 1..12" }
        val radius = maxOf(0.9, workingWidth / 2.0 + 0.35)
        val radiusSquared = radius * radius
        return pass.filterTo(linkedSetOf()) { plot ->
            val dx = plot.x + 0.5 - machineX
            val dz = plot.z + 0.5 - machineZ
            dx * dx + dz * dz <= radiusSquared
        }
    }

    fun guidanceLine(pass: Collection<FarmPlotPosition>, maximumMarkers: Int = 12): List<FarmPlotPosition> {
        require(maximumMarkers in 2..32) { "Farm machine guidance marker count must be in 2..32" }
        val plots = pass.distinct()
        if (plots.isEmpty()) return emptyList()
        val axis = primaryAxis(plots)
        val center = plots.map(axis::perpendicular).average()
        val centerLine = plots.groupBy(axis::forward).entries.sortedBy(Map.Entry<Int, List<FarmPlotPosition>>::key)
            .map { (_, row) ->
                row.minWith(
                    compareBy<FarmPlotPosition> { abs(axis.perpendicular(it) - center) }
                        .then(POSITION_ORDER),
                )
            }
        if (centerLine.size <= maximumMarkers) return centerLine
        return (0 until maximumMarkers).map { index ->
            centerLine[index * (centerLine.lastIndex) / (maximumMarkers - 1)]
        }.distinct()
    }

    private fun routeCandidates(
        stripes: List<MachineStripe>,
        originX: Double,
        originZ: Double,
    ): List<RouteCandidate> = buildList {
        listOf(stripes, stripes.asReversed()).forEach { ordered ->
            listOf(true, false).forEach { firstLowToHigh ->
                val passes = ordered.mapIndexed { index, stripe ->
                    val lowToHigh = if (index % 2 == 0) firstLowToHigh else !firstLowToHigh
                    FarmMachinePass(
                        entry = if (lowToHigh) stripe.low else stripe.high,
                        exit = if (lowToHigh) stripe.high else stripe.low,
                        plots = stripe.plots,
                    )
                }
                var x = originX
                var z = originZ
                var cost = 0.0
                passes.forEach { pass ->
                    cost += distanceSquared(x, z, pass.entry)
                    x = pass.exit.x + 0.5
                    z = pass.exit.z + 0.5
                }
                add(RouteCandidate(passes, cost))
            }
        }
    }

    private fun endpoint(plots: List<FarmPlotPosition>, axis: Axis, minimum: Boolean): FarmPlotPosition {
        val forward = if (minimum) plots.minOf(axis::forward) else plots.maxOf(axis::forward)
        val candidates = plots.filter { axis.forward(it) == forward }
        val center = plots.map(axis::perpendicular).average()
        return candidates.minWith(
            compareBy<FarmPlotPosition> { abs(axis.perpendicular(it) - center) }
                .then(POSITION_ORDER),
        )
    }

    private fun primaryAxis(plots: Collection<FarmPlotPosition>): Axis {
        val xSpan = plots.maxOf(FarmPlotPosition::x) - plots.minOf(FarmPlotPosition::x)
        val zSpan = plots.maxOf(FarmPlotPosition::z) - plots.minOf(FarmPlotPosition::z)
        return if (xSpan >= zSpan) Axis.X else Axis.Z
    }

    private fun distanceSquared(x: Double, z: Double, plot: FarmPlotPosition): Double {
        val dx = plot.x + 0.5 - x
        val dz = plot.z + 0.5 - z
        return dx * dx + dz * dz
    }

    private enum class Axis {
        X,
        Z;

        fun forward(plot: FarmPlotPosition): Int = if (this == X) plot.x else plot.z

        fun perpendicular(plot: FarmPlotPosition): Int = if (this == X) plot.z else plot.x

        fun perpendicular(point: FarmPointPosition): Double = if (this == X) point.z else point.x
    }

    private data class MachineStripe(
        val plots: List<FarmPlotPosition>,
        val low: FarmPlotPosition,
        val high: FarmPlotPosition,
    )

    private data class RouteCandidate(
        val passes: List<FarmMachinePass>,
        val travelCost: Double,
    )

    private const val MAX_MACHINE_PASSES = 63
    private val POSITION_ORDER = compareBy<FarmPlotPosition>(
        FarmPlotPosition::world,
        FarmPlotPosition::y,
        FarmPlotPosition::x,
        FarmPlotPosition::z,
    )
}

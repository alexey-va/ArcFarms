package ru.ruscrafting.farms.domain

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

data class FarmIrrigationRing(
    val index: Int,
    val radius: Double,
    val plots: List<FarmPlotPosition>,
)

/** Builds a deterministic, circular hydration front around one irrigation valve. */
object FarmIrrigationWavePlanner {
    fun rings(
        plots: Collection<FarmPlotPosition>,
        source: FarmPointPosition,
        ringWidth: Double,
    ): List<FarmIrrigationRing> {
        require(ringWidth > 0.0 && ringWidth.isFinite()) { "Irrigation ring width must be finite and positive" }
        return plots.groupBy { plot ->
            floor(distance(plot, source) / ringWidth).toInt()
        }.toSortedMap().map { (index, ringPlots) ->
            FarmIrrigationRing(
                index = index,
                radius = (index + 1) * ringWidth,
                plots = ringPlots.sortedWith(
                    compareBy<FarmPlotPosition> { plot ->
                        atan2(plot.z + 0.5 - source.z, plot.x + 0.5 - source.x)
                    }.thenBy(FarmPlotPosition::x).thenBy(FarmPlotPosition::z).thenBy(FarmPlotPosition::y),
                ),
            )
        }
    }

    private fun distance(plot: FarmPlotPosition, source: FarmPointPosition): Double = hypot(
        plot.x + 0.5 - source.x,
        plot.z + 0.5 - source.z,
    )
}

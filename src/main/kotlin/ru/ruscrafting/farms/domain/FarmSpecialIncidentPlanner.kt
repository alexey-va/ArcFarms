package ru.ruscrafting.farms.domain

data class FarmMatureCrop(
    val plot: FarmPlotPosition,
    val crop: String,
)

data class FarmSpecialIncidentPlan(
    val state: FarmSpecialIncidentState,
    val required: Int,
)

object FarmSpecialIncidentPlanner {
    fun plan(
        type: FarmIncidentType,
        sequence: Long,
        matureCrops: Collection<FarmMatureCrop>,
        fallbackPlot: FarmPlotPosition?,
        irrigationSource: FarmPointPosition?,
        giantHits: Int,
        channelGates: Int,
        nightCrops: Int,
        marketCrops: Int,
    ): FarmSpecialIncidentPlan? {
        require(giantHits in 1..1_024)
        require(channelGates in 1..16)
        require(nightCrops in 1..128)
        require(marketCrops in 1..128)
        val candidates = rotate(
            matureCrops.distinctBy(FarmMatureCrop::plot).sortedWith(
                compareBy<FarmMatureCrop> { it.plot.x }.thenBy { it.plot.z }.thenBy { it.plot.y }.thenBy { it.crop },
            ),
            sequence + type.ordinal * 37L,
        )
        return when (type) {
            FarmIncidentType.GIANT_CROP -> {
                val anchor = candidates.firstOrNull()?.plot ?: fallbackPlot ?: return null
                val crop = candidates.firstOrNull()?.crop?.takeIf { it == "PUMPKIN" || it == "MELON" } ?: "PUMPKIN"
                FarmSpecialIncidentPlan(
                    FarmSpecialIncidentState(
                        points = listOf(FarmPointPosition(anchor.world, anchor.x + 0.5, anchor.y + 1.45, anchor.z + 0.5)),
                        crop = crop,
                    ),
                    giantHits,
                )
            }
            FarmIncidentType.CHANNELS -> {
                val source = irrigationSource ?: return null
                val anchor = candidates.firstOrNull()?.plot ?: fallbackPlot ?: return null
                val target = FarmPointPosition(anchor.world, anchor.x + 0.5, anchor.y + 1.05, anchor.z + 0.5)
                val gates = interpolate(source, target, channelGates)
                val solution = gates.indices.filterTo(linkedSetOf()) { index ->
                    java.lang.Math.floorMod(sequence.toInt() + index * 3, 5) in 0..2
                }.ifEmpty { linkedSetOf(0) }
                FarmSpecialIncidentPlan(
                    FarmSpecialIncidentState(points = gates, plots = listOf(anchor), solution = solution),
                    gates.size,
                )
            }
            FarmIncidentType.NIGHT_SHIFT -> candidates.take(nightCrops).takeIf(List<FarmMatureCrop>::isNotEmpty)?.let { chosen ->
                FarmSpecialIncidentPlan(FarmSpecialIncidentState(plots = chosen.map(FarmMatureCrop::plot)), chosen.size)
            }
            FarmIncidentType.MARKET -> candidates.groupBy(FarmMatureCrop::crop).entries
                .maxWithOrNull(compareBy<Map.Entry<String, List<FarmMatureCrop>>> { it.value.size }.thenBy { it.key })
                ?.let { (crop, entries) ->
                    val chosen = entries.take(marketCrops)
                    FarmSpecialIncidentPlan(
                        FarmSpecialIncidentState(plots = chosen.map(FarmMatureCrop::plot), crop = crop),
                        chosen.size,
                    )
                }
            FarmIncidentType.PESTS, FarmIncidentType.DROUGHT -> null
        }
    }

    private fun <T> rotate(values: List<T>, salt: Long): List<T> {
        if (values.isEmpty()) return values
        val offset = java.lang.Math.floorMod((salt xor (salt ushr 32)).toInt(), values.size)
        return values.drop(offset) + values.take(offset)
    }

    private fun interpolate(
        source: FarmPointPosition,
        target: FarmPointPosition,
        count: Int,
    ): List<FarmPointPosition> = (1..count).map { step ->
        val ratio = step.toDouble() / (count + 1).toDouble()
        FarmPointPosition(
            source.world,
            source.x + (target.x - source.x) * ratio,
            source.y + (target.y - source.y) * ratio,
            source.z + (target.z - source.z) * ratio,
        )
    }
}

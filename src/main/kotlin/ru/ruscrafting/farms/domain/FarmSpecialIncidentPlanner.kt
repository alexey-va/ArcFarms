package ru.ruscrafting.farms.domain

data class FarmMatureCrop(
    val plot: FarmPlotPosition,
    val crop: String,
)

data class FarmGiantCropCandidate(
    val block: FarmPlotPosition,
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
        giantCandidates: Collection<FarmGiantCropCandidate> = emptyList(),
        nightPatrolPlots: Collection<FarmPlotPosition> = matureCrops.map(FarmMatureCrop::plot),
        fallbackPlot: FarmPlotPosition?,
        irrigationSource: FarmPointPosition?,
        channelGates: Int,
        nightCropPlacements: Int,
        nightCropTarget: Int,
        nightCropMinSpacing: Double,
        nightPatrols: Int,
        nightPatrolMinSpacing: Double,
        marketCrops: Int,
    ): FarmSpecialIncidentPlan? {
        require(channelGates in 1..16)
        require(nightCropPlacements in 1..128)
        require(nightCropTarget in 1..nightCropPlacements)
        require(nightCropMinSpacing.isFinite() && nightCropMinSpacing in 0.0..64.0)
        require(nightPatrols in 0..16)
        require(nightPatrolMinSpacing.isFinite() && nightPatrolMinSpacing in 0.0..64.0)
        require(marketCrops in 1..512)
        val candidates = rotate(
            matureCrops.distinctBy(FarmMatureCrop::plot).sortedWith(
                compareBy<FarmMatureCrop> { it.plot.x }.thenBy { it.plot.z }.thenBy { it.plot.y }.thenBy { it.crop },
            ),
            sequence + type.ordinal * 37L,
        )
        return when (type) {
            FarmIncidentType.GIANT_CROP -> {
                val chosen = rotate(
                    giantCandidates.filter { FarmGiantCropBlueprint.supports(it.crop) }
                        .distinctBy(FarmGiantCropCandidate::block)
                        .sortedWith(compareBy<FarmGiantCropCandidate> { it.block.x }
                            .thenBy { it.block.z }
                            .thenBy { it.block.y }
                            .thenBy { it.crop }),
                    sequence + 173L,
                ).firstOrNull() ?: return null
                FarmSpecialIncidentPlan(
                    FarmSpecialIncidentState(
                        points = listOf(
                            FarmPointPosition(
                                chosen.block.world,
                                chosen.block.x + 0.5,
                                chosen.block.y.toDouble(),
                                chosen.block.z + 0.5,
                            ),
                        ),
                        crop = chosen.crop,
                    ),
                    FarmGiantCropBlueprint.voxels(chosen.crop).size,
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
            FarmIncidentType.NIGHT_SHIFT -> selectSpaced(candidates, nightCropPlacements, nightCropMinSpacing)
                .takeIf(List<FarmMatureCrop>::isNotEmpty)?.let { chosen ->
                    val patrolCandidates = rotate(
                        nightPatrolPlots.distinct().sortedWith(
                            compareBy<FarmPlotPosition> { it.x }.thenBy { it.z }.thenBy { it.y },
                        ).map { FarmMatureCrop(it, "") },
                        sequence + 911L,
                    )
                    val patrols = selectSpaced(
                        patrolCandidates,
                        nightPatrols,
                        nightPatrolMinSpacing,
                    ).map { candidate ->
                        FarmPointPosition(
                            candidate.plot.world,
                            candidate.plot.x + 0.5,
                            candidate.plot.y + 1.0,
                            candidate.plot.z + 0.5,
                        )
                    }
                    FarmSpecialIncidentPlan(
                        FarmSpecialIncidentState(
                            points = patrols,
                            plots = chosen.map(FarmMatureCrop::plot),
                        ),
                        minOf(nightCropTarget, chosen.size),
                    )
                }
            FarmIncidentType.MARKET -> candidates.groupBy(FarmMatureCrop::crop).entries
                .maxWithOrNull(compareBy<Map.Entry<String, List<FarmMatureCrop>>> { it.value.size }.thenBy { it.key })
                ?.let { (crop, entries) ->
                    val chosen = entries.take(minOf(marketCrops, 128))
                    FarmSpecialIncidentPlan(
                        FarmSpecialIncidentState(plots = chosen.map(FarmMatureCrop::plot), crop = crop),
                        marketCrops,
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

    private fun selectSpaced(
        candidates: List<FarmMatureCrop>,
        count: Int,
        minimumSpacing: Double,
    ): List<FarmMatureCrop> {
        if (candidates.isEmpty() || count == 0) return emptyList()
        val selected = mutableListOf(candidates.first())
        val minimumSquared = minimumSpacing * minimumSpacing
        while (selected.size < minOf(count, candidates.size)) {
            val remaining = candidates.filterNot(selected::contains)
            val sufficientlyDistant = remaining.filter { candidate ->
                selected.all { existing -> horizontalDistanceSquared(candidate.plot, existing.plot) >= minimumSquared }
            }
            val pool = sufficientlyDistant.ifEmpty { remaining }
            val next = pool.maxWithOrNull(
                compareBy<FarmMatureCrop> { candidate ->
                    selected.minOf { existing -> horizontalDistanceSquared(candidate.plot, existing.plot) }
                }.thenByDescending { it.plot.x }
                    .thenByDescending { it.plot.z }
                    .thenByDescending { it.plot.y },
            ) ?: break
            selected += next
        }
        return selected
    }

    private fun horizontalDistanceSquared(first: FarmPlotPosition, second: FarmPlotPosition): Double {
        val dx = (first.x - second.x).toDouble()
        val dz = (first.z - second.z).toDouble()
        return dx * dx + dz * dz
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

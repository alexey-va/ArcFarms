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
        channelBlockages: Int,
        nightCropPlacements: Int,
        nightCropTarget: Int,
        nightCropMinSpacing: Double,
        nightPatrols: Int,
        nightPatrolMinSpacing: Double,
        marketCrops: Int,
    ): FarmSpecialIncidentPlan? {
        require(channelBlockages in 1..16)
        require(nightCropPlacements in 1..128)
        require(nightCropTarget in 1..nightCropPlacements)
        require(nightCropMinSpacing.isFinite() && nightCropMinSpacing in 0.0..64.0)
        require(nightPatrols in 0..16)
        require(nightPatrolMinSpacing.isFinite() && nightPatrolMinSpacing in 0.0..64.0)
        require(marketCrops in 1..512)
        val candidates by lazy {
            rotate(
                matureCrops.distinctBy(FarmMatureCrop::plot).sortedWith(
                    compareBy<FarmMatureCrop> { it.plot.x }.thenBy { it.plot.z }.thenBy { it.plot.y }.thenBy { it.crop },
                ),
                sequence + type.ordinal * 37L,
            )
        }
        return when (type) {
            FarmIncidentType.GIANT_CROP -> {
                val supportedCandidates = giantCandidates.filter { FarmGiantCropBlueprint.supports(it.crop) }
                val chosen = FarmGiantCropCandidateSelector.select(
                    supportedCandidates,
                    sequence,
                    maxChecks = supportedCandidates.size.coerceAtLeast(1),
                ) { null }.candidate ?: return null
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
                val blockages = projectChannelGates(
                    interpolate(source, target, channelBlockages),
                    nightPatrolPlots.ifEmpty { listOf(anchor) },
                )
                if (blockages.isEmpty()) return null
                FarmSpecialIncidentPlan(
                    FarmSpecialIncidentState(
                        points = blockages,
                        plots = listOf(anchor),
                        solution = blockages.indices.toSet(),
                    ),
                    blockages.size,
                )
            }
            FarmIncidentType.NIGHT_SHIFT -> FarmSpacedPlotSelector.select(
                candidates,
                nightCropPlacements,
                nightCropMinSpacing,
            ).values
                .takeIf(List<FarmMatureCrop>::isNotEmpty)?.let { chosen ->
                    val patrolCandidates = rotate(
                        nightPatrolPlots.distinct().sortedWith(
                            compareBy<FarmPlotPosition> { it.x }.thenBy { it.z }.thenBy { it.y },
                        ).map { FarmMatureCrop(it, "") },
                        sequence + 911L,
                    )
                    val patrols = FarmSpacedPlotSelector.select(
                        patrolCandidates,
                        nightPatrols,
                        nightPatrolMinSpacing,
                    ).values.map { candidate ->
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
            FarmIncidentType.PESTS, FarmIncidentType.DROUGHT, FarmIncidentType.BIRDS, FarmIncidentType.FOOD_DELIVERY,
            FarmIncidentType.PROCESSING,
            FarmIncidentType.BARN_FIRE,
            FarmIncidentType.FROST,
            -> null
        }
    }

    /** Projects every gate onto a distinct indexed bed so persisted or configured underground points stay visible. */
    fun projectChannelGates(
        points: Collection<FarmPointPosition>,
        surfacePlots: Collection<FarmPlotPosition>,
    ): List<FarmPointPosition> {
        val available = surfacePlots.distinct().toMutableSet()
        return points.mapNotNull { point ->
            val selected = available.asSequence()
                .filter { it.world == point.world }
                .minWithOrNull(
                    compareBy<FarmPlotPosition> { plot ->
                        val dx = plot.x + 0.5 - point.x
                        val dz = plot.z + 0.5 - point.z
                        dx * dx + dz * dz
                    }.thenBy { it.x }.thenBy { it.z }.thenBy { it.y },
                ) ?: return@mapNotNull null
            available.remove(selected)
            FarmPointPosition(selected.world, selected.x + 0.5, selected.y + 1.05, selected.z + 0.5)
        }
    }

    private fun <T> rotate(values: List<T>, salt: Long): List<T> {
        if (values.isEmpty()) return values
        val mixed = FarmSpatialSeed.mix(salt, 0x5350454349414cL)
        val offset = java.lang.Math.floorMod((mixed xor (mixed ushr 32)).toInt(), values.size)
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

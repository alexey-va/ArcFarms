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

internal const val FARM_CHANNEL_ROUTE_NAME = "drainage-v3"

object FarmChannelMarkerPolicy {
    fun showsEarthGuidance(index: Int, solved: Boolean, stride: Int): Boolean {
        require(index >= 0)
        require(stride >= 1)
        return !solved && index % stride == 0
    }
}

object FarmChannelTrailPolicy {
    fun visibleLinks(segmentCount: Int, visible: Set<Int>): List<Pair<Int, Int>> {
        require(segmentCount >= 0)
        require(visible.all { it in 0 until segmentCount })
        return (0 until (segmentCount - 1)).mapNotNull { from ->
            val to = from + 1
            (from to to).takeIf { from in visible && to in visible }
        }
    }
}

object FarmSpecialIncidentPlanner {
    fun plan(
        type: FarmIncidentType,
        sequence: Long,
        matureCrops: Collection<FarmMatureCrop>,
        channelPlots: Collection<FarmPlotPosition> = matureCrops.map(FarmMatureCrop::plot),
        giantCandidates: Collection<FarmGiantCropCandidate> = emptyList(),
        nightPatrolPlots: Collection<FarmPlotPosition> = matureCrops.map(FarmMatureCrop::plot),
        fallbackPlot: FarmPlotPosition?,
        channelBlockages: Int,
        nightCropPlacements: Int,
        nightCropTarget: Int,
        nightCropMinSpacing: Double,
        nightPatrols: Int,
        nightPatrolMinSpacing: Double,
        marketCrops: Int,
    ): FarmSpecialIncidentPlan? {
        require(channelBlockages in 1..128)
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
                val route = planChannelRoute(
                    channelPlots.ifEmpty { matureCrops.map(FarmMatureCrop::plot) },
                    channelBlockages,
                    sequence,
                )
                if (route.size < MIN_CHANNEL_SEGMENTS) return null
                FarmSpecialIncidentPlan(
                    FarmSpecialIncidentState(
                        points = route.map { plot ->
                            FarmPointPosition(plot.world, plot.x + 0.5, plot.y + 1.05, plot.z + 0.5)
                        },
                        plots = route,
                        routeName = FARM_CHANNEL_ROUTE_NAME,
                        solution = setOf(0),
                        active = setOf(0),
                    ),
                    route.size,
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
            FarmIncidentType.BOAR_BREAKOUT,
            FarmIncidentType.RIVAL_RAID,
            FarmIncidentType.TORNADO,
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

    /** Builds a deterministic but sequence-specific cardinal trench whose first bed is its water source. */
    fun planChannelRoute(
        surfacePlots: Collection<FarmPlotPosition>,
        requestedSegments: Int,
        sequence: Long,
    ): List<FarmPlotPosition> {
        require(requestedSegments in 1..128)
        val plots = surfacePlots.distinct().groupBy(FarmPlotPosition::world).entries
            .maxWithOrNull(compareBy<Map.Entry<String, List<FarmPlotPosition>>> { it.value.size }.thenByDescending { it.key })
            ?.value.orEmpty()
        if (plots.isEmpty()) return emptyList()
        val orderedStarts = plots.sortedWith(
            compareBy(FarmPlotPosition::x).thenBy(FarmPlotPosition::z).thenBy(FarmPlotPosition::y),
        )
        val horizontal = plots.groupBy { it.x to it.z }
        val startCandidates = rotate(orderedStarts, sequence + CHANNEL_START_SALT)
        var longest = emptyList<FarmPlotPosition>()
        startCandidates.forEachIndexed { attempt, start ->
            val route = wanderingChannelRoute(start, horizontal, requestedSegments, sequence + attempt * 97L)
            if (route.size == requestedSegments) return route
            if (route.size > longest.size) longest = route
        }
        return longest.takeIf { it.size >= MIN_CHANNEL_SEGMENTS }.orEmpty()
    }

    private fun wanderingChannelRoute(
        start: FarmPlotPosition,
        horizontal: Map<Pair<Int, Int>, List<FarmPlotPosition>>,
        requestedSegments: Int,
        sequence: Long,
    ): List<FarmPlotPosition> {
        val path = mutableListOf(start)
        val visited = mutableSetOf(start)
        var probes = 0

        fun search(): Boolean {
            if (path.size == requestedSegments) return true
            if (++probes > MAX_CHANNEL_SEARCH_PROBES) return false
            val current = path.last()
            val directionSalt = FarmSpatialSeed.mix(
                sequence + path.size * 131L,
                current.x.toLong() * 73_856_093L xor current.z.toLong() * 19_349_663L,
            )
            val previousDirection = path.takeIf { it.size >= 2 }?.let {
                val previous = it[it.lastIndex - 1]
                current.x - previous.x to current.z - previous.z
            }
            val directions = rotate(CARDINAL_DIRECTIONS, directionSalt).let { shuffled ->
                if (previousDirection == null) shuffled else listOf(previousDirection) + shuffled.filter { it != previousDirection }
            }
            val neighbours = directions.mapNotNull { (dx, dz) ->
                horizontal[current.x + dx to current.z + dz]
                    .orEmpty()
                    .asSequence()
                    .filter { candidate ->
                        candidate !in visited && candidate.y <= current.y && current.y - candidate.y <= 1 &&
                            visited.asSequence().filter { it != current }.none { previous ->
                                kotlin.math.abs(previous.x - candidate.x) +
                                    kotlin.math.abs(previous.z - candidate.z) == 1
                            }
                    }
                    .minWithOrNull(compareBy<FarmPlotPosition> { kotlin.math.abs(it.y - current.y) }.thenBy { it.y })
            }
            neighbours.forEach { next ->
                visited += next
                path += next
                if (search()) return true
                path.removeAt(path.lastIndex)
                visited -= next
            }
            return false
        }

        search()
        return path.toList()
    }

    private fun <T> rotate(values: List<T>, salt: Long): List<T> {
        if (values.isEmpty()) return values
        val mixed = FarmSpatialSeed.mix(salt, 0x5350454349414cL)
        val offset = java.lang.Math.floorMod((mixed xor (mixed ushr 32)).toInt(), values.size)
        return values.drop(offset) + values.take(offset)
    }

    private const val CHANNEL_START_SALT = 0x4348414e53544152L
    private const val MAX_CHANNEL_SEARCH_PROBES = 2_048

    private const val MIN_CHANNEL_SEGMENTS = 4
    private val CARDINAL_DIRECTIONS = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
}

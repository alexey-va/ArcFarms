package ru.ruscrafting.farms.domain

import java.util.UUID

const val MAX_FARM_PATCH_PLOTS = 6_144
const val MAX_FARM_INCIDENTS = 8

enum class FarmPhase {
    IDLE,
    PREPARATION,
    PLANTING,
    CARE,
    HARVESTING,
    INCIDENT,
    DELIVERY,
    COOLDOWN,
}

enum class FarmContractRarity {
    COMMON,
    RARE,
}

enum class FarmCustomerType {
    BAKER,
    MINE_SUPPLIER,
    MARKET_TRADER,
}

data class FarmPlotPosition(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    init {
        require(DomainIdentifiers.isWorld(world)) { "Invalid farm plot world: $world" }
        require(x in -30_000_000..30_000_000 && z in -30_000_000..30_000_000) {
            "Farm plot position is outside the world border"
        }
        require(y in -4_096..4_096) { "Farm plot height is invalid" }
    }
}

enum class FarmIncidentType {
    PESTS,
    DROUGHT,
    BIRDS,
    FOOD_DELIVERY,
    GIANT_CROP,
    CHANNELS,
    NIGHT_SHIFT,
    MARKET,
    PROCESSING,
    BARN_FIRE,
}

enum class FarmProcessingStage {
    LOADING,
    OPERATING,
    PACKING,
}

/** Durable progress only. Carriers and display UUIDs deliberately remain runtime-owned. */
data class FarmProcessingState(
    val crop: String,
    val stage: FarmProcessingStage = FarmProcessingStage.LOADING,
    val inputLoaded: Int = 0,
    val inputRequired: Int,
    val cyclesCompleted: Int = 0,
    val cyclesRequired: Int,
    val outputDelivered: Int = 0,
    val outputRequired: Int,
) {
    init {
        require(DomainIdentifiers.isContent(crop)) { "Invalid processing crop: $crop" }
        require(inputRequired in 1..16 && inputLoaded in 0..inputRequired) { "Invalid processing input progress" }
        require(cyclesRequired in 1..32 && cyclesCompleted in 0..cyclesRequired) { "Invalid processing cycle progress" }
        require(outputRequired in 1..16 && outputDelivered in 0..outputRequired) { "Invalid processing output progress" }
    }

    val completed: Int get() = inputLoaded + cyclesCompleted + outputDelivered
    val required: Int get() = inputRequired + cyclesRequired + outputRequired
}

enum class FarmCareType {
    SEEDER,
    WEEDS,
    IRRIGATION,
    POLLINATION,
    APPLE_HARVEST,
    STORM_COVERS,
    SCARECROWS,
    ANIMAL_RESCUE,
    DISEASE,
    MOLES,
}

enum class FarmSeederStage {
    TILLING,
    PLANTING,
}

enum class FarmCareRole {
    SEEDER_HORSE,
    SEEDER_WAYPOINT,
    WEED_ROOT,
    VALVE,
    HIVE,
    FLOWER_PATCH,
    APPLE,
    COVER_ANCHOR,
    SCARECROW,
    ANIMAL,
    PEN,
    DISEASED_CROP,
    MOLE_MOUND,
}

data class FarmCareTarget(
    val id: Int,
    val role: FarmCareRole,
    val position: FarmPointPosition,
    val progress: Int = 0,
    val required: Int = 1,
) {
    init {
        require(id in 0..511) { "Farm care target id is invalid" }
        require(progress in 0..required) { "Farm care target progress is invalid" }
        require(required in 1..8) { "Farm care target requirement is invalid" }
    }

    val complete: Boolean get() = progress >= required
}

data class FarmDeliveryPosition(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(DomainIdentifiers.isWorld(world)) { "Invalid delivery world: $world" }
        require(listOf(x, y, z).all(Double::isFinite)) { "Delivery position must be finite" }
    }
}

data class FarmPestNest(
    val position: FarmPlotPosition,
    val health: Int,
    val spawned: Int = 0,
) {
    init {
        require(health in 1..20) { "Farm pest nest health is invalid" }
        require(spawned in 0..64) { "Farm pest nest spawn count is invalid" }
    }
}

data class FarmCropDamage(
    val position: FarmPlotPosition,
    val crop: String,
) {
    init {
        require(DomainIdentifiers.isContent(crop)) { "Invalid damaged crop: $crop" }
    }
}

data class FarmSpecialIncidentState(
    val points: List<FarmPointPosition> = emptyList(),
    val plots: List<FarmPlotPosition> = emptyList(),
    val crop: String? = null,
    val routeName: String? = null,
    val solution: Set<Int> = emptySet(),
    val active: Set<Int> = emptySet(),
    val marketAccepted: Boolean = false,
    val marketDeadlineAt: Long = 0,
) {
    init {
        require(points.size <= 16) { "Farm special incident has too many points" }
        require(plots.size <= 128 && plots.distinct().size == plots.size) {
            "Farm special incident has invalid plots"
        }
        crop?.let { require(DomainIdentifiers.isContent(it)) { "Invalid special incident crop: $it" } }
        routeName?.let { require(DomainIdentifiers.isOrder(it)) { "Invalid special incident route: $it" } }
        require(marketDeadlineAt >= 0) { "Farm market deadline is invalid" }
        val gateRange = points.indices
        require(solution.all(gateRange::contains) && active.all(gateRange::contains)) {
            "Farm channel state references an unknown blockage"
        }
    }
}

data class FarmOrder(
    val id: String,
    val required: Map<String, Int>,
    val rarity: FarmContractRarity = FarmContractRarity.COMMON,
    val careTypes: List<FarmCareType> = FarmCareType.entries.filterNot { it == FarmCareType.SEEDER },
    val incidentTypes: List<FarmIncidentType> = FarmIncidentType.entries,
    val customerType: FarmCustomerType = FarmCustomerType.MARKET_TRADER,
    val cartLoadMaterial: String = required.keys.first(),
    val cartLoadCustomModelData: Int = 0,
) {
    init {
        require(DomainIdentifiers.isOrder(id)) { "Invalid farm order id: $id" }
        require(required.isNotEmpty()) { "Farm order $id must require crops" }
        require(required.size <= 12) { "Farm order $id has too many crops" }
        require(required.values.all { it in 1..100_000 }) { "Farm order $id has an invalid crop quota" }
        require(careTypes.isNotEmpty() && FarmCareType.SEEDER !in careTypes) { "Farm order $id has invalid care types" }
        require(careTypes.distinct().size == careTypes.size) { "Farm order $id duplicates a care type" }
        require(incidentTypes.isNotEmpty() && incidentTypes.distinct().size == incidentTypes.size) {
            "Farm order $id has invalid incident types"
        }
        require(DomainIdentifiers.isContent(cartLoadMaterial)) { "Farm order $id has an invalid cart load material" }
        require(cartLoadCustomModelData in 0..2_000_000) { "Farm order $id has an invalid cart load model" }
    }

    val totalRequired: Int = required.values.sum()
}

data class FarmRules(
    val incidentTriggerPercents: List<Int>,
    val incidentQuota: Int,
    val cooldownMillis: Long,
    val droughtQuota: Int = incidentQuota,
    val incidentCountMin: Int = incidentTriggerPercents.size,
    val incidentCountMax: Int = incidentTriggerPercents.size,
) {
    init {
        require(incidentTriggerPercents.isNotEmpty() && incidentTriggerPercents.size <= MAX_FARM_INCIDENTS)
        require(incidentTriggerPercents.all { it in 1..99 })
        require(incidentTriggerPercents == incidentTriggerPercents.distinct().sorted())
        require(incidentCountMin in 1..incidentTriggerPercents.size)
        require(incidentCountMax in incidentCountMin..incidentTriggerPercents.size)
        require(incidentQuota in 1..64)
        require(cooldownMillis in 0..3_600_000)
        require(droughtQuota in 1..64)
    }

    fun incidentTargetCount(sequence: Long): Int {
        val span = incidentCountMax - incidentCountMin + 1
        return incidentCountMin + java.lang.Math.floorMod((sequence xor (sequence ushr 32)).toInt(), span)
    }

    fun incidentTriggers(sequence: Long): List<Int> {
        val count = incidentTargetCount(sequence)
        if (count == incidentTriggerPercents.size) return incidentTriggerPercents
        if (count == 1) return listOf(incidentTriggerPercents[incidentTriggerPercents.lastIndex / 2])
        return (0 until count).map { index ->
            val sourceIndex = kotlin.math.round(
                index * incidentTriggerPercents.lastIndex.toDouble() / (count - 1),
            ).toInt()
            incidentTriggerPercents[sourceIndex]
        }
    }
}

data class FarmShiftState(
    val phase: FarmPhase = FarmPhase.IDLE,
    val sequence: Long = 0,
    /** Monotonic objective nonce. Unlike [sequence], it advances for forced admin events inside one shift. */
    val placementSequence: Long = 0,
    val orderId: String? = null,
    val progress: Map<String, Int> = emptyMap(),
    val preparationPatch: List<FarmPlotPosition> = emptyList(),
    val preparationCrop: String? = null,
    val preparationReleased: Boolean = false,
    val tilledPlots: Set<FarmPlotPosition> = emptySet(),
    val plantedPlots: Set<FarmPlotPosition> = emptySet(),
    val preparationProgress: Int = 0,
    val plantingProgress: Int = 0,
    val preparationRequired: Int = 0,
    val harvestCheckpoint: Int = 0,
    val harvestMilestone: Int = 0,
    val careType: FarmCareType? = null,
    val seederStage: FarmSeederStage? = null,
    val careTargets: List<FarmCareTarget> = emptyList(),
    val careGoal: Int? = null,
    val incidentCrop: String? = null,
    val incidentType: FarmIncidentType? = null,
    val incidentProgress: Int = 0,
    val incidentRequired: Int = 0,
    val incidentResolved: Boolean = false,
    val incidentsResolved: Int = 0,
    val droughtPlots: Set<FarmPlotPosition> = emptySet(),
    val droughtDamagedPlots: Set<FarmPlotPosition> = emptySet(),
    val pestNestsInitialized: Boolean = false,
    val pestNests: List<FarmPestNest> = emptyList(),
    val pestAlive: Int = 0,
    val pestDamagedCrops: List<FarmCropDamage> = emptyList(),
    val diseaseDamagedCrops: List<FarmCropDamage>? = emptyList(),
    val specialIncident: FarmSpecialIncidentState? = null,
    val processing: FarmProcessingState? = null,
    val specialDamagedCrops: List<FarmCropDamage> = emptyList(),
    val rewardMoneyBonusPercent: Int = 0,
    val startedAt: Long = 0,
    val cooldownEndsAt: Long = 0,
    val deliveryPosition: FarmDeliveryPosition? = null,
    val deliveredCrates: Set<Int> = emptySet(),
    val outcome: ShiftOutcome = ShiftOutcome.NONE,
    val contributors: Map<UUID, Int> = emptyMap(),
) {
    init {
        require(placementSequence in 0 until Long.MAX_VALUE) { "Farm placement sequence is invalid" }
    }

    fun completed(order: FarmOrder): Int = order.required.entries.sumOf { (crop, amount) ->
        (progress[crop] ?: 0).coerceAtMost(amount)
    }

    fun progressRatio(order: FarmOrder): Double = completed(order).toDouble() / order.totalRequired.toDouble()

    fun careProgress(): Int = careTargets.sumOf(FarmCareTarget::progress).coerceAtMost(careRequired())

    fun careRequired(): Int = careGoal ?: careTargets.sumOf(FarmCareTarget::required)
}

fun FarmShiftState.seederStage(): FarmSeederStage? {
    if (phase != FarmPhase.CARE || careType != FarmCareType.SEEDER) return null
    return seederStage ?: if (plantedPlots.isNotEmpty()) FarmSeederStage.PLANTING else FarmSeederStage.TILLING
}

/** Advances the durable spatial nonce without allowing a corrupt negative wraparound. */
fun FarmShiftState.nextPlacementSequence(): Long = if (placementSequence == Long.MAX_VALUE) 1L else placementSequence + 1L

object FarmShiftEngine {
    fun start(
        current: FarmShiftState,
        order: FarmOrder,
        patch: List<FarmPlotPosition>,
        preparationCrop: String,
        now: Long,
        completionPercent: Int = 100,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.IDLE) return EngineResult(current, false)
        require(patch.isNotEmpty() && patch.size <= MAX_FARM_PATCH_PLOTS) {
            "Farm preparation patch must contain 1..$MAX_FARM_PATCH_PLOTS plots"
        }
        require(patch.distinct().size == patch.size) { "Farm preparation patch contains duplicate plots" }
        require(patch.map(FarmPlotPosition::world).distinct().size == 1) { "Farm preparation patch crosses worlds" }
        require(preparationCrop in order.required) { "Farm preparation crop is outside order ${order.id}" }
        val next = FarmShiftState(
            phase = FarmPhase.PREPARATION,
            sequence = current.sequence + 1,
            placementSequence = current.placementSequence,
            orderId = order.id,
            progress = order.required.keys.associateWith { 0 },
            preparationPatch = patch,
            preparationCrop = preparationCrop,
            preparationRequired = FarmFieldQuota.required(patch.size, completionPercent),
            startedAt = now,
        )
        return EngineResult(next, true, events = listOf(ShiftEvent.STARTED))
    }

    fun till(
        current: FarmShiftState,
        plot: FarmPlotPosition,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        if (
            current.phase != FarmPhase.PREPARATION || plot !in current.preparationPatch ||
            plot in current.tilledPlots || current.preparationProgress >= current.preparationRequired
        ) {
            return EngineResult(current, false)
        }
        val progress = current.preparationProgress + 1
        val completed = progress >= current.preparationRequired
        val tilled = if (completed) current.preparationPatch.toSet() else current.tilledPlots + plot
        val state = current.copy(
            phase = if (completed) FarmPhase.PLANTING else FarmPhase.PREPARATION,
            tilledPlots = tilled,
            preparationProgress = tilled.size,
            contributors = incrementContribution(current.contributors, playerId, 1),
        )
        return EngineResult(
            state,
            true,
            contribution = 1,
            events = buildList {
                add(ShiftEvent.PREPARATION_PROGRESS)
                if (completed) add(ShiftEvent.PLANTING_STARTED)
            },
        )
    }

    fun plant(
        current: FarmShiftState,
        plot: FarmPlotPosition,
        crop: String,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        if (
            current.phase != FarmPhase.PLANTING || crop != current.preparationCrop ||
            plot !in current.preparationPatch || plot !in current.tilledPlots || plot in current.plantedPlots ||
            current.plantingProgress >= current.preparationRequired
        ) {
            return EngineResult(current, false)
        }
        val progress = current.plantingProgress + 1
        val completed = progress >= current.preparationRequired
        val tilled = if (completed) current.preparationPatch.toSet() else current.tilledPlots
        val planted = if (completed) tilled else current.plantedPlots + plot
        val state = current.copy(
            phase = if (completed) FarmPhase.HARVESTING else FarmPhase.PLANTING,
            tilledPlots = tilled,
            plantedPlots = planted,
            preparationProgress = tilled.size,
            plantingProgress = planted.size,
            contributors = incrementContribution(current.contributors, playerId, 1),
        )
        return EngineResult(
            state,
            true,
            contribution = 1,
            events = buildList {
                add(ShiftEvent.PLANTING_PROGRESS)
                if (completed) add(ShiftEvent.PREPARATION_COMPLETED)
            },
        )
    }

    fun harvest(
        current: FarmShiftState,
        order: FarmOrder,
        rules: FarmRules,
        crop: String,
        playerId: UUID,
        now: Long,
        incidentType: FarmIncidentType = FarmIncidentType.PESTS,
    ): EngineResult<FarmShiftState> {
        val advanced = tick(current, order, now)
        var state = advanced.state
        val events = advanced.events.toMutableList()
        if (state.phase !in setOf(FarmPhase.HARVESTING, FarmPhase.INCIDENT)) {
            return EngineResult(state, false, events = events)
        }
        if (state.phase == FarmPhase.INCIDENT) return EngineResult(state, false, events = events)
        if (FarmIncidentRecovery.pending(state)) return EngineResult(state, false, events = events)
        val required = order.required[crop] ?: return EngineResult(state, false, events = events)
        val before = state.progress[crop] ?: 0
        if (before >= required) return EngineResult(state, false, events = events)

        val after = (before + 1).coerceAtMost(required)
        val orderDelta = after - before
        val contribution = orderDelta
        state = state.copy(
            progress = state.progress + (crop to after),
            contributors = incrementContribution(state.contributors, playerId, contribution),
        )
        events += ShiftEvent.PROGRESS

        val milestone = FarmContractPlanner.harvestMilestone(state.completed(order), order.totalRequired)
        if (milestone > state.harvestMilestone) {
            state = state.copy(harvestMilestone = milestone)
            events += ShiftEvent.HARVEST_MILESTONE
        }

        val checkpoint = FarmContractPlanner.harvestCheckpoint(state.completed(order), order.totalRequired)
        if (checkpoint > state.harvestCheckpoint) {
            state = state.copy(harvestCheckpoint = checkpoint)
            if (checkpoint < 10) events += ShiftEvent.HARVEST_CHECKPOINT
        }

        if (state.completed(order) >= order.totalRequired && state.phase != FarmPhase.INCIDENT) {
            state = state.copy(
                phase = FarmPhase.DELIVERY,
                incidentCrop = null,
                incidentType = null,
                droughtPlots = emptySet(),
                droughtDamagedPlots = emptySet(),
                pestNests = emptyList(),
                pestNestsInitialized = false,
                pestAlive = 0,
                pestDamagedCrops = emptyList(),
                specialIncident = null,
                processing = null,
                specialDamagedCrops = emptyList(),
                deliveryPosition = null,
                deliveredCrates = emptySet(),
            )
            events += ShiftEvent.DELIVERY_STARTED
            return EngineResult(state, true, contribution, events)
        }

        val nextTrigger = rules.incidentTriggers(state.sequence).getOrNull(state.incidentsResolved)
        val triggerReached = nextTrigger != null && state.completed(order) * 100 >= order.totalRequired * nextTrigger
        if (state.incidentCrop == null && triggerReached) {
            val incidentCrop = remainingCrop(state, order)
            if (incidentCrop != null) {
                state = state.copy(
                    phase = FarmPhase.INCIDENT,
                    placementSequence = state.nextPlacementSequence(),
                    incidentCrop = incidentCrop,
                    incidentType = incidentType,
                    incidentProgress = 0,
                    incidentRequired = if (incidentType == FarmIncidentType.DROUGHT) rules.droughtQuota else rules.incidentQuota,
                    incidentResolved = false,
                    droughtPlots = emptySet(),
                    droughtDamagedPlots = emptySet(),
                    pestNests = emptyList(),
                    pestNestsInitialized = false,
                    pestAlive = 0,
                    pestDamagedCrops = emptyList(),
                    specialIncident = null,
                    processing = null,
                    specialDamagedCrops = emptyList(),
                )
                events += ShiftEvent.INCIDENT_STARTED
            }
        }
        return EngineResult(state, true, contribution, events)
    }

    fun startCare(
        current: FarmShiftState,
        type: FarmCareType,
        targets: List<FarmCareTarget>,
        goal: Int = targets.sumOf(FarmCareTarget::required),
    ): EngineResult<FarmShiftState> {
        val validSource = if (type == FarmCareType.SEEDER) {
            current.phase == FarmPhase.PREPARATION && current.preparationProgress == 0 && current.plantingProgress == 0
        } else {
            current.phase == FarmPhase.HARVESTING
        }
        if (!validSource || current.careType != null) {
            return EngineResult(current, false)
        }
        require(targets.isNotEmpty() && targets.size <= 512) { "Farm care scene must contain 1..512 targets" }
        require(targets.map(FarmCareTarget::id).distinct().size == targets.size) { "Farm care scene contains duplicate target ids" }
        require(targets.map { it.position.world }.distinct().size == 1) { "Farm care scene crosses worlds" }
        require(goal in 1..targets.sumOf(FarmCareTarget::required)) {
            "Farm care goal must fit the available target progress"
        }
        if (type == FarmCareType.SEEDER) {
            require(targets.count { it.role == FarmCareRole.SEEDER_HORSE } == 1) { "Seeder scene must contain one horse" }
        }
        return EngineResult(
            current.copy(
                phase = FarmPhase.CARE,
                placementSequence = current.nextPlacementSequence(),
                careType = type,
                seederStage = if (type == FarmCareType.SEEDER) FarmSeederStage.TILLING else null,
                careTargets = targets,
                careGoal = goal,
            ),
            true,
            events = listOf(ShiftEvent.CARE_STARTED),
        )
    }

    fun startSeeder(
        current: FarmShiftState,
        targetId: Int,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.CARE || current.careType != FarmCareType.SEEDER) {
            return EngineResult(current, false)
        }
        val target = current.careTargets.firstOrNull { it.id == targetId } ?: return EngineResult(current, false)
        if (target.role != FarmCareRole.SEEDER_HORSE || target.complete) return EngineResult(current, false)
        val targets = current.careTargets.map { candidate ->
            if (candidate.id == targetId) candidate.copy(progress = candidate.progress + 1) else candidate
        }
        return EngineResult(
            current.copy(careTargets = targets),
            true,
            events = listOf(ShiftEvent.CARE_PROGRESS),
        )
    }

    fun workSeeder(
        current: FarmShiftState,
        processed: Set<FarmPlotPosition>,
        playerId: UUID,
    ): EngineResult<FarmShiftState> = workSeeder(current, processed, setOf(playerId))

    fun workSeeder(
        current: FarmShiftState,
        processed: Set<FarmPlotPosition>,
        playerIds: Set<UUID>,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.CARE || current.careType != FarmCareType.SEEDER) {
            return EngineResult(current, false)
        }
        require(playerIds.isNotEmpty()) { "Seeder work must have at least one mounted participant" }
        require(processed.all(current.preparationPatch::contains)) { "Seeder processed outside the preparation patch" }
        return when (requireNotNull(current.seederStage())) {
            FarmSeederStage.TILLING -> {
                val remaining = (current.preparationRequired - current.tilledPlots.size).coerceAtLeast(0)
                val added = (processed - current.tilledPlots).sortedWith(FARM_PLOT_ORDER).take(remaining).toSet()
                if (added.isEmpty()) return EngineResult(current, false)
                val worked = current.tilledPlots + added
                val complete = worked.size >= current.preparationRequired
                val tilled = if (complete) current.preparationPatch.toSet() else worked
                EngineResult(
                    current.copy(
                        tilledPlots = tilled,
                        preparationProgress = tilled.size,
                        seederStage = if (complete) FarmSeederStage.PLANTING else FarmSeederStage.TILLING,
                        careTargets = if (complete) {
                            current.careTargets.filter { it.role == FarmCareRole.SEEDER_HORSE }
                        } else {
                            current.careTargets
                        },
                        contributors = incrementContributions(current.contributors, playerIds, added.size),
                    ),
                    true,
                    contribution = added.size,
                    events = if (complete) {
                        listOf(ShiftEvent.SEEDER_PLANTING_STARTED)
                    } else {
                        listOf(ShiftEvent.SEEDER_PROGRESS)
                    },
                    contributionCredits = playerIds.associateWith { added.size },
                )
            }
            FarmSeederStage.PLANTING -> {
                require(processed.all(current.tilledPlots::contains)) { "Seeder planted an untilled plot" }
                val remaining = (current.preparationRequired - current.plantedPlots.size).coerceAtLeast(0)
                val added = (processed - current.plantedPlots).sortedWith(FARM_PLOT_ORDER).take(remaining).toSet()
                if (added.isEmpty()) return EngineResult(current, false)
                val worked = current.plantedPlots + added
                val complete = worked.size >= current.preparationRequired
                val planted = if (complete) current.preparationPatch.toSet() else worked
                EngineResult(
                    current.copy(
                        phase = if (complete) FarmPhase.HARVESTING else FarmPhase.CARE,
                        plantedPlots = planted,
                        plantingProgress = planted.size,
                        seederStage = if (complete) null else FarmSeederStage.PLANTING,
                        careTargets = if (complete) emptyList() else current.careTargets,
                        contributors = incrementContributions(current.contributors, playerIds, added.size),
                    ),
                    true,
                    contribution = added.size,
                    events = if (complete) {
                        listOf(ShiftEvent.CARE_RESOLVED)
                    } else {
                        listOf(ShiftEvent.SEEDER_PROGRESS)
                    },
                    contributionCredits = playerIds.associateWith { added.size },
                )
            }
        }
    }

    private val FARM_PLOT_ORDER = compareBy<FarmPlotPosition> { it.x }
        .thenBy { it.z }
        .thenBy { it.y }

    fun advanceCare(
        current: FarmShiftState,
        targetId: Int,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.CARE) return EngineResult(current, false)
        val target = current.careTargets.firstOrNull { it.id == targetId } ?: return EngineResult(current, false)
        if (target.complete) return EngineResult(current, false)
        val targets = current.careTargets.map { candidate ->
            if (candidate.id == targetId) candidate.copy(progress = candidate.progress + 1) else candidate
        }
        val complete = targets.sumOf(FarmCareTarget::progress) >= current.careRequired()
        return EngineResult(
            current.copy(
                phase = if (complete) FarmPhase.HARVESTING else FarmPhase.CARE,
                careTargets = targets,
                contributors = incrementContribution(current.contributors, playerId, 1),
            ),
            true,
            contribution = 1,
            events = listOf(ShiftEvent.CARE_PROGRESS) + if (complete) listOf(ShiftEvent.CARE_RESOLVED) else emptyList(),
        )
    }

    fun spreadDisease(
        current: FarmShiftState,
        target: FarmCareTarget,
        maxSpots: Int,
    ): EngineResult<FarmShiftState> {
        require(maxSpots in 1..32) { "Disease spread cap is invalid" }
        if (current.phase != FarmPhase.CARE || current.careType != FarmCareType.DISEASE) {
            return EngineResult(current, false)
        }
        if (target.role != FarmCareRole.DISEASED_CROP || current.careTargets.any { it.id == target.id }) {
            return EngineResult(current, false)
        }
        val diseaseTargets = current.careTargets.filter { it.role == FarmCareRole.DISEASED_CROP }
        if (diseaseTargets.size >= maxSpots) {
            return EngineResult(current, false)
        }
        if (current.careTargets.firstOrNull()?.position?.world != target.position.world) {
            return EngineResult(current, false)
        }
        return EngineResult(
            current.copy(
                careTargets = current.careTargets + target,
                careGoal = current.careRequired() + target.required,
            ),
            true,
        )
    }

    fun expireDisease(
        current: FarmShiftState,
        targetId: Int,
        replacement: FarmCareTarget?,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.CARE || current.careType != FarmCareType.DISEASE) {
            return EngineResult(current, false)
        }
        val expired = current.careTargets.firstOrNull {
            it.id == targetId && it.role == FarmCareRole.DISEASED_CROP && !it.complete
        } ?: return EngineResult(current, false)
        if (replacement != null) {
            require(replacement.role == FarmCareRole.DISEASED_CROP) { "Disease replacement has the wrong role" }
            require(current.careTargets.none { it.id == replacement.id }) { "Disease replacement id is already in use" }
            require(replacement.position.world == expired.position.world) { "Disease replacement crosses worlds" }
        }
        val targets = current.careTargets.filterNot { it.id == targetId } + listOfNotNull(replacement)
        val goal = current.careRequired() - expired.required + (replacement?.required ?: 0)
        val complete = targets.isEmpty() || targets.sumOf(FarmCareTarget::progress) >= goal
        return EngineResult(
            current.copy(
                phase = if (complete) FarmPhase.HARVESTING else FarmPhase.CARE,
                careTargets = targets,
                careGoal = goal.takeIf { it > 0 },
            ),
            true,
            events = if (complete) listOf(ShiftEvent.CARE_RESOLVED) else emptyList(),
        )
    }

    fun normalizeDisease(current: FarmShiftState): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.CARE || current.careType != FarmCareType.DISEASE) {
            return EngineResult(current, false)
        }
        if (current.careTargets.all { it.required == 1 && it.progress <= 1 }) return EngineResult(current, false)
        val targets = current.careTargets.map { target ->
            if (target.role == FarmCareRole.DISEASED_CROP) {
                target.copy(required = 1, progress = target.progress.coerceAtMost(1))
            } else target
        }
        val goal = targets.sumOf(FarmCareTarget::required)
        val complete = targets.sumOf(FarmCareTarget::progress) >= goal
        return EngineResult(
            current.copy(
                phase = if (complete) FarmPhase.HARVESTING else FarmPhase.CARE,
                careTargets = targets,
                careGoal = goal,
            ),
            true,
            events = if (complete) listOf(ShiftEvent.CARE_RESOLVED) else emptyList(),
        )
    }

    fun defeatPest(
        current: FarmShiftState,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        val actualType = current.incidentType ?: FarmIncidentType.PESTS
        if (current.phase != FarmPhase.INCIDENT || actualType != FarmIncidentType.PESTS || current.pestAlive <= 0) {
            return EngineResult(current, false)
        }
        val state = current.copy(
            incidentProgress = current.incidentProgress + 1,
            pestAlive = current.pestAlive - 1,
            contributors = incrementContribution(current.contributors, playerId, 1),
        )
        return finishPestIncidentIfClear(state, contribution = 1)
    }

    fun damagePestNest(
        current: FarmShiftState,
        position: FarmPlotPosition,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        val actualType = current.incidentType ?: FarmIncidentType.PESTS
        if (current.phase != FarmPhase.INCIDENT || actualType != FarmIncidentType.PESTS) {
            return EngineResult(current, false)
        }
        val nest = current.pestNests.firstOrNull { it.position == position } ?: return EngineResult(current, false)
        if (nest.health > 1) {
            return EngineResult(
                current.copy(pestNests = current.pestNests.map { if (it.position == position) it.copy(health = it.health - 1) else it }),
                true,
            )
        }
        val state = current.copy(
            pestNests = current.pestNests.filterNot { it.position == position },
            incidentProgress = current.incidentProgress + 1,
            contributors = incrementContribution(current.contributors, playerId, 1),
        )
        return finishPestIncidentIfClear(state, contribution = 1)
    }

    fun finishPestIncidentIfClear(
        current: FarmShiftState,
        contribution: Int = 0,
    ): EngineResult<FarmShiftState> {
        val actualType = current.incidentType ?: FarmIncidentType.PESTS
        if (
            current.phase != FarmPhase.INCIDENT || actualType != FarmIncidentType.PESTS ||
            current.pestNests.isNotEmpty() || current.pestAlive > 0
        ) {
            return EngineResult(
                current,
                contribution > 0,
                contribution = contribution,
                events = if (contribution > 0) listOf(ShiftEvent.INCIDENT_PROGRESS) else emptyList(),
            )
        }
        val completed = completeIncident(current, contribution)
        return if (contribution > 0) {
            completed.copy(events = listOf(ShiftEvent.INCIDENT_PROGRESS) + completed.events)
        } else {
            completed
        }
    }

    fun waterDrySoil(
        current: FarmShiftState,
        playerId: UUID,
    ): EngineResult<FarmShiftState> = resolveIncident(
        current,
        FarmIncidentType.DROUGHT,
        playerId,
    )

    private fun resolveIncident(
        current: FarmShiftState,
        expectedType: FarmIncidentType,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        val actualType = current.incidentType ?: FarmIncidentType.PESTS
        if (
            current.phase != FarmPhase.INCIDENT || actualType != expectedType ||
            current.incidentProgress >= current.incidentRequired
        ) {
            return EngineResult(current, false)
        }
        var state = current.copy(
            incidentProgress = current.incidentProgress + 1,
            contributors = incrementContribution(current.contributors, playerId, 1),
        )
        val events = mutableListOf(ShiftEvent.INCIDENT_PROGRESS)
        if (state.incidentProgress >= state.incidentRequired) {
            val completed = completeIncident(state, contribution = 1)
            return completed.copy(events = events + completed.events.filterNot { it == ShiftEvent.INCIDENT_PROGRESS })
        }
        return EngineResult(state, true, contribution = 1, events = events)
    }

    private fun completeIncident(
        current: FarmShiftState,
        contribution: Int,
    ): EngineResult<FarmShiftState> {
        val state = current.copy(
            phase = FarmPhase.HARVESTING,
            incidentResolved = true,
            incidentsResolved = (current.incidentsResolved + 1).coerceAtMost(MAX_FARM_INCIDENTS),
            incidentCrop = null,
            incidentType = null,
            incidentProgress = 0,
            incidentRequired = 0,
            droughtPlots = emptySet(),
            pestNests = emptyList(),
            pestNestsInitialized = false,
            pestAlive = 0,
            specialIncident = null,
            processing = null,
        )
        return EngineResult(state, true, contribution, listOf(ShiftEvent.INCIDENT_RESOLVED))
    }

    fun deliver(
        current: FarmShiftState,
        rules: FarmRules,
        crateIndex: Int,
        requiredCrates: Int,
        playerId: UUID,
        now: Long,
    ): EngineResult<FarmShiftState> {
        require(requiredCrates in 1..8) { "Farm delivery must require 1..8 crates" }
        if (current.phase != FarmPhase.DELIVERY || crateIndex !in 0 until requiredCrates || crateIndex in current.deliveredCrates) {
            return EngineResult(current, false)
        }
        val delivered = current.deliveredCrates + crateIndex
        val completed = delivered.size >= requiredCrates
        return EngineResult(
            current.copy(
                phase = if (completed) FarmPhase.COOLDOWN else FarmPhase.DELIVERY,
                cooldownEndsAt = if (completed) now + rules.cooldownMillis else current.cooldownEndsAt,
                deliveryPosition = if (completed) null else current.deliveryPosition,
                deliveredCrates = delivered,
                outcome = if (completed) ShiftOutcome.COMPLETED else current.outcome,
                contributors = incrementContribution(current.contributors, playerId, 1),
            ),
            true,
            contribution = 1,
            events = listOf(if (completed) ShiftEvent.COMPLETED else ShiftEvent.DELIVERY_PROGRESS),
        )
    }

    fun defeatBird(
        current: FarmShiftState,
        playerId: UUID,
        contribution: Int,
    ): EngineResult<FarmShiftState> {
        require(contribution in 1..8) { "Farm bird contribution is invalid" }
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.BIRDS) {
            return EngineResult(current, false)
        }
        val progress = (current.incidentProgress + 1).coerceAtMost(current.incidentRequired)
        val state = current.copy(
            incidentProgress = progress,
            contributors = incrementContribution(current.contributors, playerId, contribution),
        )
        if (progress >= current.incidentRequired) return completeIncident(state, contribution)
        return EngineResult(state, true, contribution, listOf(ShiftEvent.INCIDENT_PROGRESS))
    }

    fun initializeProcessing(
        current: FarmShiftState,
        crop: String,
        inputRequired: Int,
        cyclesRequired: Int,
        outputRequired: Int,
    ): EngineResult<FarmShiftState> {
        if (
            current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.PROCESSING ||
            current.processing != null
        ) return EngineResult(current, false)
        val processing = FarmProcessingState(
            crop = crop,
            inputRequired = inputRequired,
            cyclesRequired = cyclesRequired,
            outputRequired = outputRequired,
        )
        return EngineResult(
            current.copy(
                incidentProgress = 0,
                incidentRequired = processing.required,
                processing = processing,
            ),
            true,
        )
    }

    fun advanceProcessing(
        current: FarmShiftState,
        playerId: UUID,
        expectedStage: FarmProcessingStage,
    ): EngineResult<FarmShiftState> {
        val processing = current.processing
        if (
            current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.PROCESSING ||
            processing == null || processing.stage != expectedStage
        ) return EngineResult(current, false)

        val advanced = when (expectedStage) {
            FarmProcessingStage.LOADING -> processing.copy(inputLoaded = (processing.inputLoaded + 1).coerceAtMost(processing.inputRequired))
            FarmProcessingStage.OPERATING -> processing.copy(
                cyclesCompleted = (processing.cyclesCompleted + 1).coerceAtMost(processing.cyclesRequired),
            )
            FarmProcessingStage.PACKING -> processing.copy(
                outputDelivered = (processing.outputDelivered + 1).coerceAtMost(processing.outputRequired),
            )
        }
        val nextStage = when {
            advanced.stage == FarmProcessingStage.LOADING && advanced.inputLoaded >= advanced.inputRequired ->
                FarmProcessingStage.OPERATING
            advanced.stage == FarmProcessingStage.OPERATING && advanced.cyclesCompleted >= advanced.cyclesRequired ->
                FarmProcessingStage.PACKING
            else -> advanced.stage
        }
        val nextProcessing = advanced.copy(stage = nextStage)
        val state = current.copy(
            processing = nextProcessing,
            incidentProgress = nextProcessing.completed.coerceAtMost(nextProcessing.required),
            contributors = incrementContribution(current.contributors, playerId, 1),
        )
        if (nextProcessing.outputDelivered >= nextProcessing.outputRequired) {
            val completed = completeIncident(state, contribution = 1)
            return completed.copy(events = listOf(ShiftEvent.INCIDENT_PROGRESS) + completed.events)
        }
        val events = buildList {
            add(ShiftEvent.INCIDENT_PROGRESS)
            if (nextStage != processing.stage) add(ShiftEvent.PROCESSING_STAGE_CHANGED)
        }
        return EngineResult(state, true, contribution = 1, events = events)
    }

    fun initializeBarnFire(
        current: FarmShiftState,
        hotspots: List<FarmPointPosition>,
    ): EngineResult<FarmShiftState> {
        require(hotspots.size in 1..16) { "Farm barn fire must contain 1..16 hotspots" }
        require(hotspots.distinct().size == hotspots.size) { "Farm barn fire contains duplicate hotspots" }
        require(hotspots.map(FarmPointPosition::world).distinct().size == 1) { "Farm barn fire crosses worlds" }
        if (
            current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.BARN_FIRE ||
            current.specialIncident != null
        ) return EngineResult(current, false)
        return EngineResult(
            current.copy(
                incidentProgress = 0,
                incidentRequired = hotspots.size,
                specialIncident = FarmSpecialIncidentState(
                    points = hotspots,
                    active = hotspots.indices.toSet(),
                ),
            ),
            true,
        )
    }

    fun extinguishBarnFire(
        current: FarmShiftState,
        hotspotIndex: Int,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        val incident = current.specialIncident
        if (
            current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.BARN_FIRE ||
            incident == null || hotspotIndex !in incident.active
        ) return EngineResult(current, false)
        val active = incident.active - hotspotIndex
        val progressed = current.copy(
            incidentProgress = (current.incidentProgress + 1).coerceAtMost(current.incidentRequired),
            specialIncident = incident.copy(active = active),
            contributors = incrementContribution(current.contributors, playerId, 1),
        )
        if (active.isEmpty()) {
            val completed = completeIncident(progressed, contribution = 1)
            return completed.copy(events = listOf(ShiftEvent.INCIDENT_PROGRESS) + completed.events)
        }
        return EngineResult(progressed, true, contribution = 1, events = listOf(ShiftEvent.INCIDENT_PROGRESS))
    }

    fun initializeFoodDelivery(current: FarmShiftState, checkpoints: Int, routeName: String = FarmRouteKeys.DEFAULT_NAME): EngineResult<FarmShiftState> {
        require(checkpoints in 2..512) { "Farm food delivery route must contain 2..512 checkpoints" }
        require(DomainIdentifiers.isOrder(routeName)) { "Farm food delivery route name is invalid" }
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.FOOD_DELIVERY ||
            current.specialIncident != null
        ) return EngineResult(current, false)
        return EngineResult(
            current.copy(
                incidentProgress = 1,
                incidentRequired = checkpoints,
                specialIncident = FarmSpecialIncidentState(routeName = routeName),
            ),
            true,
        )
    }

    fun advanceFoodDelivery(
        current: FarmShiftState,
        reachedCheckpoint: Int,
        playerId: UUID,
        completionContribution: Int,
    ): EngineResult<FarmShiftState> {
        require(completionContribution in 1..64) { "Farm food delivery contribution is invalid" }
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.FOOD_DELIVERY ||
            current.specialIncident == null || reachedCheckpoint <= current.incidentProgress ||
            reachedCheckpoint > current.incidentRequired
        ) return EngineResult(current, false)
        val progressed = current.copy(incidentProgress = reachedCheckpoint)
        if (reachedCheckpoint >= current.incidentRequired) {
            val credited = progressed.copy(
                contributors = incrementContribution(progressed.contributors, playerId, completionContribution),
            )
            return completeIncident(credited, completionContribution)
        }
        return EngineResult(progressed, true, events = listOf(ShiftEvent.INCIDENT_PROGRESS))
    }

    fun defendFoodDelivery(
        current: FarmShiftState,
        playerId: UUID,
        contribution: Int = 1,
    ): EngineResult<FarmShiftState> {
        require(contribution in 1..8) { "Farm food delivery defense contribution is invalid" }
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.FOOD_DELIVERY ||
            current.specialIncident == null
        ) return EngineResult(current, false)
        return EngineResult(
            current.copy(contributors = incrementContribution(current.contributors, playerId, contribution)),
            accepted = true,
            contribution = contribution,
        )
    }

    fun skipUnavailableIncident(
        current: FarmShiftState,
        expectedType: FarmIncidentType,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != expectedType) {
            return EngineResult(current, false)
        }
        return completeIncident(current, contribution = 0).copy(events = emptyList())
    }

    /** Completes a delivery for administration/QA without fabricating player contribution. */
    fun completeDeliveryAsAdmin(
        current: FarmShiftState,
        rules: FarmRules,
        requiredCrates: Int,
        now: Long,
    ): EngineResult<FarmShiftState> {
        require(requiredCrates in 1..8) { "Farm delivery must require 1..8 crates" }
        if (current.phase != FarmPhase.DELIVERY) return EngineResult(current, false)
        return EngineResult(
            current.copy(
                phase = FarmPhase.COOLDOWN,
                cooldownEndsAt = now + rules.cooldownMillis,
                deliveryPosition = null,
                deliveredCrates = (0 until requiredCrates).toSet(),
                outcome = ShiftOutcome.COMPLETED,
            ),
            true,
            events = listOf(ShiftEvent.COMPLETED),
        )
    }

    fun tick(
        current: FarmShiftState,
        order: FarmOrder?,
        now: Long,
    ): EngineResult<FarmShiftState> {
        val state = current
        if (state.phase == FarmPhase.IDLE) return EngineResult(state, false)
        if (state.phase == FarmPhase.COOLDOWN && now >= state.cooldownEndsAt) {
            return EngineResult(
                FarmShiftState(sequence = state.sequence, placementSequence = state.placementSequence),
                true,
                events = listOf(ShiftEvent.RESET),
            )
        }
        if (state.phase == FarmPhase.COOLDOWN) return EngineResult(state, false)
        if (state.phase == FarmPhase.DELIVERY) return EngineResult(state, false)
        if (state.phase == FarmPhase.CARE) return EngineResult(state, false)
        if (order != null && state.completed(order) >= order.totalRequired) {
            if (state.phase == FarmPhase.INCIDENT) return EngineResult(state, false)
            return EngineResult(
                state.copy(
                    phase = FarmPhase.DELIVERY,
                    incidentCrop = null,
                    incidentType = null,
                    droughtPlots = emptySet(),
                    droughtDamagedPlots = emptySet(),
                    pestNests = emptyList(),
                    pestNestsInitialized = false,
                    pestAlive = 0,
                    pestDamagedCrops = emptyList(),
                    specialIncident = null,
                    processing = null,
                    specialDamagedCrops = emptyList(),
                    deliveryPosition = null,
                    deliveredCrates = emptySet(),
                ),
                true,
                events = listOf(ShiftEvent.DELIVERY_STARTED),
            )
        }
        return EngineResult(state, false)
    }

    private fun remainingCrop(state: FarmShiftState, order: FarmOrder): String? = order.required.entries
        .filter { (candidate, amount) -> (state.progress[candidate] ?: 0) < amount }
        .maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value - (state.progress[it.key] ?: 0) }
                .thenByDescending { it.key },
        )
        ?.key
}

private fun incrementContributions(
    current: Map<UUID, Int>,
    playerIds: Set<UUID>,
    delta: Int,
): Map<UUID, Int> = playerIds.fold(current) { contributions, playerId ->
    incrementContribution(contributions, playerId, delta)
}

package ru.ruscrafting.farms.domain

import java.util.UUID

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
        require(world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid farm plot world: $world" }
        require(x in -30_000_000..30_000_000 && z in -30_000_000..30_000_000) {
            "Farm plot position is outside the world border"
        }
        require(y in -4_096..4_096) { "Farm plot height is invalid" }
    }
}

enum class FarmIncidentType {
    PESTS,
    DROUGHT,
}

enum class FarmCareType {
    SEEDER,
    WEEDS,
    IRRIGATION,
    POLLINATION,
    STORM_COVERS,
    SCARECROWS,
    ANIMAL_RESCUE,
    DISEASE,
    MOLES,
}

enum class FarmCareRole {
    SEEDER_HORSE,
    SEEDER_WAYPOINT,
    WEED_ROOT,
    VALVE,
    HIVE,
    FLOWER_PATCH,
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
        require(id in 0..63) { "Farm care target id is invalid" }
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
        require(world.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid delivery world: $world" }
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
        require(crop.matches(Regex("[A-Z0-9_]{2,64}"))) { "Invalid damaged crop: $crop" }
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
        require(id.matches(Regex("[a-z0-9_-]{1,48}"))) { "Invalid farm order id: $id" }
        require(required.isNotEmpty()) { "Farm order $id must require crops" }
        require(required.size <= 12) { "Farm order $id has too many crops" }
        require(required.values.all { it in 1..100_000 }) { "Farm order $id has an invalid crop quota" }
        require(careTypes.isNotEmpty() && FarmCareType.SEEDER !in careTypes) { "Farm order $id has invalid care types" }
        require(careTypes.distinct().size == careTypes.size) { "Farm order $id duplicates a care type" }
        require(incidentTypes.isNotEmpty() && incidentTypes.distinct().size == incidentTypes.size) {
            "Farm order $id has invalid incident types"
        }
        require(cartLoadMaterial.matches(Regex("[A-Z0-9_]{2,64}"))) { "Farm order $id has an invalid cart load material" }
        require(cartLoadCustomModelData in 0..2_000_000) { "Farm order $id has an invalid cart load model" }
    }

    val totalRequired: Int = required.values.sum()
}

data class FarmRules(
    val incidentTriggerPercent: Int,
    val incidentQuota: Int,
    val cooldownMillis: Long,
    val droughtQuota: Int = incidentQuota,
) {
    init {
        require(incidentTriggerPercent in 1..99)
        require(incidentQuota in 1..64)
        require(cooldownMillis in 0..3_600_000)
        require(droughtQuota in 1..64)
    }
}

data class FarmShiftState(
    val phase: FarmPhase = FarmPhase.IDLE,
    val sequence: Long = 0,
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
    val harvestMilestone: Int = 0,
    val careType: FarmCareType? = null,
    val careTargets: List<FarmCareTarget> = emptyList(),
    val incidentCrop: String? = null,
    val incidentType: FarmIncidentType? = null,
    val incidentProgress: Int = 0,
    val incidentRequired: Int = 0,
    val incidentResolved: Boolean = false,
    val droughtPlots: Set<FarmPlotPosition> = emptySet(),
    val droughtDamagedPlots: Set<FarmPlotPosition> = emptySet(),
    val pestNestsInitialized: Boolean = false,
    val pestNests: List<FarmPestNest> = emptyList(),
    val pestAlive: Int = 0,
    val pestDamagedCrops: List<FarmCropDamage> = emptyList(),
    val startedAt: Long = 0,
    val cooldownEndsAt: Long = 0,
    val deliveryPosition: FarmDeliveryPosition? = null,
    val deliveredCrates: Set<Int> = emptySet(),
    val outcome: ShiftOutcome = ShiftOutcome.NONE,
    val contributors: Map<UUID, Int> = emptyMap(),
) {
    fun completed(order: FarmOrder): Int = order.required.entries.sumOf { (crop, amount) ->
        (progress[crop] ?: 0).coerceAtMost(amount)
    }

    fun progressRatio(order: FarmOrder): Double = completed(order).toDouble() / order.totalRequired.toDouble()

    fun careProgress(): Int = careTargets.sumOf(FarmCareTarget::progress)

    fun careRequired(): Int = careTargets.sumOf(FarmCareTarget::required)
}

object FarmShiftEngine {
    fun start(
        current: FarmShiftState,
        order: FarmOrder,
        patch: List<FarmPlotPosition>,
        preparationCrop: String,
        now: Long,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.IDLE) return EngineResult(current, false)
        require(patch.isNotEmpty() && patch.size <= 512) { "Farm preparation patch must contain 1..512 plots" }
        require(patch.distinct().size == patch.size) { "Farm preparation patch contains duplicate plots" }
        require(patch.map(FarmPlotPosition::world).distinct().size == 1) { "Farm preparation patch crosses worlds" }
        require(preparationCrop in order.required) { "Farm preparation crop is outside order ${order.id}" }
        val next = FarmShiftState(
            phase = FarmPhase.PREPARATION,
            sequence = current.sequence + 1,
            orderId = order.id,
            progress = order.required.keys.associateWith { 0 },
            preparationPatch = patch,
            preparationCrop = preparationCrop,
            preparationRequired = patch.size,
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
        val state = current.copy(
            phase = if (completed) FarmPhase.PLANTING else FarmPhase.PREPARATION,
            tilledPlots = current.tilledPlots + plot,
            preparationProgress = progress,
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
        val state = current.copy(
            phase = if (completed) FarmPhase.HARVESTING else FarmPhase.PLANTING,
            plantedPlots = current.plantedPlots + plot,
            plantingProgress = progress,
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
                deliveryPosition = null,
                deliveredCrates = emptySet(),
            )
            events += ShiftEvent.DELIVERY_STARTED
            return EngineResult(state, true, contribution, events)
        }

        val triggerReached = state.completed(order) * 100 >= order.totalRequired * rules.incidentTriggerPercent
        if (!state.incidentResolved && state.incidentCrop == null && triggerReached) {
            val incidentCrop = remainingCrop(state, order)
            if (incidentCrop != null) {
                state = state.copy(
                    phase = FarmPhase.INCIDENT,
                    incidentCrop = incidentCrop,
                    incidentType = incidentType,
                    incidentProgress = 0,
                    incidentRequired = if (incidentType == FarmIncidentType.DROUGHT) rules.droughtQuota else rules.incidentQuota,
                    droughtPlots = emptySet(),
                    droughtDamagedPlots = emptySet(),
                    pestNests = emptyList(),
                    pestNestsInitialized = false,
                    pestAlive = 0,
                    pestDamagedCrops = emptyList(),
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
    ): EngineResult<FarmShiftState> {
        val validSource = if (type == FarmCareType.SEEDER) {
            current.phase == FarmPhase.PLANTING && current.plantingProgress == 0
        } else {
            current.phase == FarmPhase.HARVESTING
        }
        if (!validSource || current.careType != null) {
            return EngineResult(current, false)
        }
        require(targets.isNotEmpty() && targets.size <= 64) { "Farm care scene must contain 1..64 targets" }
        require(targets.map(FarmCareTarget::id).distinct().size == targets.size) { "Farm care scene contains duplicate target ids" }
        require(targets.map { it.position.world }.distinct().size == 1) { "Farm care scene crosses worlds" }
        return EngineResult(
            current.copy(
                phase = FarmPhase.CARE,
                careType = type,
                careTargets = targets,
            ),
            true,
            events = listOf(ShiftEvent.CARE_STARTED),
        )
    }

    fun advanceSeeder(
        current: FarmShiftState,
        targetId: Int,
        planted: Set<FarmPlotPosition>,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.CARE || current.careType != FarmCareType.SEEDER) {
            return EngineResult(current, false)
        }
        require(planted.all(current.preparationPatch::contains)) { "Seeder planted outside the preparation patch" }
        val target = current.careTargets.firstOrNull { it.id == targetId } ?: return EngineResult(current, false)
        if (target.role !in setOf(FarmCareRole.SEEDER_HORSE, FarmCareRole.SEEDER_WAYPOINT)) {
            return EngineResult(current, false)
        }
        if (target.role == FarmCareRole.SEEDER_HORSE && planted.isNotEmpty()) return EngineResult(current, false)
        if (target.complete) return EngineResult(current, false)
        val targets = current.careTargets.map { candidate ->
            if (candidate.id == targetId) candidate.copy(progress = candidate.progress + 1) else candidate
        }
        val plantedPlots = current.plantedPlots + planted
        val routeComplete = targets.all(FarmCareTarget::complete)
        if (routeComplete && !plantedPlots.containsAll(current.preparationPatch)) return EngineResult(current, false)
        val complete = routeComplete
        val contribution = (plantedPlots.size - current.plantedPlots.size).coerceAtLeast(1)
        return EngineResult(
            current.copy(
                phase = if (complete) FarmPhase.HARVESTING else FarmPhase.CARE,
                careTargets = targets,
                plantedPlots = plantedPlots,
                plantingProgress = plantedPlots.size.coerceAtMost(current.preparationRequired),
                contributors = incrementContribution(current.contributors, playerId, contribution),
            ),
            true,
            contribution = contribution,
            events = listOf(ShiftEvent.CARE_PROGRESS) + if (complete) listOf(ShiftEvent.CARE_RESOLVED) else emptyList(),
        )
    }

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
        val complete = targets.all(FarmCareTarget::complete)
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
        return EngineResult(current.copy(careTargets = current.careTargets + target), true)
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
            incidentType = null,
            droughtPlots = emptySet(),
            pestNests = emptyList(),
            pestNestsInitialized = false,
            pestAlive = 0,
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

    fun tick(
        current: FarmShiftState,
        order: FarmOrder?,
        now: Long,
    ): EngineResult<FarmShiftState> {
        val state = current
        if (state.phase == FarmPhase.IDLE) return EngineResult(state, false)
        if (state.phase == FarmPhase.COOLDOWN && now >= state.cooldownEndsAt) {
            return EngineResult(FarmShiftState(sequence = state.sequence), true, events = listOf(ShiftEvent.RESET))
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

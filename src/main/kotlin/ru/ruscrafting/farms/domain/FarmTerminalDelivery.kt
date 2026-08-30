package ru.ruscrafting.farms.domain

import java.util.UUID

/** Pure state transitions for the fixed crate-delivery -> food-route finale. */
internal object FarmTerminalDelivery {
    fun start(
        current: FarmShiftState,
        deliveredCrates: Set<Int>,
        contributors: Map<UUID, Int>,
    ): FarmShiftState = current.copy(
        phase = FarmPhase.INCIDENT,
        placementSequence = current.nextPlacementSequence(),
        incidentCrop = null,
        incidentType = FarmIncidentType.FOOD_DELIVERY,
        incidentProgress = 0,
        incidentRequired = 0,
        incidentResolved = false,
        droughtPlots = emptySet(),
        droughtDamagedPlots = emptySet(),
        pestNests = emptyList(),
        pestNestsInitialized = false,
        pestAlive = 0,
        pestDamagedCrops = emptyList(),
        specialIncident = null,
        frost = null,
        processing = null,
        specialDamagedCrops = emptyList(),
        deliveryPosition = null,
        deliveredCrates = deliveredCrates,
        outcome = ShiftOutcome.NONE,
        contributors = contributors,
    )

    fun complete(
        current: FarmShiftState,
        rules: FarmRules,
        now: Long,
        contribution: Int,
    ): EngineResult<FarmShiftState, FarmShiftEvent> = EngineResult(
        current.copy(
            phase = FarmPhase.COOLDOWN,
            incidentResolved = true,
            incidentsResolved = (current.incidentsResolved + 1).coerceAtMost(MAX_FARM_INCIDENTS),
            incidentCrop = null,
            incidentType = null,
            incidentProgress = 0,
            incidentRequired = 0,
            specialIncident = null,
            cooldownEndsAt = now + rules.cooldownMillis,
            outcome = ShiftOutcome.COMPLETED,
        ),
        true,
        contribution,
        listOf(FarmShiftEvent.COMPLETED),
    )
}

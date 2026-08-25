package ru.ruscrafting.farms.domain

import java.util.UUID

private val SPECIAL_INCIDENT_TYPES = setOf(
    FarmIncidentType.GIANT_CROP,
    FarmIncidentType.CHANNELS,
    FarmIncidentType.NIGHT_SHIFT,
    FarmIncidentType.MARKET,
)

object FarmSpecialIncidentEngine {
    fun initialize(
        current: FarmShiftState,
        type: FarmIncidentType,
        state: FarmSpecialIncidentState,
        required: Int,
    ): EngineResult<FarmShiftState> {
        require(type in SPECIAL_INCIDENT_TYPES) { "$type is not a special farm incident" }
        require(required in 1..1_024) { "Special farm incident quota is invalid" }
        if (
            current.phase != FarmPhase.INCIDENT || current.incidentType != type ||
            current.specialIncident != null
        ) return EngineResult(current, false)
        return EngineResult(
            current.copy(
                incidentProgress = 0,
                incidentRequired = required,
                specialIncident = state,
            ),
            true,
        )
    }

    fun damageGiantCrop(current: FarmShiftState, playerId: UUID): EngineResult<FarmShiftState> =
        advance(current, FarmIncidentType.GIANT_CROP, playerId)

    fun toggleChannelGate(
        current: FarmShiftState,
        gateIndex: Int,
        playerId: UUID,
    ): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.CHANNELS) {
            return EngineResult(current, false)
        }
        val special = current.specialIncident ?: return EngineResult(current, false)
        if (gateIndex !in special.points.indices) return EngineResult(current, false)
        val active = special.active.toMutableSet().also { gates ->
            if (!gates.add(gateIndex)) gates.remove(gateIndex)
        }
        val progress = channelProgress(special.solution, active, special.points.size)
        val contribution = (progress - current.incidentProgress).coerceAtLeast(0)
        val updated = current.copy(
            incidentProgress = progress,
            specialIncident = special.copy(active = active),
        )
        if (progress >= current.incidentRequired) {
            return complete(updated, playerId, contribution = contribution)
        }
        return EngineResult(
            updated.copy(
                contributors = if (contribution > 0) {
                    incrementContribution(updated.contributors, playerId, contribution)
                } else updated.contributors,
            ),
            true,
            contribution = contribution,
            events = listOf(ShiftEvent.INCIDENT_PROGRESS),
        )
    }

    fun acceptMarket(current: FarmShiftState): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.MARKET) {
            return EngineResult(current, false)
        }
        val special = current.specialIncident ?: return EngineResult(current, false)
        if (special.marketAccepted) return EngineResult(current, false)
        return EngineResult(current.copy(specialIncident = special.copy(marketAccepted = true)), true)
    }

    fun declineMarket(current: FarmShiftState): EngineResult<FarmShiftState> {
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.MARKET) {
            return EngineResult(current, false)
        }
        val special = current.specialIncident ?: return EngineResult(current, false)
        if (special.marketAccepted) return EngineResult(current, false)
        return complete(current, playerId = null, contribution = 0)
    }

    fun harvestSpecialCrop(
        current: FarmShiftState,
        type: FarmIncidentType,
        damage: FarmCropDamage,
        playerId: UUID,
        marketBonusPercent: Int = 0,
    ): EngineResult<FarmShiftState> {
        require(type == FarmIncidentType.NIGHT_SHIFT || type == FarmIncidentType.MARKET) {
            "$type does not harvest special crops"
        }
        require(marketBonusPercent in 0..200) { "Market reward bonus is invalid" }
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != type) {
            return EngineResult(current, false)
        }
        val special = current.specialIncident ?: return EngineResult(current, false)
        if (type == FarmIncidentType.MARKET && !special.marketAccepted) return EngineResult(current, false)
        if (current.specialDamagedCrops.any { it.position == damage.position }) return EngineResult(current, false)
        val damaged = current.specialDamagedCrops + damage
        val advanced = current.copy(specialDamagedCrops = damaged)
        return advance(
            advanced,
            type,
            playerId,
            bonusPercentOnComplete = if (type == FarmIncidentType.MARKET) marketBonusPercent else 0,
        )
    }

    fun channelProgress(solution: Set<Int>, active: Set<Int>, gateCount: Int): Int {
        require(gateCount in 1..16) { "Farm channel gate count is invalid" }
        require(solution.all { it in 0 until gateCount } && active.all { it in 0 until gateCount }) {
            "Farm channel state references an unknown gate"
        }
        return (0 until gateCount).takeWhile { index -> (index in solution) == (index in active) }.count()
    }

    private fun advance(
        current: FarmShiftState,
        type: FarmIncidentType,
        playerId: UUID,
        bonusPercentOnComplete: Int = 0,
    ): EngineResult<FarmShiftState> {
        if (
            current.phase != FarmPhase.INCIDENT || current.incidentType != type ||
            current.incidentProgress >= current.incidentRequired || current.specialIncident == null
        ) return EngineResult(current, false)
        val progressed = current.copy(incidentProgress = current.incidentProgress + 1)
        if (progressed.incidentProgress >= progressed.incidentRequired) {
            val withBonus = progressed.copy(
                rewardMoneyBonusPercent = (progressed.rewardMoneyBonusPercent + bonusPercentOnComplete).coerceAtMost(200),
            )
            return complete(withBonus, playerId, contribution = 1)
        }
        return EngineResult(
            progressed.copy(contributors = incrementContribution(progressed.contributors, playerId, 1)),
            true,
            contribution = 1,
            events = listOf(ShiftEvent.INCIDENT_PROGRESS),
        )
    }

    private fun complete(
        current: FarmShiftState,
        playerId: UUID?,
        contribution: Int,
    ): EngineResult<FarmShiftState> {
        val contributors = if (playerId != null && contribution > 0) {
            incrementContribution(current.contributors, playerId, contribution)
        } else current.contributors
        return EngineResult(
            current.copy(
                phase = FarmPhase.HARVESTING,
                incidentResolved = true,
                incidentsResolved = current.incidentsResolved + 1,
                incidentCrop = null,
                incidentType = null,
                incidentProgress = 0,
                incidentRequired = 0,
                specialIncident = null,
                contributors = contributors,
            ),
            true,
            contribution = contribution,
            events = listOf(ShiftEvent.INCIDENT_RESOLVED),
        )
    }
}

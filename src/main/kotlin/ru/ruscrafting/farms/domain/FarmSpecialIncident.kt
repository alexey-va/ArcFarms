package ru.ruscrafting.farms.domain

import java.util.UUID

private val SPECIAL_INCIDENT_TYPES = setOf(
    FarmIncidentType.GIANT_CROP,
    FarmIncidentType.CHANNELS,
    FarmIncidentType.NIGHT_SHIFT,
    FarmIncidentType.MARKET,
)

object FarmSpecialIncidentEngine {
    fun retargetUninitialized(
        current: FarmShiftState,
        replacement: FarmIncidentType,
    ): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(replacement in SPECIAL_INCIDENT_TYPES) { "$replacement is not a special farm incident" }
        if (
            current.phase != FarmPhase.INCIDENT || current.incidentType !in SPECIAL_INCIDENT_TYPES ||
            current.specialIncident != null || current.incidentProgress != 0
        ) return EngineResult(current, false)
        if (current.incidentType == replacement) return EngineResult(current, false)
        return EngineResult(current.copy(incidentType = replacement), true)
    }

    fun skipUnavailable(current: FarmShiftState): EngineResult<FarmShiftState, FarmShiftEvent> {
        if (
            current.phase != FarmPhase.INCIDENT || current.incidentType !in SPECIAL_INCIDENT_TYPES ||
            current.specialIncident != null || current.incidentProgress != 0
        ) return EngineResult(current, false)
        return complete(current, playerId = null, contribution = 0).copy(events = emptyList())
    }

    fun initialize(
        current: FarmShiftState,
        type: FarmIncidentType,
        state: FarmSpecialIncidentState,
        required: Int,
    ): EngineResult<FarmShiftState, FarmShiftEvent> {
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

    fun damageGiantCrop(current: FarmShiftState, playerId: UUID): EngineResult<FarmShiftState, FarmShiftEvent> =
        advance(current, FarmIncidentType.GIANT_CROP, playerId)

    fun reconcileGiantCrop(
        current: FarmShiftState,
        totalBlocks: Int,
        brokenBlocks: Int,
    ): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(totalBlocks in 1..1_024 && brokenBlocks in 0..totalBlocks) { "Invalid giant crop block state" }
        if (
            current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.GIANT_CROP ||
            current.specialIncident == null ||
            (totalBlocks == current.incidentRequired && brokenBlocks <= current.incidentProgress)
        ) return EngineResult(current, false)
        val reconciled = current.copy(incidentRequired = totalBlocks, incidentProgress = brokenBlocks)
        return if (brokenBlocks >= totalBlocks) {
            complete(reconciled, playerId = null, contribution = 0)
        } else {
            EngineResult(reconciled, true)
        }
    }

    fun clearChannelBlockage(
        current: FarmShiftState,
        blockageIndex: Int,
        playerId: UUID,
    ): EngineResult<FarmShiftState, FarmShiftEvent> {
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.CHANNELS) {
            return EngineResult(current, false)
        }
        val special = current.specialIncident ?: return EngineResult(current, false)
        if (blockageIndex !in special.points.indices || blockageIndex in special.active) {
            return EngineResult(current, false)
        }
        val cleared = special.active + blockageIndex
        val progress = cleared.size
        val updated = current.copy(
            incidentProgress = progress,
            specialIncident = special.copy(active = cleared),
        )
        if (progress >= current.incidentRequired) {
            return complete(updated, playerId, contribution = 1)
        }
        return EngineResult(
            updated.copy(contributors = incrementContribution(updated.contributors, playerId, 1)),
            true,
            contribution = 1,
            events = listOf(FarmShiftEvent.INCIDENT_PROGRESS),
        )
    }

    fun acceptMarket(current: FarmShiftState, now: Long, durationMillis: Long): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(now >= 0) { "Market clock is invalid" }
        require(durationMillis in 10_000L..3_600_000L) { "Market duration is invalid" }
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.MARKET) {
            return EngineResult(current, false)
        }
        val special = current.specialIncident ?: return EngineResult(current, false)
        if (special.marketAccepted) return EngineResult(current, false)
        val deadline = if (Long.MAX_VALUE - now < durationMillis) Long.MAX_VALUE else now + durationMillis
        return EngineResult(
            current.copy(specialIncident = special.copy(marketAccepted = true, marketDeadlineAt = deadline)),
            true,
        )
    }

    fun expireMarket(current: FarmShiftState, now: Long): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(now >= 0) { "Market clock is invalid" }
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.MARKET) {
            return EngineResult(current, false)
        }
        val special = current.specialIncident ?: return EngineResult(current, false)
        if (!special.marketAccepted || special.marketDeadlineAt <= 0 || now < special.marketDeadlineAt) {
            return EngineResult(current, false)
        }
        return complete(current, playerId = null, contribution = 0, event = FarmShiftEvent.MARKET_EXPIRED)
    }

    fun declineMarket(current: FarmShiftState): EngineResult<FarmShiftState, FarmShiftEvent> {
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
    ): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(type == FarmIncidentType.NIGHT_SHIFT || type == FarmIncidentType.MARKET) {
            "$type does not harvest special crops"
        }
        require(marketBonusPercent in 0..200) { "Market reward bonus is invalid" }
        if (current.phase != FarmPhase.INCIDENT || current.incidentType != type) {
            return EngineResult(current, false)
        }
        val special = current.specialIncident ?: return EngineResult(current, false)
        if (type == FarmIncidentType.MARKET && !special.marketAccepted) return EngineResult(current, false)
        if (type == FarmIncidentType.NIGHT_SHIFT && current.specialDamagedCrops.any { it.position == damage.position }) {
            return EngineResult(current, false)
        }
        val advanced = if (type == FarmIncidentType.NIGHT_SHIFT) {
            current.copy(specialDamagedCrops = current.specialDamagedCrops + damage)
        } else {
            current
        }
        return advance(
            advanced,
            type,
            playerId,
            bonusPercentOnComplete = if (type == FarmIncidentType.MARKET) marketBonusPercent else 0,
        )
    }

    fun channelFlowProgress(cleared: Set<Int>, blockageCount: Int): Int {
        require(blockageCount in 1..16) { "Farm channel blockage count is invalid" }
        require(cleared.all { it in 0 until blockageCount }) {
            "Farm channel state references an unknown blockage"
        }
        return (0 until blockageCount).takeWhile(cleared::contains).count()
    }

    private fun advance(
        current: FarmShiftState,
        type: FarmIncidentType,
        playerId: UUID,
        bonusPercentOnComplete: Int = 0,
    ): EngineResult<FarmShiftState, FarmShiftEvent> {
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
            events = listOf(FarmShiftEvent.INCIDENT_PROGRESS),
        )
    }

    private fun complete(
        current: FarmShiftState,
        playerId: UUID?,
        contribution: Int,
        event: FarmShiftEvent = FarmShiftEvent.INCIDENT_RESOLVED,
    ): EngineResult<FarmShiftState, FarmShiftEvent> {
        val contributors = if (playerId != null && contribution > 0) {
            incrementContribution(current.contributors, playerId, contribution)
        } else current.contributors
        return EngineResult(
            current.copy(
                phase = FarmPhase.HARVESTING,
                incidentResolved = true,
                incidentsResolved = (current.incidentsResolved + 1).coerceAtMost(MAX_FARM_INCIDENTS),
                incidentCrop = null,
                incidentType = null,
                incidentProgress = 0,
                incidentRequired = 0,
                specialIncident = null,
                contributors = contributors,
            ),
            true,
            contribution = contribution,
            events = listOf(event),
        )
    }
}

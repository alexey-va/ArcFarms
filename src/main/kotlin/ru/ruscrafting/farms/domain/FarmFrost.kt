package ru.ruscrafting.farms.domain

import java.util.UUID

object FarmFrostEngine {
    fun initialize(
        current: FarmShiftState,
        campfires: List<FarmPlotPosition>,
        now: Long,
        targetTemperature: Int,
    ): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(targetTemperature in 1..1_000) { "Farm frost temperature target is invalid" }
        require(now >= 0) { "Farm frost start timestamp is invalid" }
        if (
            current.phase != FarmPhase.INCIDENT || current.incidentType != FarmIncidentType.FROST ||
            current.frost != null || campfires.isEmpty()
        ) {
            return EngineResult(current, false)
        }
        val frost = FarmFrostState(
            campfires = campfires.distinct().map(::FarmFrostCampfire),
            lastTickAt = now,
        )
        return EngineResult(
            current.copy(
                incidentProgress = 0,
                incidentRequired = targetTemperature,
                frost = frost,
            ),
            true,
        )
    }

    fun fuel(
        current: FarmShiftState,
        position: FarmPlotPosition,
        playerId: UUID,
        now: Long,
        fuelMillis: Long,
    ): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(now >= 0) { "Farm frost fuel timestamp is invalid" }
        require(fuelMillis in 1_000..3_600_000) { "Farm frost fuel duration is invalid" }
        val frost = current.activeFrost() ?: return EngineResult(current, false)
        if (frost.campfires.none { it.position == position }) return EngineResult(current, false)
        val campfires = frost.campfires.map { fire ->
            if (fire.position != position) fire
            else {
                val base = maxOf(now, fire.fuelUntil)
                fire.copy(fuelUntil = if (base > Long.MAX_VALUE - fuelMillis) Long.MAX_VALUE else base + fuelMillis)
            }
        }
        return EngineResult(
            current.copy(
                frost = frost.copy(campfires = campfires, lastTickAt = now, coolingRemainderMillis = 0),
                contributors = incrementContribution(current.contributors, playerId, 1),
            ),
            true,
            contribution = 1,
            events = listOf(FarmShiftEvent.INCIDENT_PROGRESS),
        )
    }

    fun tick(
        current: FarmShiftState,
        now: Long,
        heatPerSecondPerFire: Int,
        coolingSecondsPerDegree: Int,
    ): EngineResult<FarmShiftState, FarmShiftEvent> {
        require(now >= 0) { "Farm frost tick timestamp is invalid" }
        require(heatPerSecondPerFire in 1..100) { "Farm frost heat rate is invalid" }
        require(coolingSecondsPerDegree in 1..600) { "Farm frost cooling rate is invalid" }
        val frost = current.activeFrost() ?: return EngineResult(current, false)
        if (now <= frost.lastTickAt) return EngineResult(current, false)

        val elapsedMillis = now - frost.lastTickAt
        val litCampfires = frost.campfires.count { it.fuelUntil > now }
        if (litCampfires == 0 && current.incidentProgress == 0) return EngineResult(current, false)
        val (delta, coolingRemainder) = if (litCampfires > 0) {
            val heated = elapsedMillis / 1_000L * heatPerSecondPerFire * litCampfires
            heated.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() to 0L
        } else {
            val intervalMillis = coolingSecondsPerDegree * 1_000L
            val accumulated = frost.coolingRemainderMillis + elapsedMillis
            -(accumulated / intervalMillis).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() to
                (accumulated % intervalMillis)
        }
        if (delta == 0) return EngineResult(current, false)
        val temperature = (current.incidentProgress + delta).coerceIn(0, current.incidentRequired)
        val progressed = current.copy(
            incidentProgress = temperature,
            frost = frost.copy(
                lastTickAt = now,
                coolingRemainderMillis = coolingRemainder,
            ),
        )
        if (temperature >= current.incidentRequired) {
            val completed = FarmShiftEngine.completeIncident(progressed, contribution = 0)
            return completed.copy(
                events = listOf(FarmShiftEvent.INCIDENT_PROGRESS, FarmShiftEvent.INCIDENT_RESOLVED),
            )
        }
        return EngineResult(
            progressed,
            true,
            events = if (temperature != current.incidentProgress) {
                listOf(FarmShiftEvent.INCIDENT_PROGRESS)
            } else {
                emptyList()
            },
        )
    }

    private fun FarmShiftState.activeFrost(): FarmFrostState? = frost?.takeIf {
        phase == FarmPhase.INCIDENT && incidentType == FarmIncidentType.FROST && incidentRequired > 0
    }
}

/** Deterministically spreads campfires over indexed field beds. */
object FarmFrostPlanner {
    fun select(
        beds: Collection<FarmPlotPosition>,
        count: Int,
        sequence: Long,
    ): List<FarmPlotPosition> {
        require(count in 0..16) { "Farm frost campfire count is invalid" }
        val available = beds.distinct().sortedWith(
            compareBy<FarmPlotPosition> { it.world }.thenBy { it.x }.thenBy { it.z }.thenBy { it.y },
        ).toMutableList()
        if (available.isEmpty() || count == 0) return emptyList()
        val mixed = FarmSpatialSeed.mix(sequence, 0x46524f5354L)
        val first = java.lang.Math.floorMod((mixed xor (mixed ushr 32)).toInt(), available.size)
        val selected = mutableListOf(available.removeAt(first))
        while (selected.size < count && available.isNotEmpty()) {
            val next = available.maxWithOrNull(
                compareBy<FarmPlotPosition> { candidate ->
                    selected.minOf { chosen -> distanceSquared(candidate, chosen) }
                }.thenByDescending { it.x }.thenByDescending { it.z }.thenByDescending { it.y },
            ) ?: break
            available.remove(next)
            selected += next
        }
        return selected
    }

    private fun distanceSquared(left: FarmPlotPosition, right: FarmPlotPosition): Long {
        if (left.world != right.world) return Long.MAX_VALUE
        val dx = left.x.toLong() - right.x
        val dz = left.z.toLong() - right.z
        return dx * dx + dz * dz
    }
}

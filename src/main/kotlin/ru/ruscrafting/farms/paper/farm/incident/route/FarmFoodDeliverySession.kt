package ru.ruscrafting.farms.paper.farm.incident.route

import org.bukkit.Location
import ru.ruscrafting.farms.domain.FarmStallWatchdogState
import java.util.ArrayDeque
import java.util.UUID

/** Volatile, bounded entity ownership for one active delivery run. */
internal data class FarmFoodDeliverySession(
    val sequence: Long,
    val routeName: String,
    var horseId: UUID? = null,
    var cartId: UUID? = null,
    var gunnerSeatId: UUID? = null,
    val loadIds: MutableList<UUID> = mutableListOf(),
    var riderId: UUID? = null,
    var gunnerId: UUID? = null,
    val crewIds: MutableSet<UUID> = linkedSetOf(),
    val escortIds: MutableSet<UUID> = linkedSetOf(),
    val ambushCrewIds: MutableSet<UUID> = linkedSetOf(),
    var portalId: UUID? = null,
    var portalLabelId: UUID? = null,
    var brokenDown: Boolean = false,
    var spawnedMonsters: Int = 0,
    var ambushesStarted: Int = 0,
    val monsterIds: MutableSet<UUID> = linkedSetOf(),
    val pendingAmbushCheckpoints: ArrayDeque<Int> = ArrayDeque(),
    var ambushPlan: FarmFoodDeliveryAmbushPlan? = null,
    val gunnerTrail: ArrayDeque<Location> = ArrayDeque(),
    var stallWatchdog: FarmStallWatchdogState? = null,
    var stallAnchor: Location? = null,
    var stallRouteProgress: Int = 0,
) {
    fun participantIds(): Set<UUID> = buildSet {
        addAll(crewIds)
        riderId?.let(::add)
        gunnerId?.let(::add)
        addAll(escortIds)
        addAll(ambushCrewIds)
    }

    fun isParticipant(playerId: UUID): Boolean = playerId in crewIds || riderId == playerId || gunnerId == playerId ||
        playerId in escortIds || playerId in ambushCrewIds

    fun registerParticipant(playerId: UUID) {
        crewIds += playerId
    }

    fun transitionToEscort(playerId: UUID) {
        registerParticipant(playerId)
        if (riderId == playerId) riderId = null
        if (gunnerId == playerId) gunnerId = null
        ambushCrewIds.remove(playerId)
        escortIds += playerId
    }

    fun releaseParticipant(playerId: UUID): Boolean {
        val wasParticipant = isParticipant(playerId)
        if (riderId == playerId) riderId = null
        if (gunnerId == playerId) gunnerId = null
        escortIds.remove(playerId)
        ambushCrewIds.remove(playerId)
        crewIds.remove(playerId)
        return wasParticipant
    }

    fun finishWaveIfCleared(): Boolean {
        if (!brokenDown || monsterIds.isNotEmpty()) return false
        brokenDown = false
        return true
    }

    fun replaceAmbushCheckpoints(candidates: List<Int>, maximum: Int, progress: Int) {
        val remaining = (maximum - ambushesStarted).coerceAtLeast(0)
        val due = pendingAmbushCheckpoints.firstOrNull()
            ?.takeIf { it <= progress && !brokenDown && remaining > 0 }
        pendingAmbushCheckpoints.clear()
        due?.let(pendingAmbushCheckpoints::addLast)
        candidates.asSequence()
            .filter { it > progress }
            .distinct()
            .take(remaining - if (due == null) 0 else 1)
            .forEach(pendingAmbushCheckpoints::addLast)
    }
}

internal data class FarmFoodDeliveryAmbushPlan(
    val distance: Double,
    val maximum: Int,
    val afterFarmDistance: Double,
    val endSafeDistance: Double,
    val placementSeed: Long,
)

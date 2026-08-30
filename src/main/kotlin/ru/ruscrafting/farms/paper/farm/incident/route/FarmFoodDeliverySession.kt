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
    val escortIds: MutableSet<UUID> = linkedSetOf(),
    val ambushCrewIds: MutableSet<UUID> = linkedSetOf(),
    var portalId: UUID? = null,
    var portalLabelId: UUID? = null,
    var brokenDown: Boolean = false,
    var spawnedMonsters: Int = 0,
    val monsterIds: MutableSet<UUID> = linkedSetOf(),
    val pendingAmbushCheckpoints: ArrayDeque<Int> = ArrayDeque(),
    val gunnerTrail: ArrayDeque<Location> = ArrayDeque(),
    var stallWatchdog: FarmStallWatchdogState? = null,
    var stallAnchor: Location? = null,
    var stallRouteProgress: Int = 0,
) {
    fun participantIds(): Set<UUID> = buildSet {
        riderId?.let(::add)
        gunnerId?.let(::add)
        addAll(escortIds)
        addAll(ambushCrewIds)
    }

    fun isParticipant(playerId: UUID): Boolean = riderId == playerId || gunnerId == playerId ||
        playerId in escortIds || playerId in ambushCrewIds

    fun transitionToEscort(playerId: UUID) {
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
        return wasParticipant
    }

    fun finishWaveIfCleared(): Boolean {
        if (!brokenDown || monsterIds.isNotEmpty()) return false
        brokenDown = false
        return true
    }
}

package ru.ruscrafting.farms.paper.farm.incident.route

import org.bukkit.Location
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
    var brokenDown: Boolean = false,
    var spawnedMonsters: Int = 0,
    val monsterIds: MutableSet<UUID> = linkedSetOf(),
    val monsterGoal: Int,
    var lastWaveAt: Long = 0,
    val gunnerTrail: ArrayDeque<Location> = ArrayDeque(),
) {
    /** Opens a real respite window after the last attacker of a wave is defeated. */
    fun finishWaveIfCleared(now: Long): Boolean {
        if (!brokenDown || monsterIds.isNotEmpty()) return false
        brokenDown = false
        lastWaveAt = now
        return true
    }
}

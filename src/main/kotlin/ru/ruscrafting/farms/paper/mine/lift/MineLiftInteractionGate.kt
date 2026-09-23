package ru.ruscrafting.farms.paper.mine.lift

import java.util.UUID

/** Coalesces duplicate main-hand entity events delivered in one server tick. */
internal class MineLiftInteractionGate {
    private val lastTick = mutableMapOf<UUID, Long>()
    private val pendingWalkIns = mutableSetOf<UUID>()

    fun isDuplicate(player: UUID, tick: Long): Boolean = lastTick.put(player, tick) == tick

    /** Opens once for a cancelled walk-in attempt; clear it after a step away or successful boarding. */
    fun shouldOpenWalkInMenu(player: UUID, tick: Long): Boolean =
        pendingWalkIns.add(player) && !isDuplicate(player, tick)

    fun hasPendingWalkIn(player: UUID): Boolean = player in pendingWalkIns

    fun clearWalkIn(player: UUID) { pendingWalkIns.remove(player) }

    fun clear(player: UUID) {
        lastTick.remove(player)
        pendingWalkIns.remove(player)
    }

    fun clear() {
        lastTick.clear()
        pendingWalkIns.clear()
    }
}

package ru.ruscrafting.farms.paper.mine.lift

import java.util.UUID

/** Coalesces duplicate main-hand entity events delivered in one server tick. */
internal class MineLiftInteractionGate {
    private val lastTick = mutableMapOf<UUID, Long>()

    fun isDuplicate(player: UUID, tick: Long): Boolean = lastTick.put(player, tick) == tick

    fun clear(player: UUID) { lastTick.remove(player) }

    fun clear() { lastTick.clear() }
}

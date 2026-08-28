package ru.ruscrafting.farms.domain

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe lifecycle gate for snapshot-based background planning.
 * Sequence is part of the identity, so a late completion from an old shift
 * cannot release the guard owned by a newer shift in the same zone.
 */
internal class FarmAsyncPlanGate {
    private data class Key(val zoneId: String, val sequence: Long)

    private val pending = ConcurrentHashMap.newKeySet<Key>()

    fun acquire(zoneId: String, sequence: Long): Boolean = pending.add(Key(zoneId, sequence))

    fun release(zoneId: String, sequence: Long) {
        pending.remove(Key(zoneId, sequence))
    }

    fun clearZone(zoneId: String) {
        pending.removeIf { it.zoneId == zoneId }
    }

    fun clear() = pending.clear()
}

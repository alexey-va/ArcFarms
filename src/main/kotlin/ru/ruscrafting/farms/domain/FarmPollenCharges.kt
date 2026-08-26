package ru.ruscrafting.farms.domain

import java.util.UUID

/** Ephemeral pollination charges scoped to one exact farm shift. */
internal class FarmPollenCharges {
    private data class Charge(
        val zoneId: String,
        val sequence: Long,
        val remaining: Int,
    )

    private val charges = mutableMapOf<UUID, Charge>()

    fun grant(playerId: UUID, zoneId: String, sequence: Long, amount: Int) {
        require(zoneId.isNotBlank()) { "Pollen charge zone must not be blank" }
        require(sequence >= 0L) { "Pollen charge sequence must not be negative" }
        require(amount >= 1) { "Pollen charge amount must be positive" }
        charges[playerId] = Charge(zoneId, sequence, amount)
    }

    fun remaining(playerId: UUID, zoneId: String, sequence: Long): Int =
        charges[playerId]?.takeIf { it.zoneId == zoneId && it.sequence == sequence }?.remaining ?: 0

    fun consume(playerId: UUID, zoneId: String, sequence: Long): Boolean {
        val charge = charges[playerId]?.takeIf { it.zoneId == zoneId && it.sequence == sequence } ?: return false
        if (charge.remaining <= 1) charges.remove(playerId)
        else charges[playerId] = charge.copy(remaining = charge.remaining - 1)
        return true
    }

    fun remove(playerId: UUID) {
        charges.remove(playerId)
    }

    fun clear(zoneId: String, sequence: Long) {
        charges.entries.removeIf { (_, charge) -> charge.zoneId == zoneId && charge.sequence == sequence }
    }

    fun clearAll() = charges.clear()

    internal fun size(): Int = charges.size
}

package ru.ruscrafting.farms.paper.farm.shift

import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import java.util.logging.Level

/**
 * Sole owner of the persisted per-zone automatic-order-cycle pause state.
 *
 * Mutations are transactional: a failed blocking snapshot rolls the in-memory
 * value back so admin feedback never claims a state that cannot survive a
 * restart.
 */
internal class FarmOrderCycleController(
    private val port: WorksiteRuntimePort,
    private val persistBlocking: () -> Unit,
) {
    private val pausedZones = mutableSetOf<String>()

    fun replace(zoneIds: Collection<String>) {
        pausedZones.clear()
        pausedZones += zoneIds
    }

    fun retain(zoneIds: Set<String>) {
        pausedZones.retainAll(zoneIds)
    }

    fun isPaused(zoneId: String): Boolean = zoneId in pausedZones

    fun snapshot(): Set<String> = pausedZones.toSet()

    fun set(zoneId: String, paused: Boolean): Boolean {
        val changed = if (paused) pausedZones.add(zoneId) else pausedZones.remove(zoneId)
        if (!changed) return true
        return runCatching(persistBlocking).fold(
            onSuccess = { true },
            onFailure = { failure ->
                if (paused) pausedZones.remove(zoneId) else pausedZones.add(zoneId)
                port.log(Level.SEVERE, "Could not persist farm order cycle state for $zoneId paused=$paused", failure)
                false
            },
        )
    }

    /** Internal phase transitions may resume a zone and persist in their normal batch. */
    fun resumeInMemory(zoneId: String) {
        pausedZones.remove(zoneId)
    }
}

package ru.ruscrafting.farms.paper.farm.shift

import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/**
 * Sole owner of the persisted per-zone automatic-order-cycle pause state.
 *
 * Mutations are serialized per zone while their asynchronous durable snapshot
 * is in flight. A failed write rolls the in-memory value back.
 */
internal class FarmOrderCycleController(
    private val port: WorksiteRuntimePort,
    private val persistAsync: () -> CompletableFuture<Unit>,
) {
    private val pausedZones = mutableSetOf<String>()
    private val pendingZones = mutableSetOf<String>()

    fun replace(zoneIds: Collection<String>) {
        pendingZones.clear()
        pausedZones.clear()
        pausedZones += zoneIds
    }

    fun retain(zoneIds: Set<String>) {
        pausedZones.retainAll(zoneIds)
    }

    fun clearPending() {
        pendingZones.clear()
    }

    fun isPaused(zoneId: String): Boolean = zoneId in pausedZones

    fun snapshot(): Set<String> = pausedZones.toSet()

    fun set(zoneId: String, paused: Boolean): Boolean {
        if (zoneId in pendingZones) return false
        val changed = if (paused) pausedZones.add(zoneId) else pausedZones.remove(zoneId)
        if (!changed) return true
        pendingZones += zoneId
        val token = port.lifecycleToken()
        runCatching(persistAsync).getOrElse { CompletableFuture.failedFuture(it) }.whenComplete { _, failure ->
            port.runSync(token) {
                pendingZones.remove(zoneId)
                if (failure == null) return@runSync
                if (paused) pausedZones.remove(zoneId) else pausedZones.add(zoneId)
                port.log(Level.SEVERE, "Could not persist farm order cycle state for $zoneId paused=$paused", failure)
            }
        }
        return true
    }

    /** Internal phase transitions may resume a zone and persist in their normal batch. */
    fun resumeInMemory(zoneId: String) {
        pausedZones.remove(zoneId)
    }
}

package ru.ruscrafting.farms.api

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.Collections
import java.util.UUID

/**
 * Emitted after a farm, lumbermill, or mine shift reaches its real completed
 * state. The snapshot is immutable so consumers can process it asynchronously.
 */
class WorkShiftCompletedEvent(
    val eventId: String,
    val kind: String,
    contributors: Set<UUID>,
    val zoneId: String,
) : Event(false) {
    init {
        require(eventId.matches(EVENT_ID)) { "Invalid work shift event id" }
        require(kind in KINDS) { "Invalid work shift kind: $kind" }
        require(zoneId.matches(ZONE_ID)) { "Invalid worksite zone id: $zoneId" }
    }

    val contributors: Set<UUID> = Collections.unmodifiableSet(contributors.toSet())

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val EVENT_ID = Regex("[a-z0-9_.:-]{1,120}")
        private val ZONE_ID = Regex("[a-z0-9_-]{1,48}")
        private val KINDS = setOf("farm", "lumber", "mine")
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

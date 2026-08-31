package ru.ruscrafting.farms.paper.farm

import org.bukkit.Location
import ru.ruscrafting.farms.paper.FarmRuntime

/** Sole owner and lookup index for the currently active farm-zone runtimes. */
internal class FarmRuntimeRegistry {
    private var runtimes: List<FarmRuntime> = emptyList()

    fun replace(next: Collection<FarmRuntime>) {
        runtimes = next.toList()
    }

    /** Applies reloadable settings without invalidating references held by an active incident. */
    fun reconfigure(next: Collection<FarmRuntime>) {
        val current = runtimes.associateBy { it.settings.id }
        val candidates = next.toList()
        require(current.keys == candidates.mapTo(linkedSetOf()) { it.settings.id }) {
            "Changing farm zone topology requires a full plugin restart"
        }
        runtimes = candidates.map { candidate ->
            requireNotNull(current[candidate.settings.id]).apply {
                settings = candidate.settings
                region = candidate.region
                orders = candidate.orders
                orderList = candidate.orderList
                rules = candidate.rules
                state = candidate.state
            }
        }
    }

    fun snapshot(): List<FarmRuntime> = runtimes

    fun at(location: Location): FarmRuntime? = runtimes.firstOrNull { it.region.contains(location) }

    fun byId(zoneId: String): FarmRuntime? = runtimes.firstOrNull { it.settings.id == zoneId }

    val size: Int get() = runtimes.size
}

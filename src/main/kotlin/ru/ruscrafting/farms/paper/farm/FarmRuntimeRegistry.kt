package ru.ruscrafting.farms.paper.farm

import org.bukkit.Location
import ru.ruscrafting.farms.paper.FarmRuntime

/** Sole owner and lookup index for the currently active farm-zone runtimes. */
internal class FarmRuntimeRegistry {
    private var runtimes: List<FarmRuntime> = emptyList()

    fun replace(next: Collection<FarmRuntime>) {
        runtimes = next.toList()
    }

    fun snapshot(): List<FarmRuntime> = runtimes

    fun at(location: Location): FarmRuntime? = runtimes.firstOrNull { it.region.contains(location) }

    fun byId(zoneId: String): FarmRuntime? = runtimes.firstOrNull { it.settings.id == zoneId }

    val size: Int get() = runtimes.size
}

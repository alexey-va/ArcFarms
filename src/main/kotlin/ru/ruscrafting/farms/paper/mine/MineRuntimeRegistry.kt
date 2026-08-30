package ru.ruscrafting.farms.paper.mine

import org.bukkit.Location

internal class MineRuntimeRegistry {
    private var runtimes: List<MineRuntime> = emptyList()

    val size: Int get() = runtimes.size

    fun replace(next: List<MineRuntime>) {
        require(next.map { it.settings.id }.distinct().size == next.size) { "Duplicate mine runtime id" }
        runtimes = next.toList()
    }

    fun snapshot(): List<MineRuntime> = runtimes.toList()

    fun byId(zoneId: String): MineRuntime? = runtimes.firstOrNull { it.settings.id == zoneId }

    fun at(location: Location): MineRuntime? = runtimes.firstOrNull { it.region.contains(location) }
}

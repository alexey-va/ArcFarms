package ru.ruscrafting.farms.paper.lumber

import org.bukkit.Location

internal class LumberRuntimeRegistry {
    private var runtimes: List<LumberRuntime> = emptyList()

    val size: Int get() = runtimes.size

    fun replace(next: List<LumberRuntime>) {
        require(next.map { it.settings.id }.distinct().size == next.size) { "Duplicate lumber runtime id" }
        runtimes = next.toList()
    }

    fun reconfigure(next: List<LumberRuntime>) {
        val current = runtimes.associateBy { it.settings.id }
        require(current.keys == next.mapTo(linkedSetOf()) { it.settings.id }) {
            "Changing lumber zone topology requires a full plugin restart"
        }
        runtimes = next.map { candidate ->
            requireNotNull(current[candidate.settings.id]).apply {
                settings = candidate.settings
                region = candidate.region
                station = candidate.station
                cooldownMillis = candidate.cooldownMillis
                state = candidate.state
            }
        }
    }

    fun snapshot(): List<LumberRuntime> = runtimes.toList()

    fun byId(zoneId: String): LumberRuntime? = runtimes.firstOrNull { it.settings.id == zoneId }

    fun at(location: Location): LumberRuntime? = runtimes.firstOrNull { it.region.contains(location) }
}

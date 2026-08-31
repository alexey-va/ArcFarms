package ru.ruscrafting.farms.paper.mine

import org.bukkit.Location

internal class MineRuntimeRegistry {
    private var runtimes: List<MineRuntime> = emptyList()

    val size: Int get() = runtimes.size

    fun replace(next: List<MineRuntime>) {
        require(next.map { it.settings.id }.distinct().size == next.size) { "Duplicate mine runtime id" }
        runtimes = next.toList()
    }

    fun reconfigure(next: List<MineRuntime>) {
        val current = runtimes.associateBy { it.settings.id }
        require(current.keys == next.mapTo(linkedSetOf()) { it.settings.id }) {
            "Changing mine zone topology requires a full plugin restart"
        }
        runtimes = next.map { candidate ->
            requireNotNull(current[candidate.settings.id]).apply {
                settings = candidate.settings
                region = candidate.region
                cooldownMillis = candidate.cooldownMillis
                state = candidate.state
            }
        }
    }

    fun snapshot(): List<MineRuntime> = runtimes.toList()

    fun byId(zoneId: String): MineRuntime? = runtimes.firstOrNull { it.settings.id == zoneId }

    fun at(location: Location): MineRuntime? = runtimes.firstOrNull { it.region.contains(location) }
}

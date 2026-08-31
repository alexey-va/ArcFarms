package ru.ruscrafting.farms.paper

import ru.ruscrafting.farms.config.ArcFarmsConfig

/** Separates live gameplay tuning from construction-time plugin topology. */
internal object ArcFarmsHotReloadPolicy {
    fun validate(current: ArcFarmsConfig, candidate: ArcFarmsConfig) {
        require(candidate.enabled) { "enabled requires a full plugin restart" }
        require(candidate.serverId == current.serverId) { "server-id requires a full plugin restart" }
        require(candidate.network.enabled == current.network.enabled) {
            "network.enabled requires a full plugin restart"
        }
        require(candidate.requiresWorldGuard == current.requiresWorldGuard) {
            "Changing the region gateway requires a full plugin restart"
        }

        require(
            current.farms.associate { it.id to it.reference } ==
                candidate.farms.associate { it.id to it.reference },
        ) { "Changing farm zone ids or regions requires a full plugin restart" }
        require(
            current.lumbermills.associate { it.id to Triple(it.reference, it.station, it.engineVersion) } ==
                candidate.lumbermills.associate { it.id to Triple(it.reference, it.station, it.engineVersion) },
        ) { "Changing lumber zone topology or engine-version requires a full plugin restart" }
        require(
            current.mines.associate { it.id to (it.reference to it.engineVersion) } ==
                candidate.mines.associate { it.id to (it.reference to it.engineVersion) },
        ) { "Changing mine zone topology or engine-version requires a full plugin restart" }
    }
}

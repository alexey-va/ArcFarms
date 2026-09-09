package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC product event bridge; telemetry failures never affect rewards. */
internal object ArcProductTelemetryBridge {
    private val telemetry by lazy { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }

    fun rewardClaimed(playerId: UUID, rewardId: String): Boolean = runCatching {
        telemetry?.recordEvent(playerId, "arcfarms", "farm_reward_claimed", rewardId) == true
    }.getOrDefault(false)
}

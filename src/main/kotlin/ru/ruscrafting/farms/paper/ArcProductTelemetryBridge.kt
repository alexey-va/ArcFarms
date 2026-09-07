package ru.ruscrafting.farms.paper

import java.util.UUID

/** Optional ARC product event bridge; telemetry failures never affect rewards. */
internal object ArcProductTelemetryBridge {
    private val recordMethod = lazy {
        Class.forName("ru.arc.metrics.ExternalProductTelemetryBridge").getMethod(
            "recordEvent", UUID::class.java, String::class.java, String::class.java, String::class.java,
        )
    }

    fun rewardClaimed(playerId: UUID, rewardId: String): Boolean = runCatching {
        recordMethod.value.invoke(null, playerId, "arcfarms", "farm_reward_claimed", rewardId) as Boolean
    }.getOrDefault(false)
}

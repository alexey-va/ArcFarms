package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC audit marker for a confirmed Vault farm reward. */
internal object ArcEconomyAuditBridge {
    private val audit by lazy { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }

    fun mark(playerId: UUID, amount: Double, rewardId: String): String? = markWith(
        gateway = { id, source, action, value, currency, operationId ->
            audit?.markExternalReward(id, source, action, value, currency, operationId)
        },
        playerId = playerId,
        amount = amount,
        rewardId = rewardId,
    )

    fun cancel(playerId: UUID, token: String?) {
        if (token.isNullOrBlank()) return
        runCatching { audit?.cancelAudit(playerId, token) }
    }

    internal fun markWith(gateway: (UUID, String, String, Double, String, String) -> String?, playerId: UUID, amount: Double, rewardId: String): String? =
        runCatching { gateway(playerId, "arcfarms", "farm_reward", amount, "vault", rewardId) }.getOrNull()
}

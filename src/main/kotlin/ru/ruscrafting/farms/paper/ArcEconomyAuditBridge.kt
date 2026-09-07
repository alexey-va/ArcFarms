package ru.ruscrafting.farms.paper

import java.util.UUID

/** Optional ARC audit marker for a confirmed Vault farm reward. */
internal object ArcEconomyAuditBridge {
    private val markMethod = lazy {
        Class.forName("ru.arc.audit.ExternalEconomyAuditBridge").getMethod(
            "markExternalReward", UUID::class.java, String::class.java, String::class.java,
            Double::class.javaPrimitiveType, String::class.java, String::class.java,
        )
    }
    private val cancelMethod = lazy {
        Class.forName("ru.arc.audit.ExternalEconomyAuditBridge").getMethod("cancel", UUID::class.java, String::class.java)
    }

    fun mark(playerId: UUID, amount: Double, rewardId: String): String? = markWith(
        gateway = { id, source, action, value, currency, operationId ->
            markMethod.value.invoke(null, id, source, action, value, currency, operationId) as String?
        },
        playerId = playerId,
        amount = amount,
        rewardId = rewardId,
    )

    fun cancel(playerId: UUID, token: String?) {
        if (token.isNullOrBlank()) return
        runCatching { cancelMethod.value.invoke(null, playerId, token) }
    }

    internal fun markWith(gateway: (UUID, String, String, Double, String, String) -> String?, playerId: UUID, amount: Double, rewardId: String): String? =
        runCatching { gateway(playerId, "arcfarms", "farm_reward", amount, "vault", rewardId) }.getOrNull()
}

package ru.ruscrafting.farms.paper

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC product event bridge; telemetry failures never affect rewards. */
internal object ArcProductTelemetryBridge {
    @Volatile private var telemetry: ArcTelemetryProvider? = null

    /** Resolve ARC's optional service during plugin startup, never on persistence callbacks. */
    fun install() {
        telemetry = runCatching { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }.getOrNull()
    }

    fun rewardClaimed(playerId: UUID, rewardId: String): Boolean = runCatching {
        telemetry?.recordEvent(playerId, "arcfarms", "farm_reward_claimed", rewardId) == true
    }.getOrDefault(false)

    fun worksiteStarted(
        playerId: UUID,
        kind: String,
        zoneId: String,
        sequence: Long,
        subject: String?,
        attributes: Map<String, String> = emptyMap(),
        provider: ArcTelemetryProvider? = telemetry,
    ) = activity(
        playerId, "worksite_started", subject,
        "worksite:$kind:$zoneId:$sequence:started",
        attributes + mapOf("worksite_kind" to kind, "zone_id" to zoneId, "sequence" to sequence.toString(), "outcome" to "started"),
        provider,
    )

    fun worksiteStartRejected(
        playerId: UUID,
        kind: String,
        zoneId: String,
        sequence: Long,
        reason: String,
        provider: ArcTelemetryProvider? = telemetry,
    ) = activity(
        playerId, "worksite_start_rejected", zoneId,
        "worksite:$kind:$zoneId:$sequence:start_rejected:$reason",
        mapOf("worksite_kind" to kind, "zone_id" to zoneId, "sequence" to sequence.toString(), "outcome" to "rejected", "reason" to reason),
        provider,
    )

    fun worksiteCompleted(
        playerId: UUID,
        kind: String,
        zoneId: String,
        sequence: Long,
        startedAt: Long,
        completedAt: Long,
        contribution: Int?,
        subject: String?,
        provider: ArcTelemetryProvider? = telemetry,
    ) {
        val attributes = buildMap {
            put("worksite_kind", kind)
            put("zone_id", zoneId)
            put("sequence", sequence.toString())
            put("outcome", "completed")
            if (startedAt > 0L && completedAt >= startedAt) put("duration_ms", (completedAt - startedAt).toString())
            contribution?.let { put("contribution", it.coerceAtLeast(0).toString()) }
        }
        activity(
            playerId, "worksite_completed", subject,
            "$kind:$zoneId:$sequence:completed:$playerId",
            attributes,
            provider,
        )
    }

    private fun activity(
        playerId: UUID,
        event: String,
        subject: String?,
        operationId: String,
        attributes: Map<String, String>,
        provider: ArcTelemetryProvider?,
    ): Boolean = runCatching {
        provider?.recordActivity(playerId, "arcfarms", event, subject, operationId, attributes.toMap()) == true
    }.getOrDefault(false)
}

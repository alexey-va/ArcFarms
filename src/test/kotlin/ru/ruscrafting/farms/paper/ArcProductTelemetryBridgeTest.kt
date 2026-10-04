package ru.ruscrafting.farms.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

class ArcProductTelemetryBridgeTest : FunSpec({
    test("a persisted worksite completion records one bounded per-player snapshot") {
        val playerId = UUID.randomUUID()
        val collector = ActivityCollector(accept = false)

        ArcProductTelemetryBridge.worksiteCompleted(
            playerId = playerId,
            kind = "mine",
            zoneId = "origin_mine",
            sequence = 2L,
            startedAt = 10_000L,
            completedAt = 73_000L,
            contribution = 8,
            subject = "coal_delivery",
            provider = collector,
        )

        collector.activity shouldBe Activity(
            playerId,
            "arcfarms",
            "worksite_completed",
            "coal_delivery",
            "mine:origin_mine:2:completed:$playerId",
            mapOf(
                "worksite_kind" to "mine",
                "zone_id" to "origin_mine",
                "sequence" to "2",
                "outcome" to "completed",
                "duration_ms" to "63000",
                "contribution" to "8",
            ),
        )
        collector.accepted shouldBe false
        collector.activity!!.operationId!!.length shouldNotBe 0
        (collector.activity!!.operationId!!.length <= 128) shouldBe true
    }

    test("provider errors stay outside the worksite transition") {
        val collector = ActivityCollector(failure = IllegalStateException("optional sink unavailable"))

        val accepted = ArcProductTelemetryBridge.worksiteStartRejected(
            UUID.randomUUID(), "farm", "origin_farm", 4L, "patch_unavailable", collector,
        )

        accepted shouldBe false
        collector.calls shouldBe 1
    }
})

private data class Activity(
    val playerId: UUID,
    val source: String,
    val event: String,
    val subject: String?,
    val operationId: String?,
    val attributes: Map<String, String>,
)

private class ActivityCollector(
    private val accept: Boolean = true,
    private val failure: Throwable? = null,
) : ArcTelemetryProvider {
    var calls = 0
        private set
    var accepted: Boolean? = null
        private set
    var activity: Activity? = null
        private set

    override fun recordActivity(
        playerId: UUID,
        source: String,
        event: String,
        subject: String?,
        operationId: String?,
        attributes: Map<String, String>,
    ): Boolean {
        calls += 1
        failure?.let { throw it }
        this.activity = Activity(playerId, source, event, subject, operationId, attributes.toMap())
        accepted = accept
        return accept
    }
}

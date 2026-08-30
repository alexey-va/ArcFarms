package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.LumberOrderSettings
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.domain.PendingLumberBlock
import ru.ruscrafting.farms.persistence.LumberRecoveryJournal
import java.util.concurrent.CompletableFuture

class LumbermillModuleLifecycleMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("v2 module rebuilds activates and cleans without a second runtime collection") {
        paper.server.addSimpleWorld("world")
        val port = mockk<WorksiteRuntimePort>(relaxed = true)
        val graph = LumbermillComponentGraph(
            paper.createSimplePlugin("LumberModuleTest"),
            CuboidRegionGateway(),
            port,
            clock = { 1_000L },
            journal = EmptyLumberJournal,
        )
        val persisted = LumberShiftState(phase = LumberPhase.FELLING, sequence = 3, orderId = "oak_contract", species = "OAK")

        graph.module.rebuild(listOf(settings()), mapOf("sawmill" to persisted), cooldownMillis = 5_000L)
        graph.module.zoneCount shouldBe 1
        graph.module.activateLoadedState()
        graph.module.cleanup("reload")

        graph.module.states().keys shouldContainExactly setOf("sawmill")
        graph.module.states().getValue("sawmill") shouldBe persisted
        graph.mutableRuntimeCollectionCount shouldBe 1
    }
})

private object EmptyLumberJournal : LumberRecoveryJournal {
    override fun records(): List<PendingLumberBlock> = emptyList()
    override fun containsPosition(positionKey: String): Boolean = false
    override fun prepare(record: PendingLumberBlock): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    override fun remove(recordId: String): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
}

private fun settings(): LumberZoneSettings {
    val reference = ZoneReference("world", null, CuboidBounds(0, 50, 0, 20, 90, 20))
    return LumberZoneSettings(
        id = "sawmill",
        reference = reference,
        station = ZoneReference("world", null, CuboidBounds(5, 50, 5, 10, 60, 10)),
        permission = "arcfarms.lumber",
        fellingQuota = 2,
        processingQuota = 2,
        processingPerUse = 1,
        species = listOf("OAK"),
        stationMaterials = setOf("STONECUTTER"),
        engineVersion = 2,
        orders = listOf(
            LumberOrderSettings(
                id = "oak_contract",
                species = listOf("OAK"),
                fellingRequired = 2,
                skiddingRequired = 1,
                sawingRequired = 2,
                stackingRequired = 1,
                incidentTypes = listOf(
                    LumberIncidentType.WINDTHROW,
                    LumberIncidentType.BARK_BEETLES,
                    LumberIncidentType.SAW_JAM,
                ),
            ),
        ),
        incidentCountMin = 3,
        incidentCountMax = 3,
    )
}

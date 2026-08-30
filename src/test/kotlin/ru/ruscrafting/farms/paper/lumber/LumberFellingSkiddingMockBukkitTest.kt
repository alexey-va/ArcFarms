package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.LumberOrderSettings
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.LumberIncidentType
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.PendingLumberBlock
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.index.LumberIndexDefinition
import ru.ruscrafting.farms.paper.lumber.index.LumberLogTarget
import ru.ruscrafting.farms.paper.lumber.skidding.LumberBundleEffects
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.persistence.LumberRecoveryJournal
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LumberFellingSkiddingMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("indexed logs start a V2 order and a released bundle can be delivered by another player") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..7) for (z in 0..7) world.getBlockAt(x, 63, z).type = Material.DIRT
        val first = world.getBlockAt(2, 64, 2).also { block ->
            block.type = Material.OAK_LOG
        }
        val second = world.getBlockAt(4, 64, 2).also { block ->
            block.type = Material.OAK_LOG
        }
        val playerA = paper.server.addPlayer("LoggerA")
        val playerB = paper.server.addPlayer("LoggerB")
        val effects = RecordingBundleEffects()
        val port = lumberTestPort()
        val graph = testLumbermillComponentGraph(
            paper.createSimplePlugin("LumberSliceTest"),
            CuboidRegionGateway(),
            port,
            clock = { 1_000L },
            journal = ImmediateLumberJournal(),
            bundleEffects = effects,
        )
        graph.module.rebuild(listOf(lumberSliceSettings()), emptyMap(), cooldownMillis = 5_000L)
        val runtime = graph.registry.byId("sawmill")!!
        val definition = LumberIndexDefinition("sawmill", runtime.region, setOf("OAK"))
        graph.index.replaceZone(
            definition,
            listOf(world.getChunkAt(0, 0)),
            listOf(first, second).map { block ->
                LumberLogTarget(WorksitePosition("world", block.x, block.y, block.z), "OAK")
            },
        )

        graph.module.onBreakHigh(BlockBreakEvent(first, playerA)) shouldBe true
        graph.module.onBreakHigh(BlockBreakEvent(second, playerA)) shouldBe true

        runtime.state.phase shouldBe LumberPhase.SKIDDING
        runtime.state.objective!!.targets shouldHaveSize 2
        effects.ground shouldHaveSize 2

        val bundleId = runtime.state.objective!!.targets.first().id
        graph.bundleScene.pickup(runtime, bundleId, playerA) shouldBe true
        runtime.state.objective!!.target(bundleId)!!.status shouldBe ObjectiveTargetStatus.LEASED
        graph.module.releasePlayer(playerA, WorksitePlayerReleaseReason.ZONE_EXIT)
        runtime.state.objective!!.target(bundleId)!!.status shouldBe ObjectiveTargetStatus.AVAILABLE

        graph.bundleScene.pickup(runtime, bundleId, playerB) shouldBe true
        graph.skidding.onMove(playerB.location, runtime.station.bounds.center(runtime.station.world), playerB) shouldBe true
        runtime.state.skidded shouldBe 1
        runtime.state.phase shouldBe LumberPhase.SAWING
        effects.carried shouldBe emptySet()
    }

    test("wrong and unindexed logs remain intact and do not advance the order") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..7) for (z in 0..7) world.getBlockAt(x, 63, z).type = Material.DIRT
        val indexed = world.getBlockAt(2, 64, 2).also { it.type = Material.OAK_LOG }
        val reserve = world.getBlockAt(4, 64, 2).also { it.type = Material.OAK_LOG }
        val unindexed = world.getBlockAt(6, 64, 2).also { it.type = Material.OAK_LOG }
        val wrong = world.getBlockAt(2, 64, 4).also { it.type = Material.BIRCH_LOG }
        val player = paper.server.addPlayer("CarefulLogger")
        val graph = testLumbermillComponentGraph(
            paper.createSimplePlugin("LumberGuardTest"),
            CuboidRegionGateway(),
            lumberTestPort(),
            clock = { 1_000L },
            journal = ImmediateLumberJournal(),
            bundleEffects = RecordingBundleEffects(),
        )
        graph.module.rebuild(listOf(lumberSliceSettings()), emptyMap(), cooldownMillis = 5_000L)
        val runtime = graph.registry.byId("sawmill")!!
        graph.index.replaceZone(
            LumberIndexDefinition("sawmill", runtime.region, setOf("OAK")),
            listOf(world.getChunkAt(0, 0)),
            listOf(indexed, reserve).map { block ->
                LumberLogTarget(WorksitePosition("world", block.x, block.y, block.z), "OAK")
            },
        )

        graph.module.onBreakHigh(BlockBreakEvent(unindexed, player)) shouldBe true
        graph.module.onBreakHigh(BlockBreakEvent(wrong, player)) shouldBe true

        unindexed.type shouldBe Material.OAK_LOG
        wrong.type shouldBe Material.BIRCH_LOG
        runtime.state.phase shouldBe LumberPhase.IDLE
        runtime.state.felled shouldBe 0
    }
})

internal fun lumberTestPort(players: List<Player> = emptyList()): WorksiteRuntimePort {
    val token = mockk<RuntimeTaskSupervisor.Token>()
    return mockk(relaxed = true) {
        every { isOperational() } returns true
        every { hasAccess(any(), any()) } returns true
        every { lifecycleToken() } returns token
        every { runSync(token, any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        every { persistAsync() } returns CompletableFuture.completedFuture(Unit)
        every { players(any()) } returns players
    }
}

internal class ImmediateLumberJournal : LumberRecoveryJournal {
    private val records = linkedMapOf<String, PendingLumberBlock>()

    override fun records(): List<PendingLumberBlock> = records.values.toList()
    override fun containsPosition(positionKey: String): Boolean = records.values.any { it.positionKey == positionKey }
    override fun prepare(record: PendingLumberBlock): CompletableFuture<Unit> {
        records[record.id] = record
        return CompletableFuture.completedFuture(Unit)
    }
    override fun remove(recordId: String): CompletableFuture<Unit> {
        records.remove(recordId)
        return CompletableFuture.completedFuture(Unit)
    }
}

internal class RecordingBundleEffects : LumberBundleEffects {
    val ground = linkedSetOf<String>()
    val carried = linkedSetOf<UUID>()

    override fun showGround(runtime: LumberRuntime, targetId: String, position: WorksitePosition) {
        ground += targetId
    }

    override fun hideGround(runtime: LumberRuntime, targetId: String) {
        ground -= targetId
    }

    override fun showCarried(runtime: LumberRuntime, targetId: String, player: Player) {
        carried += player.uniqueId
    }

    override fun moveCarried(player: Player) = Unit

    override fun hideCarried(playerId: UUID) {
        carried -= playerId
    }

    override fun cleanupZone(zoneId: String) {
        ground.clear()
        carried.clear()
    }
}

internal fun lumberSliceSettings(): LumberZoneSettings = LumberZoneSettings(
    id = "sawmill",
    reference = ZoneReference("world", null, CuboidBounds(0, 50, 0, 20, 90, 20)),
    station = ZoneReference("world", null, CuboidBounds(8, 64, 8, 10, 68, 10)),
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

private fun CuboidBounds.center(world: org.bukkit.World) = org.bukkit.Location(
    world,
    (minX + maxX + 1) / 2.0,
    minY.toDouble(),
    (minZ + maxZ + 1) / 2.0,
)

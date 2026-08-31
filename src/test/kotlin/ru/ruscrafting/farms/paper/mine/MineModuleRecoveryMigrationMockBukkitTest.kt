package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.every
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.MineOrderSettings
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import java.util.concurrent.CompletableFuture

class MineModuleRecoveryMigrationMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("v2 module reconciles every legacy pending block before the affected zone starts") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0)
        val block = world.getBlockAt(2, 64, 2).also { it.type = Material.DEEPSLATE }
        val record = PendingMineBlock(
            id = "old_shafts:legacy-1",
            zoneId = "old_shafts",
            world = "world",
            x = block.x,
            y = block.y,
            z = block.z,
            originalMaterial = "STONE",
            temporaryMaterial = "DEEPSLATE",
            nextMaterial = "IRON_ORE",
            restoreAt = 500L,
        )
        val journal = ImmediateMineJournal(record)
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineMigrationTest"),
            CuboidRegionGateway(),
            mockk<WorksiteRuntimePort>(relaxed = true),
            clock = { 1_000L },
            journal = journal,
        )
        val persisted = MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 2, orderId = "ore_run")

        graph.module.rebuild(listOf(mineV2Settings()), mapOf("old_shafts" to persisted), 5_000L)
        graph.module.canStart("old_shafts") shouldBe false
        graph.module.activateLoadedState()

        graph.recovery.pendingCount shouldBe 0
        graph.module.canStart("old_shafts") shouldBe true
        block.type shouldBe Material.IRON_ORE
        graph.module.states().keys shouldContainExactly setOf("old_shafts")
        graph.mutableRuntimeCollectionCount shouldBe 1
    }

    test("reload completes an accepted recovery callback and releases its position lock") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val block = world.getBlockAt(2, 64, 2).also { it.type = Material.STONE }
        val journal = ImmediateMineJournal()
        val token = mockk<RuntimeTaskSupervisor.Token>()
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { lifecycleToken() } returns token
            every { runSync(token, any()) } returns true
            every { isOperational() } returns true
        }
        val controller = MineBlockRecoveryController(journal, port, port, port, { 1_000L })
        fun record(id: String) = PendingMineBlock(
            id = id,
            zoneId = "old_shafts",
            world = world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            originalMaterial = "STONE",
            temporaryMaterial = "DEEPSLATE",
            nextMaterial = "IRON_ORE",
            restoreAt = 2_000L,
        )

        val prepared = controller.prepare(record("old_shafts:reload-1"), block, Material.STONE) {
            block.type = Material.DEEPSLATE
        }
        prepared.isDone shouldBe false

        controller.beforeReload("config_reload")
        prepared.join() shouldBe false
        controller.processDue(now = 3_000L)
        journal.records() shouldBe emptyList()

        val retried = controller.prepare(record("old_shafts:reload-2"), block, Material.STONE) {
            block.type = Material.DEEPSLATE
        }
        retried.isDone shouldBe false
        controller.beforeReload("test_cleanup")
    }
})

internal class ImmediateMineJournal(vararg initial: PendingMineBlock) : MineRecoveryJournal {
    private val records = initial.associateByTo(linkedMapOf(), PendingMineBlock::id)

    override fun records(): List<PendingMineBlock> = records.values.toList()
    override fun containsPosition(positionKey: String): Boolean = records.values.any { it.positionKey == positionKey }
    override fun prepare(record: PendingMineBlock): CompletableFuture<Unit> {
        check(records.values.none { it.positionKey == record.positionKey })
        records[record.id] = record
        return CompletableFuture.completedFuture(Unit)
    }
    override fun remove(recordId: String): CompletableFuture<Unit> {
        records.remove(recordId)
        return CompletableFuture.completedFuture(Unit)
    }
}

internal fun mineV2Settings() = MineZoneSettings(
    id = "old_shafts",
    priority = 0,
    reference = ZoneReference("world", null, CuboidBounds(0, 50, 0, 20, 90, 20)),
    permission = "arcfarms.mine",
    cartQuota = 4,
    hazardTrigger = 2,
    supportsRequired = 1,
    restoreSeconds = 2,
    temporaryMaterial = "DEEPSLATE",
    baseMaterial = "STONE",
    materialWeights = linkedMapOf("STONE" to 1, "IRON_ORE" to 1),
    engineVersion = 2,
    orders = listOf(
        MineOrderSettings(
            id = "ore_run",
            prospectingRequired = 1,
            miningRequired = 2,
            loadingRequired = 1,
            incidentTypes = listOf(
                MineIncidentType.CAVE_IN,
                MineIncidentType.GAS_LEAK,
                MineIncidentType.FLOODING,
            ),
        ),
    ),
    incidentCountMin = 3,
    incidentCountMax = 3,
)

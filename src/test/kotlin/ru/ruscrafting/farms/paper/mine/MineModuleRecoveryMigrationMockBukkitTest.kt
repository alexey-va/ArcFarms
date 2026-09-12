package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import io.mockk.every
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.MineOrderSettings
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MineShiftEngine
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

    test("activation reindexes a persisted active mining-only map") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        world.getBlockAt(1, 64, 1).type = Material.DEEPSLATE_BRICKS
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineActiveReindexTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(), random = java.util.Random(4),
        )
        graph.module.rebuild(listOf(mineV2Settings().copy(miningOnly = true)), emptyMap(), 5_000L)
        val runtime = graph.registry.byId("old_shafts")!!
        runtime.state = MineShiftEngine.start(
            runtime.state, runtime.nextOrder().domain(), runtime.rules(), 1_000L,
        ).state
        runtime.state.phase shouldBe MinePhase.MINING

        graph.module.activateLoadedState()

        graph.admin.startReindex("old_shafts") shouldBe false
        graph.admin.tickReindex("old_shafts", 262_144) shouldNotBe null
    }

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

    test("ordinary AIR mining recovery does not block a new shift, while event recovery does") {
        val ordinary = PendingMineBlock(
            id = "mine:old_shafts:4:ordinary",
            zoneId = "old_shafts",
            world = "world",
            x = 2,
            y = 64,
            z = 2,
            originalMaterial = "IRON_ORE",
            temporaryMaterial = "AIR",
            nextMaterial = "IRON_ORE",
            restoreAt = 2_000L,
        )
        val journal = ImmediateMineJournal(ordinary)
        val port = mockk<WorksiteRuntimePort>(relaxed = true)
        val controller = MineBlockRecoveryController(journal, port, port, port, { 1_000L })

        controller.canStart("old_shafts") shouldBe true

        journal.prepare(ordinary.copy(
            id = "mine-incident:old_shafts:4:cave_in:0",
            x = 3,
            temporaryMaterial = "COBBLESTONE",
        ))
        controller.canStart("old_shafts") shouldBe false
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

    test("due recovery does not race a journaled block mutation") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val block = world.getBlockAt(2, 64, 2).also { it.type = Material.STONE }
        val journal = ControllableMineJournal()
        val token = mockk<RuntimeTaskSupervisor.Token>()
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { lifecycleToken() } returns token
            every { runSync(token, any()) } answers {
                secondArg<() -> Unit>().invoke()
                true
            }
            every { isOperational() } returns true
        }
        val controller = MineBlockRecoveryController(journal, port, port, port, { 1_000L })
        val record = PendingMineBlock(
            id = "vein:race",
            zoneId = "old_shafts",
            world = world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            originalMaterial = "STONE",
            temporaryMaterial = "STONE",
            nextMaterial = "IRON_ORE",
            restoreAt = 1_000L,
        )

        val prepared = controller.prepare(record, block, Material.STONE) {
            block.type = Material.IRON_ORE
        }

        controller.processDue(now = 1_000L) shouldBe 0
        block.type shouldBe Material.STONE
        journal.records() shouldContainExactly listOf(record)

        journal.completePrepare()
        prepared.join() shouldBe true
        block.type shouldBe Material.IRON_ORE
    }

    test("ore recovery waits for nearby players and never overwrites a manual edit") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        val player = paper.server.addPlayer("NearbyMiner")
        val block = world.getBlockAt(2, 64, 2).also { it.type = Material.AIR }
        player.teleport(Location(world, 2.5, 64.0, 2.5))
        val journal = ImmediateMineJournal(
            PendingMineBlock(
                id = "mine:nearby",
                zoneId = "old_shafts",
                world = world.name,
                x = block.x,
                y = block.y,
                z = block.z,
                originalMaterial = "IRON_ORE",
                temporaryMaterial = "AIR",
                nextMaterial = "IRON_ORE",
                restoreAt = 1_000L,
            ),
        )
        val port = mockk<WorksiteRuntimePort>(relaxed = true)
        val controller = MineBlockRecoveryController(journal, port, port, port, { 1_000L })

        controller.processDue(now = 1_000L) shouldBe 0
        block.type shouldBe Material.AIR
        journal.records().size shouldBe 1

        player.teleport(Location(world, 20.5, 64.0, 20.5))
        // A blocked loaded chunk is retried on the bounded one-second queue,
        // rather than being scanned on every server tick.
        controller.processDue(now = 1_001L) shouldBe 0
        controller.processDue(now = 2_000L) shouldBe 1
        block.type shouldBe Material.IRON_ORE
        journal.records() shouldBe emptyList()

        val edited = world.getBlockAt(3, 64, 2).also { it.type = Material.STONE }
        val editedRecord = PendingMineBlock(
            id = "mine:edited",
            zoneId = "old_shafts",
            world = world.name,
            x = edited.x,
            y = edited.y,
            z = edited.z,
            originalMaterial = "GOLD_ORE",
            temporaryMaterial = "AIR",
            nextMaterial = "GOLD_ORE",
            restoreAt = 1_000L,
        )
        journal.prepare(editedRecord)
        controller.processDue(now = 2_001L) shouldBe 1
        edited.type shouldBe Material.STONE
        journal.records() shouldBe emptyList()
    }

    test("due recovery rotates past a blocked prefix and reaches a later record") {
        val world = paper.server.addSimpleWorld("world")
        world.getChunkAt(0, 0).load()
        world.getChunkAt(1, 0).load()
        val player = paper.server.addPlayer("BlockingMiner")
        val blocked = world.getBlockAt(2, 64, 2).also { it.type = Material.AIR }
        val later = world.getBlockAt(30, 64, 2).also { it.type = Material.AIR }
        player.teleport(Location(world, 2.5, 64.0, 2.5))
        fun record(id: String, block: org.bukkit.block.Block, next: Material) = PendingMineBlock(
            id = id,
            zoneId = "old_shafts",
            world = world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            originalMaterial = next.name,
            temporaryMaterial = Material.AIR.name,
            nextMaterial = next.name,
            restoreAt = 1_000L,
        )
        val blockedRecord = record("mine:blocked", blocked, Material.IRON_ORE)
        val laterRecord = record("mine:later", later, Material.GOLD_ORE)
        val journal = ImmediateMineJournal(blockedRecord, laterRecord)
        val port = mockk<WorksiteRuntimePort>(relaxed = true)
        val controller = MineBlockRecoveryController(journal, port, port, port, { 1_000L })

        controller.processDue(now = 1_000L, budget = 1) shouldBe 0
        later.type shouldBe Material.AIR
        journal.records() shouldContainExactly listOf(blockedRecord, laterRecord)

        controller.processDue(now = 1_001L, budget = 1) shouldBe 1
        later.type shouldBe Material.GOLD_ORE
        journal.records() shouldContainExactly listOf(blockedRecord)
    }
})

private class ControllableMineJournal : MineRecoveryJournal {
    private val records = linkedMapOf<String, PendingMineBlock>()
    private var pending: CompletableFuture<Unit>? = null

    override fun records(): List<PendingMineBlock> = records.values.toList()
    override fun containsPosition(positionKey: String): Boolean = records.values.any { it.positionKey == positionKey }
    override fun prepare(record: PendingMineBlock): CompletableFuture<Unit> {
        records[record.id] = record
        return CompletableFuture<Unit>().also { pending = it }
    }
    override fun remove(recordId: String): CompletableFuture<Unit> {
        records.remove(recordId)
        return CompletableFuture.completedFuture(Unit)
    }
    fun completePrepare() = requireNotNull(pending).complete(Unit)
}

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
            miningMaterials = setOf("IRON_ORE"),
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

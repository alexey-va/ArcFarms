package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.EventExecutor
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.ArcFarmsPlugin
import ru.ruscrafting.farms.paper.ArcFarmsService
import ru.ruscrafting.farms.paper.installArcSidebarHost
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.fixtures.awaitFarmsReady
import ru.ruscrafting.farms.paper.fixtures.drainFarmsPlugin
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import java.util.concurrent.CompletableFuture
import java.io.InputStreamReader
import java.nio.file.Files

class MineNativeBreakingMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.drainFarmsPlugin(); paper.close() }

    test("real listener service and versioned router preserve foreign cancellations and the admitted journal") {
        var now = 1_000L
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal, clock = { now })
        val service = installNativeDispatch(paper, graph)
        val plugin = paper.server.pluginManager.getPlugin("ArcFarms")!!
        var onHigh: (BlockBreakEvent) -> Unit = {}
        var afterHighest: (BlockBreakEvent) -> Unit = {}
        paper.server.pluginManager.registerEvent(BlockBreakEvent::class.java, object : Listener {},
            EventPriority.HIGH, EventExecutor { _, event -> onHigh(event as BlockBreakEvent) }, plugin)
        // Registered after ArcFarms: this executes after its HIGHEST validation, before MONITOR.
        paper.server.pluginManager.registerEvent(BlockBreakEvent::class.java, object : Listener {},
            EventPriority.HIGHEST, EventExecutor { _, event -> afterHighest(event as BlockBreakEvent) }, plugin)
        graph.mining.prewarm()
        journal.completeAll()
        service.protectsTemporaryBlock(blocks[0].location, player) shouldBe false
        val runtime = graph.registry.byId("old_shafts")!!

        onHigh = { it.isCancelled = true }
        val foreignHigh = BlockBreakEvent(blocks[0], player)
        paper.server.pluginManager.callEvent(foreignHigh)
        foreignHigh.isCancelled shouldBe true
        runtime.state.mined shouldBe 0
        blocks[0].type shouldBe Material.IRON_ORE

        onHigh = {}
        afterHighest = { it.isCancelled = true }
        val foreignHighest = BlockBreakEvent(blocks[0], player)
        paper.server.pluginManager.callEvent(foreignHighest)
        foreignHighest.isCancelled shouldBe true
        runtime.state.mined shouldBe 0
        blocks[0].type shouldBe Material.IRON_ORE

        now = 2_999L
        val record = journal.records().single { it.x == blocks[0].x }
        afterHighest = {
            it.isCancelled shouldBe false
            now = 3_000L
            graph.recovery.processDue(now)
            graph.recovery.restoreNow(blocks[0]).getNow(true) shouldBe false
            graph.recovery.retireUnusedMiningPreparation(record)
            graph.recovery.isAdmittedMiningBreak(record) shouldBe true
        }
        val accepted = BlockBreakEvent(blocks[0], player).also { it.expToDrop = 7 }
        paper.server.pluginManager.callEvent(accepted)
        accepted.isCancelled shouldBe false
        accepted.isDropItems shouldBe false
        accepted.expToDrop shouldBe 0
        runtime.state.mined shouldBe 1
        service.onBreakMonitor(accepted)
        runtime.state.mined shouldBe 1
        journal.records().contains(record) shouldBe true
        blocks[0].type shouldBe Material.IRON_ORE // MockBukkit does not execute Paper's native removal.
    }

    test("a journal becoming ready during an already cancelled event never reopens that event") {
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal)
        val event = BlockBreakEvent(blocks[0], player)
        graph.module.onBreakLowest(event)
        event.isCancelled shouldBe true
        journal.complete()
        graph.module.onBreakHigh(event)
        event.isCancelled shouldBe true
        graph.module.onBreakMonitor(event) shouldBe false
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 0
        blocks[0].type shouldBe Material.IRON_ORE
    }

    test("damage journals first then native break is uncancelled without scheduled block resync") {
        val journal = NativeBreakJournal()
        val fixture = nativeFixture(paper, journal)
        val (graph, player, blocks, port) = fixture
        val ore = blocks.first()
        clearMocks(port, answers = false, recordedCalls = true)
        val damage = BlockDamageEvent(player, ore, player.inventory.itemInMainHand, false).also { it.isCancelled = true }
        graph.module.onBlockDamage(damage) shouldBe true
        damage.isCancelled shouldBe false
        damage.instaBreak shouldBe false
        graph.recovery.isPreparedMiningBreak(journal.records().single()) shouldBe false
        ore.type shouldBe Material.IRON_ORE
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 0
        verify(exactly = 0) { port.runLater(any<Long>(), any()) }
        verify(exactly = 0) { port.runLater(any(), any(), any()) }

        journal.complete()
        graph.recovery.isPreparedMiningBreak(journal.records().single()) shouldBe true
        val event = BlockBreakEvent(ore, player).also { it.expToDrop = 7 }
        graph.module.onBreakLowest(event)
        graph.module.onBreakHigh(event) shouldBe true
        event.isCancelled shouldBe false
        event.isDropItems shouldBe false
        event.expToDrop shouldBe 0
        // Paper performs removal/tool wear after all handlers. The plugin does neither here.
        ore.type shouldBe Material.IRON_ORE
        graph.module.onBreakMonitor(event) shouldBe true
        graph.module.onBreakMonitor(event) shouldBe false
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 1
        ore.type shouldBe Material.IRON_ORE
        player.inventory.itemInMainHand shouldBe ItemStack(Material.IRON_PICKAXE)
    }

    test("pending or failed writes and late completion never remove a cancelled block") {
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal)
        val ore = blocks.first()
        val cold = BlockBreakEvent(ore, player)
        graph.module.onBreakLowest(cold)
        graph.module.onBreakHigh(cold)
        cold.isCancelled shouldBe true
        journal.fail()
        ore.type shouldBe Material.IRON_ORE
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 0
        graph.mining.prepareHit(player, ore) shouldBe true
        journal.complete()
        ore.type shouldBe Material.IRON_ORE
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 0
        graph.module.onBreakMonitor(cold) shouldBe false
        graph.breakManagedOre(ore, player).isCancelled shouldBe false
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 1
    }

    test("nearby preparations batch eight distinct records and do not rewrite warm records") {
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal, dense = true)
        graph.mining.prewarm()
        journal.records().size shouldBe 8
        journal.batches shouldBe 1
        journal.records().map { it.id }.distinct().size shouldBe 8
        blocks.all { it.type == Material.IRON_ORE } shouldBe true
        journal.complete()
        repeat(100) {
            graph.mining.prewarm()
            journal.completeAll()
        }
        journal.records().size shouldBe 256
        val batches = journal.batches
        repeat(10) { graph.mining.prewarm() }
        journal.batches shouldBe batches
        // Even an immediate/creative-shaped break is warm before its first damage event.
        val prepared = journal.records().first()
        val block = player.world.getBlockAt(prepared.x, prepared.y, prepared.z)
        val event = BlockBreakEvent(block, player)
        graph.module.onBreakLowest(event)
        graph.module.onBreakHigh(event)
        event.isCancelled shouldBe false
    }

    test("later cancellation and another miner cannot duplicate or exceed the last resource quota") {
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal, quota = 2)
        val runtime = graph.registry.byId("old_shafts")!!
        runtime.state = runtime.state.copy(mined = 1, cart = 1, minedByMaterial = mapOf("IRON" to 1))
        graph.mining.prewarm()
        journal.completeAll()
        val cancelled = BlockBreakEvent(blocks[0], player)
        graph.module.onBreakLowest(cancelled)
        graph.module.onBreakHigh(cancelled)
        cancelled.isCancelled shouldBe false
        cancelled.isCancelled = true // A later HIGHEST listener rejects this event.
        graph.module.onBreakMonitor(cancelled) shouldBe false
        runtime.state.mined shouldBe 1

        val accepted = graph.breakManagedOre(blocks[0], player)
        accepted.isCancelled shouldBe false
        graph.module.onBreakMonitor(accepted) shouldBe false
        val other = paper.server.addPlayer("OtherMiner")
        other.teleport(player.location)
        other.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        val denied = BlockBreakEvent(blocks[1], other)
        graph.module.onBreakLowest(denied)
        graph.module.onBreakHigh(denied)
        denied.isCancelled shouldBe true
        runtime.state.mined shouldBe 2
        blocks[1].type shouldBe Material.IRON_ORE
    }

    test("unfinished writes occupy the 256 cap across expiry phase changes and reload cancellation") {
        var now = 1_000L
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal, clock = { now }, dense = true)
        val runtime = graph.registry.byId("old_shafts")!!
        repeat(32) { graph.mining.prewarm() }
        journal.records().size shouldBe 256
        journal.batches shouldBe 32

        repeat(3) {
            now += 5_001L
            runtime.state = runtime.state.copy(phase = MinePhase.INCIDENT)
            graph.mining.prewarm()
            runtime.state = runtime.state.copy(phase = MinePhase.MINING, sequence = runtime.state.sequence + 1)
            repeat(40) { graph.mining.prewarm() }
            journal.records().size shouldBe 256
            journal.batches shouldBe 32
        }
        val expired = BlockBreakEvent(blocks.first(), player)
        graph.module.onBreakLowest(expired)
        graph.module.onBreakHigh(expired)
        expired.isCancelled shouldBe true
        graph.module.beforeReload("test_pending_storage")
        repeat(40) { graph.mining.prewarm() }
        journal.records().size shouldBe 256
        journal.batches shouldBe 32

        journal.completeAll()
        journal.records() shouldBe emptyList()
        blocks.all { it.type == Material.IRON_ORE } shouldBe true
        runtime.state.mined shouldBe 0
        repeat(16) { graph.mining.prewarm() }
        journal.records().isNotEmpty() shouldBe true // Terminal acknowledgement releases slots.
    }

    test("normal restoration starts at actual break and restart retains the durable AIR repair") {
        var now = 1_000L
        val journal = NativeBreakJournal()
        val (graph, player, blocks, port) = nativeFixture(paper, journal, clock = { now })
        graph.mining.prepareHit(player, blocks[0])
        journal.complete()
        now = 2_000L
        graph.breakManagedOre(blocks[0], player).isCancelled shouldBe false
        graph.module.beforeReload("test_healthy_regrowth")
        player.teleport(Location(player.world, 40.5, 64.0, 40.5))
        graph.recovery.processDue(3_000L) shouldBe 0
        blocks[0].type shouldBe Material.AIR
        graph.recovery.processDue(3_999L) shouldBe 0
        graph.recovery.processDue(4_000L) shouldBe 1
        blocks[0].type shouldBe Material.IRON_ORE

        now = 5_000L
        player.teleport(Location(player.world, 4.5, 64.0, 4.5))
        graph.mining.prepareHit(player, blocks[1])
        journal.complete()
        now = 6_000L
        graph.breakManagedOre(blocks[1], player).isCancelled shouldBe false
        player.teleport(Location(player.world, 40.5, 64.0, 40.5))
        val restarted = MineBlockRecoveryController(journal, port, port, port, { now })
        restarted.processDue(7_000L) shouldBe 1
        blocks[1].type shouldBe Material.IRON_ORE
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 2
        journal.records() shouldBe emptyList()
    }

    test("expired and stale lifecycle preparations never authorize a native break") {
        var now = 1_000L
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal, clock = { now })
        graph.mining.prepareHit(player, blocks[0])
        journal.complete()
        now = 3_000L
        val expired = BlockBreakEvent(blocks[0], player)
        graph.module.onBreakLowest(expired)
        graph.module.onBreakHigh(expired)
        expired.isCancelled shouldBe true
        journal.records().size shouldBe 1 // New intent is still pending.
        graph.module.beforeReload("test_reload")
        journal.completeAll()
        blocks[0].type shouldBe Material.IRON_ORE
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 0
        journal.records() shouldBe emptyList()
    }

    test("preparation completion does not load a chunk that unloaded during the journal write") {
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal)
        graph.mining.prepareHit(player, blocks[0])
        val world = player.world
        player.teleport(Location(world, 40.5, 64.0, 40.5))
        world.unloadChunk(0, 0, false) shouldBe true

        journal.complete()

        world.isChunkLoaded(0, 0) shouldBe false
        journal.records() shouldBe emptyList()
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 0
    }

    test("a lease expiring after HIGHEST still accounts its already accepted event exactly once") {
        var now = 1_000L
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal, clock = { now })
        graph.mining.prepareHit(player, blocks[0])
        journal.complete()
        now = 2_999L
        val event = BlockBreakEvent(blocks[0], player)
        graph.module.onBreakLowest(event)
        graph.module.onBreakHigh(event)
        event.isCancelled shouldBe false
        now = 3_000L
        graph.module.onBreakMonitor(event) shouldBe true
        graph.module.onBreakMonitor(event) shouldBe false
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 1
    }

    test("far unused preparations are retired in bounded passes without changing ore") {
        var now = 1_000L
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal, clock = { now }, dense = true, restoreSeconds = 30)
        repeat(40) { graph.mining.prewarm(); journal.completeAll() }
        journal.records().size shouldBe 256
        player.teleport(Location(player.world, 40.5, 64.0, 40.5))
        now += 5_001L
        graph.mining.prewarm()
        journal.records().size shouldBe 240
        repeat(15) { graph.mining.prewarm() }
        journal.records() shouldBe emptyList()
        blocks.all { it.type == Material.IRON_ORE } shouldBe true
    }

    test("an incident journal at the same position is never borrowed for native mining") {
        val journal = NativeBreakJournal()
        val (graph, player, blocks) = nativeFixture(paper, journal)
        val ore = blocks.first()
        val foreign = PendingMineBlock("mine-incident:old_shafts:1:foreign:0", "old_shafts", "world",
            ore.x, ore.y, ore.z, "IRON_ORE", "AIR", "IRON_ORE", 3_000L)
        journal.prepare(foreign)
        journal.complete()
        graph.recovery.isPreparedMiningBreak(foreign) shouldBe false
        val event = BlockBreakEvent(ore, player)
        graph.module.onBreakLowest(event)
        graph.module.onBreakHigh(event)
        event.isCancelled shouldBe true
        graph.registry.byId("old_shafts")!!.state.mined shouldBe 0
        journal.records() shouldBe listOf(foreign)
    }
})

private data class NativeFixture(
    val graph: MineComponentGraph,
    val player: Player,
    val blocks: List<Block>,
    val port: WorksiteRuntimePort,
)

/** Boot the real listener/service/router; only the generation's component graph uses the controlled journal. */
private fun installNativeDispatch(paper: MockBukkitTestRuntime, graph: MineComponentGraph): ArcFarmsService {
    paper.installArcSidebarHost()
    paper.server.addSimpleWorld("sp11")
    val plugin = paper.server.pluginManager.loadPlugin(ArcFarmsPlugin::class.java) as ArcFarmsPlugin
    val root = plugin.dataFolder.toPath()
    Files.createDirectories(root.resolve("lang"))
    val loader = MineNativeBreakingMockBukkitTest::class.java.classLoader
    listOf("lang/ru.yml", "lang/en.yml").forEach { name ->
        requireNotNull(loader.getResourceAsStream(name)).use { Files.copy(it, root.resolve(name)) }
    }
    val config = requireNotNull(loader.getResourceAsStream("config.yml")).use {
        YamlConfiguration.loadConfiguration(InputStreamReader(it))
    }
    config.set("network.enabled", false)
    config.set("ui.menu-presentation", "INVENTORY")
    config.set("ui.bossbars", false)
    config.set("ui.particles", false)
    config.set("ui.sounds", false)
    config.set("ui.farm-scoreboard.enabled", false)
    listOf("farm-zones", "lumber-zones", "mine-zones").forEach { section ->
        config.getConfigurationSection(section)?.getKeys(false).orEmpty().forEach { id ->
            config.set("$section.$id.enabled", false)
        }
    }
    config.save(root.resolve("config.yml").toFile())
    paper.server.pluginManager.enablePlugin(plugin)
    paper.awaitFarmsReady(plugin)
    val service = ArcFarmsPlugin::class.java.getDeclaredField("service").also { it.isAccessible = true }
        .get(plugin) as ArcFarmsService
    val versioned = ArcFarmsService::class.java.getDeclaredField("mineModule").also { it.isAccessible = true }
        .get(service) as MineVersionedModule
    MineVersionedModule::class.java.getDeclaredField("delegate").also { it.isAccessible = true }.set(versioned, graph.module)
    return service
}

private fun nativeFixture(
    paper: MockBukkitTestRuntime,
    journal: MineRecoveryJournal,
    clock: () -> Long = { 1_000L },
    quota: Int = 100,
    dense: Boolean = false,
    restoreSeconds: Int = 2,
): NativeFixture {
    val world = paper.server.addSimpleWorld("world")
    val player = paper.server.addPlayer("NativeMiner")
    player.teleport(Location(world, 5.5, 64.0, 5.5))
    player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
    val settings = mineV2Settings().copy(miningOnly = true, restoreSeconds = restoreSeconds,
        orders = mineV2Settings().orders.map { it.copy(miningRequired = quota,
            miningMaterials = emptySet(), miningResources = setOf("IRON"), resourceRequirements = mapOf("IRON" to quota)) })
    val port = immediateMinePort().also {
        every { it.guarded(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
    }
    val graph = testMineComponentGraph(paper.createSimplePlugin("NativeMine"), CuboidRegionGateway(), port,
        clock = clock, journal = journal)
    graph.module.rebuild(listOf(settings), emptyMap(), 5_000L)
    val runtime = graph.registry.byId(settings.id)!!
    val blocks = if (dense) (1..10).flatMap { x -> (1..10).flatMap { z -> (64..67).map { y ->
        world.getBlockAt(x, y, z).also { it.type = Material.IRON_ORE }
    } } } else listOf(world.getBlockAt(3, 64, 3), world.getBlockAt(4, 64, 3)).onEach { it.type = Material.IRON_ORE }
    graph.index.replaceZone(MineIndexDefinition(settings.id, runtime.region, runtime.mineableMaterials),
        listOf(world.getChunkAt(0, 0)), blocks.map {
            MineIndexedTarget(WorksitePosition("world", it.x, it.y, it.z), setOf(MineAnchorRole.MINEABLE))
        })
    graph.module.tick(clock())
    runtime.state.phase shouldBe MinePhase.MINING
    return NativeFixture(graph, player, blocks, port)
}

private class NativeBreakJournal : MineRecoveryJournal {
    private val records = linkedMapOf<String, PendingMineBlock>()
    private val pending = ArrayDeque<Pair<List<PendingMineBlock>, CompletableFuture<Unit>>>()
    var batches = 0
        private set
    override fun records() = records.values.toList()
    override fun containsPosition(positionKey: String) = records.values.any { it.positionKey == positionKey }
    override fun prepare(record: PendingMineBlock) = prepareAll(listOf(record))
    override fun prepareAll(records: List<PendingMineBlock>): CompletableFuture<Unit> {
        records.forEach { check(!containsPosition(it.positionKey)); this.records[it.id] = it }
        batches++
        return CompletableFuture<Unit>().also { pending.addLast(records to it) }
    }
    override fun remove(recordId: String): CompletableFuture<Unit> {
        records.remove(recordId)
        return CompletableFuture.completedFuture(Unit)
    }
    fun complete() { pending.removeFirst().second.complete(Unit) }
    fun completeAll() { while (pending.isNotEmpty()) complete() }
    fun fail() {
        val (failed, future) = pending.removeFirst()
        failed.forEach { records.remove(it.id) }
        future.completeExceptionally(IllegalStateException("Test disk failure"))
    }
}

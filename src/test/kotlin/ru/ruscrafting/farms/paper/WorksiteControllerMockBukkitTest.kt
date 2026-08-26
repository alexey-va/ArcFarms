package ru.ruscrafting.farms.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineRules
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import java.util.UUID
import java.util.concurrent.CompletableFuture

class WorksiteControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var server: ServerMock
    lateinit var world: WorldMock
    lateinit var player: PlayerMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        world = server.addSimpleWorld("worksites")
        player = server.addPlayer("Worker")
        player.teleport(world.spawnLocation)
    }

    afterEach { paper.close() }

    test("lumbermill completes a full felling and processing lifecycle on Bukkit events") {
        var now = 1_000L
        val port = testPort()
        val controller = LumbermillController(CuboidRegionGateway(), testLocale(), port, { now })
        controller.rebuild(listOf(lumberSettings()), emptyMap(), cooldownMillis = 30_000L)

        listOf(1, 2).forEach { x ->
            val block = world.getBlockAt(x, 64, 1).apply { type = Material.OAK_LOG }
            val event = BlockBreakEvent(block, player)
            controller.onBreakHigh(event) shouldBe true
            event.isCancelled shouldBe false
            controller.onBreakMonitor(event) shouldBe true
            now += 100L
        }

        controller.states().getValue("sawmill").phase shouldBe LumberPhase.PROCESSING
        val station = world.getBlockAt(11, 64, 11).apply { type = Material.CRAFTING_TABLE }
        repeat(2) {
            val event = PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                player.inventory.itemInMainHand,
                station,
                BlockFace.UP,
                EquipmentSlot.HAND,
            )
            controller.onInteract(event, station, player) shouldBe true
            event.isCancelled shouldBe true
            now += 100L
        }

        val completed = controller.states().getValue("sawmill")
        completed.phase shouldBe LumberPhase.COOLDOWN
        completed.felled shouldBe 2
        completed.processed shouldBe 2
        verify(exactly = 1) { port.recordCompletion(ActivityKind.LUMBER, any()) }
        verify(exactly = 1) { port.complete(ActivityKind.LUMBER, any(), any()) }
    }

    test("lumbermill rejects another species without losing the selected order") {
        val port = testPort()
        val controller = LumbermillController(CuboidRegionGateway(), testLocale(), port, { 1_000L })
        controller.rebuild(listOf(lumberSettings()), emptyMap(), cooldownMillis = 30_000L)
        val block = world.getBlockAt(1, 64, 1).apply { type = Material.BIRCH_LOG }

        controller.onBreakMonitor(BlockBreakEvent(block, player)) shouldBe true

        controller.states().getValue("sawmill").let { state ->
            state.phase shouldBe LumberPhase.FELLING
            state.species shouldBe "OAK"
            state.felled shouldBe 0
        }
        verify(exactly = 1) { port.sendActionBar(player, MessageKey.LUMBER_WRONG_SPECIES, any()) }
    }

    test("mine journals a real block mutation and restores it after the deadline") {
        var now = 5_000L
        val journal = ControlledMineJournal()
        val port = testPort()
        val controller = MineController(
            CuboidRegionGateway(), testLocale(), journal, port, { now }, java.util.Random(7), NoOpMineBlockEffects,
        )
        controller.rebuild(listOf(mineSettings()), emptyMap(), cooldownMillis = 30_000L)
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        world.getChunkAt(0, 0).load()
        val block = world.getBlockAt(2, 64, 2).apply { type = Material.STONE }

        controller.onBreakHigh(BlockBreakEvent(block, player)) shouldBe true

        block.type shouldBe Material.DEEPSLATE
        controller.pendingBlockCount shouldBe 1
        controller.states().getValue("mine").let { state ->
            state.phase shouldBe MinePhase.MINING
            state.cart shouldBe 1
        }
        journal.records() shouldHaveSize 1

        now += 2_100L
        controller.tick(now)

        block.type shouldBe Material.STONE
        controller.pendingBlockCount shouldBe 0
        journal.records() shouldBe emptyList()
    }

    test("mine keeps a restored position reserved until journal retirement succeeds") {
        var now = 5_000L
        val journal = ControlledMineJournal(failRemove = true)
        val port = testPort()
        val controller = MineController(
            CuboidRegionGateway(), testLocale(), journal, port, { now }, java.util.Random(7), NoOpMineBlockEffects,
        )
        controller.rebuild(listOf(mineSettings()), emptyMap(), cooldownMillis = 30_000L)
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        world.getChunkAt(0, 0).load()
        val block = world.getBlockAt(2, 64, 2).apply { type = Material.STONE }

        controller.onBreakHigh(BlockBreakEvent(block, player))
        now += 2_100L
        controller.tick(now)

        block.type shouldBe Material.STONE
        controller.pendingBlockCount shouldBe 1
        journal.records() shouldHaveSize 1

        controller.onBreakHigh(BlockBreakEvent(block, player))
        journal.prepareCalls shouldBe 1

        journal.failRemove = false
        controller.tick(now)
        controller.pendingBlockCount shouldBe 0
        journal.records() shouldBe emptyList()
    }

    test("a pending mine position cannot be prepared twice") {
        val journal = ControlledMineJournal()
        val port = testPort()
        val controller = MineController(
            CuboidRegionGateway(), testLocale(), journal, port, { 5_000L }, java.util.Random(7), NoOpMineBlockEffects,
        )
        controller.rebuild(listOf(mineSettings()), emptyMap(), cooldownMillis = 30_000L)
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        val block = world.getBlockAt(2, 64, 2).apply { type = Material.STONE }

        controller.onBreakHigh(BlockBreakEvent(block, player))
        block.type = Material.STONE
        controller.onBreakHigh(BlockBreakEvent(block, player))

        journal.prepareCalls shouldBe 1
        verify(exactly = 1) { port.sendActionBar(player, MessageKey.MINE_REGENERATING, any()) }
    }

    test("mine journal failure leaves the world untouched and tells the player") {
        val journal = ControlledMineJournal(failPrepare = true)
        val port = testPort()
        val controller = MineController(
            CuboidRegionGateway(), testLocale(), journal, port, { 5_000L }, java.util.Random(7), NoOpMineBlockEffects,
        )
        controller.rebuild(listOf(mineSettings()), emptyMap(), cooldownMillis = 30_000L)
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        val block = world.getBlockAt(2, 64, 2).apply { type = Material.STONE }

        controller.onBreakHigh(BlockBreakEvent(block, player))

        block.type shouldBe Material.STONE
        controller.states().getValue("mine").phase shouldBe MinePhase.IDLE
        controller.pendingBlockCount shouldBe 0
        journal.records() shouldBe emptyList()
        verify(exactly = 1) { port.sendChat(player, MessageKey.MINE_JOURNAL_FAILED, any()) }
    }

    test("mine support interaction resolves the hazard and leaving the zone extracts the cart") {
        val port = testPort()
        val controller = MineController(
            CuboidRegionGateway(), testLocale(), ControlledMineJournal(), port, { 5_000L },
            java.util.Random(7), NoOpMineBlockEffects,
        )
        val hazard = MineShiftState(
            phase = MinePhase.HAZARD,
            sequence = 1,
            cart = 2,
            contributors = mapOf(player.uniqueId to 2),
        )
        controller.rebuild(listOf(mineSettings()), mapOf("mine" to hazard), cooldownMillis = 30_000L)
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        player.isSneaking = true
        val support = world.getBlockAt(2, 64, 2).apply { type = Material.COBBLESTONE }
        val interaction = PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_BLOCK,
            player.inventory.itemInMainHand,
            support,
            BlockFace.UP,
            EquipmentSlot.HAND,
        )

        controller.onInteract(interaction, support, player) shouldBe true
        controller.states().getValue("mine").let { state ->
            state.phase shouldBe MinePhase.MINING
            state.hazardResolved shouldBe true
        }

        val extraction = MineShiftState(
            phase = MinePhase.EXTRACTION,
            sequence = 1,
            cart = 4,
            supports = 1,
            hazardResolved = true,
            contributors = mapOf(player.uniqueId to 5),
        )
        controller.rebuild(listOf(mineSettings()), mapOf("mine" to extraction), cooldownMillis = 30_000L)
        val inside = org.bukkit.Location(world, 2.0, 64.0, 2.0)
        val outside = org.bukkit.Location(world, 20.0, 64.0, 20.0)

        controller.onMove(inside, outside, player) shouldBe true
        controller.states().getValue("mine").phase shouldBe MinePhase.COOLDOWN
        verify(exactly = 1) { port.complete(ActivityKind.MINE, any(), any()) }
    }

    test("mine access is denied before a journal reservation or world mutation") {
        val journal = ControlledMineJournal()
        val port = testPort(accessible = false)
        val controller = MineController(
            CuboidRegionGateway(), testLocale(), journal, port, { 5_000L }, java.util.Random(7), NoOpMineBlockEffects,
        )
        controller.rebuild(listOf(mineSettings()), emptyMap(), cooldownMillis = 30_000L)
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        val block = world.getBlockAt(2, 64, 2).apply { type = Material.STONE }

        controller.onBreakHigh(BlockBreakEvent(block, player)) shouldBe true

        block.type shouldBe Material.STONE
        journal.prepareCalls shouldBe 0
        verify(exactly = 1) { port.sendChat(player, MessageKey.ZONE_LOCKED, any()) }
    }

    test("an async mine prepare completion rejected by a new lifecycle never mutates the block") {
        val journal = ControlledMineJournal(autoCompletePrepare = false)
        val port = testPort(acceptSync = false)
        val controller = MineController(
            CuboidRegionGateway(), testLocale(), journal, port, { 5_000L }, java.util.Random(7), NoOpMineBlockEffects,
        )
        controller.rebuild(listOf(mineSettings()), emptyMap(), cooldownMillis = 30_000L)
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        val block = world.getBlockAt(2, 64, 2).apply { type = Material.STONE }

        controller.onBreakHigh(BlockBreakEvent(block, player))
        journal.completePrepare()

        block.type shouldBe Material.STONE
        controller.pendingBlockCount shouldBe 0
        journal.records() shouldBe emptyList()
    }

    test("a prepare that finishes after shutdown is retired without touching the world") {
        val journal = ControlledMineJournal(autoCompletePrepare = false)
        val port = testPort(operational = false)
        val controller = MineController(
            CuboidRegionGateway(), testLocale(), journal, port, { 5_000L }, java.util.Random(7), NoOpMineBlockEffects,
        )
        controller.rebuild(listOf(mineSettings()), emptyMap(), cooldownMillis = 30_000L)
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        val block = world.getBlockAt(2, 64, 2).apply { type = Material.STONE }

        controller.onBreakHigh(BlockBreakEvent(block, player))
        journal.completePrepare()

        block.type shouldBe Material.STONE
        controller.pendingBlockCount shouldBe 0
        journal.records() shouldBe emptyList()
    }

    test("mine reload validation keeps active state and pending recovery attached to a configured zone") {
        val journal = ControlledMineJournal().apply {
            add(
                PendingMineBlock(
                    id = "mine:pending",
                    zoneId = "mine",
                    world = "worksites",
                    x = 1,
                    y = 64,
                    z = 1,
                    originalMaterial = "STONE",
                    temporaryMaterial = "DEEPSLATE",
                    nextMaterial = "STONE",
                    restoreAt = 10_000L,
                ),
            )
        }
        val active = MineShiftEngine.start(MineShiftState(), MineRules(4, 2, 1, 30_000L), 1_000L).state

        shouldThrow<IllegalArgumentException> {
            MineController.validateReload(emptyList(), mapOf("mine" to active), journal)
        }
        MineController.validateReload(listOf(mineSettings()), mapOf("mine" to active), journal)
    }

    test("lumber reload validation rejects removing the selected species") {
        val active = LumberShiftState(phase = LumberPhase.FELLING, species = "OAK")

        shouldThrow<IllegalArgumentException> {
            LumbermillController.validateReload(
                listOf(lumberSettings().copy(species = listOf("BIRCH"))),
                mapOf("sawmill" to active),
            )
        }
    }
})

private fun testLocale(): ArcFarmsLocale = mockk(relaxed = true) {
    every { text(any()) } answers { Component.text(firstArg<Any?>()?.toString().orEmpty()) }
    every { render(any(), any(), any()) } answers { Component.text(firstArg<MessageKey>().path) }
    every { renderPath(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
}

private fun testPort(
    acceptSync: Boolean = true,
    accessible: Boolean = true,
    operational: Boolean = true,
): WorksiteRuntimePort {
    val supervisor = RuntimeTaskSupervisor(TestTaskScheduler()).apply(RuntimeTaskSupervisor::activate)
    return mockk(relaxed = true) {
        every { isOperational() } returns operational
        every { hasAccess(any(), any()) } returns accessible
        every { allowInteraction(any(), any()) } returns true
        every { lifecycleToken() } returns supervisor.token()
        every { runSync(any(), any()) } answers {
            if (acceptSync) secondArg<() -> Unit>().invoke()
            acceptSync
        }
        every { guarded(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
    }
}

private fun lumberSettings() = LumberZoneSettings(
    id = "sawmill",
    reference = ZoneReference("worksites", null, CuboidBounds(0, 60, 0, 8, 70, 8)),
    station = ZoneReference("worksites", null, CuboidBounds(10, 60, 10, 12, 70, 12)),
    permission = "arcfarms.use.lumber",
    fellingQuota = 2,
    processingQuota = 2,
    processingPerUse = 1,
    species = listOf("OAK"),
    stationMaterials = setOf("CRAFTING_TABLE"),
)

private fun mineSettings() = MineZoneSettings(
    id = "mine",
    priority = 0,
    reference = ZoneReference("worksites", null, CuboidBounds(0, 60, 0, 8, 70, 8)),
    permission = "arcfarms.use.mine",
    cartQuota = 4,
    hazardTrigger = 2,
    supportsRequired = 1,
    restoreSeconds = 2,
    temporaryMaterial = "DEEPSLATE",
    baseMaterial = "STONE",
    materialWeights = linkedMapOf("STONE" to 1),
)

private class ControlledMineJournal(
    private val autoCompletePrepare: Boolean = true,
    private val failPrepare: Boolean = false,
    var failRemove: Boolean = false,
) : MineRecoveryJournal {
    private val entries = linkedMapOf<String, PendingMineBlock>()
    private var pendingPrepare: CompletableFuture<Unit>? = null
    var prepareCalls: Int = 0
        private set

    override fun records(): List<PendingMineBlock> = entries.values.toList()

    override fun containsPosition(positionKey: String): Boolean = entries.values.any { it.positionKey == positionKey }

    override fun prepare(record: PendingMineBlock): CompletableFuture<Unit> {
        prepareCalls++
        check(record.id !in entries)
        check(!containsPosition(record.positionKey))
        entries[record.id] = record
        if (failPrepare) {
            entries.remove(record.id)
            return CompletableFuture.failedFuture(IllegalStateException("journal unavailable"))
        }
        return if (autoCompletePrepare) {
            CompletableFuture.completedFuture(Unit)
        } else {
            CompletableFuture<Unit>().also { pendingPrepare = it }
        }
    }

    override fun remove(recordId: String): CompletableFuture<Unit> {
        val removed = entries.remove(recordId) ?: return CompletableFuture.completedFuture(Unit)
        if (failRemove) {
            entries[recordId] = removed
            return CompletableFuture.failedFuture(IllegalStateException("journal retirement unavailable"))
        }
        return CompletableFuture.completedFuture(Unit)
    }

    fun add(record: PendingMineBlock) {
        entries[record.id] = record
    }

    fun completePrepare() {
        requireNotNull(pendingPrepare).complete(Unit)
    }
}

private object NoOpMineBlockEffects : MineBlockEffects {
    override fun captureDrops(
        block: org.bukkit.block.Block,
        tool: ItemStack,
        player: org.bukkit.entity.Player,
    ): List<ItemStack> = emptyList()

    override fun deliverRewards(
        player: org.bukkit.entity.Player,
        block: org.bukkit.block.Block,
        drops: List<ItemStack>,
        experience: Int,
        toolSlot: Int,
        toolSnapshot: ItemStack,
    ) = Unit
}

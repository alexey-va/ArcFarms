package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.PendingMineBlock
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.MineBlockEffects
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import java.util.concurrent.CompletableFuture

class MineProspectingMiningMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("prospection reveals a vein and only indexed targets mutate after journal success") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Prospector").also {
            it.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        }
        val prospects = listOf(world.getBlockAt(1, 64, 1), world.getBlockAt(2, 64, 1)).onEach { it.type = Material.STONE }
        val vein = (3..6).map { x -> world.getBlockAt(x, 64, 1).also { it.type = Material.IRON_ORE } }
        val route = (1..4).map { x -> world.getBlockAt(x, 63, 3).also { it.type = Material.STONE } }
        val unindexed = world.getBlockAt(7, 64, 1).also { it.type = Material.IRON_ORE }
        val journal = DeferredMineJournal()
        val effects = RecordingMineEffects()
        val port = immediateMinePort()
        val startValues = slot<(Player) -> Map<String, Component>>()
        every {
            port.broadcast(
                any(), MessageKey.MINE_STARTED, any(), Sound.BLOCK_IRON_DOOR_OPEN, true, capture(startValues),
            )
        } just Runs
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineVerticalSliceTest"),
            CuboidRegionGateway(),
            port,
            clock = { 1_000L },
            journal = journal,
            random = java.util.Random(7),
            blockEffects = effects,
        )
        graph.module.rebuild(listOf(mineV2Settings()), emptyMap(), 5_000L)
        val runtime = graph.registry.byId("old_shafts")!!
        val definition = MineIndexDefinition(
            runtime.settings.id,
            runtime.region,
            setOf(Material.STONE, Material.IRON_ORE),
        )
        graph.index.replaceZone(
            definition,
            listOf(world.getChunkAt(0, 0)),
            prospects.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.PROSPECT)) } +
                vein.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.MINEABLE)) } +
                route.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.RAIL)) },
        )

        val inspect = PlayerInteractEvent(
            player, Action.RIGHT_CLICK_BLOCK, player.inventory.itemInMainHand,
            prospects.first(), BlockFace.UP, EquipmentSlot.HAND,
        )
        graph.module.onInteract(inspect, prospects.first(), player) shouldBe true

        startValues.isCaptured shouldBe true
        startValues.captured(player)["route"] shouldBe Component.text("old_shafts")
        runtime.state.phase shouldBe MinePhase.MINING
        runtime.state.objective!!.targets shouldHaveSize 4

        graph.module.onBreakHigh(BlockBreakEvent(unindexed, player)) shouldBe true
        unindexed.type shouldBe Material.IRON_ORE
        runtime.state.mined shouldBe 0

        graph.module.onBreakHigh(BlockBreakEvent(vein.first(), player)) shouldBe true
        vein.first().type shouldBe Material.IRON_ORE
        effects.toolWearCalls shouldBe 0

        journal.completePrepare()

        vein.first().type shouldBe Material.DEEPSLATE
        effects.toolWearCalls shouldBe 1
        runtime.state.mined shouldBe 1
    }

    test("mine owns breaking and placing throughout its region before phase-specific handling") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Miner").also {
            it.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        }
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineOwnershipTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(), random = java.util.Random(9),
        )
        graph.module.rebuild(listOf(mineV2Settings().copy(miningOnly = true)), emptyMap(), 5_000L)
        val runtime = graph.registry.byId("old_shafts")!!
        runtime.state = ru.ruscrafting.farms.domain.MineShiftEngine.start(
            runtime.state, requireNotNull(runtime.currentOrder() ?: runtime.nextOrder()).domain(), runtime.rules(), 1_000L,
        ).state

        val decoration = world.getBlockAt(8, 64, 8).also { it.type = Material.DEEPSLATE_BRICKS }
        val breakEvent = BlockBreakEvent(decoration, player)
        graph.module.onBreakLowest(breakEvent) shouldBe true
        breakEvent.isCancelled shouldBe true

        val stone = world.getBlockAt(7, 64, 8).also { it.type = Material.STONE }
        val ore = world.getBlockAt(6, 64, 8).also { it.type = Material.IRON_ORE }
        graph.index.replaceZone(
            MineIndexDefinition(runtime.settings.id, runtime.region, runtime.mineableMaterials),
            listOf(world.getChunkAt(0, 0)),
            listOf(
                MineIndexedTarget(stone.position(), setOf(MineAnchorRole.MINEABLE)),
                MineIndexedTarget(ore.position(), setOf(MineAnchorRole.MINEABLE)),
            ),
        )
        graph.module.onBreakHigh(BlockBreakEvent(stone, player)) shouldBe true
        stone.type shouldBe Material.STONE
        runtime.state.mined shouldBe 0
        graph.module.onBreakHigh(BlockBreakEvent(ore, player)) shouldBe true
        ore.type shouldBe Material.DEEPSLATE
        runtime.state.mined shouldBe 1

        val placed = world.getBlockAt(9, 64, 8).also { it.type = Material.COBBLESTONE }
        val placeEvent = BlockPlaceEvent(
            placed, placed.state, world.getBlockAt(9, 63, 8), ItemStack(Material.COBBLESTONE),
            player, true, EquipmentSlot.HAND,
        )
        graph.module.onBlockPlace(placeEvent) shouldBe true
        placeEvent.isCancelled shouldBe true

        val outside = world.getBlockAt(40, 64, 40).also { it.type = Material.STONE }
        val outsideBreak = BlockBreakEvent(outside, player)
        graph.module.onBreakLowest(outsideBreak) shouldBe false
        outsideBreak.isCancelled shouldBe false
    }
})

internal fun immediateMinePort(): WorksiteRuntimePort {
    val token = mockk<RuntimeTaskSupervisor.Token>()
    return mockk(relaxed = true) {
        every { isOperational() } returns true
        every { hasAccess(any(), any()) } returns true
        every { isAdminEditing(any()) } returns false
        every { lifecycleToken() } returns token
        every { runSync(token, any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        every { persistAsync() } returns CompletableFuture.completedFuture(Unit)
    }
}

private class DeferredMineJournal : MineRecoveryJournal {
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

private class RecordingMineEffects : MineBlockEffects {
    var toolWearCalls = 0
    override fun applyToolWear(
        player: Player,
        toolSlot: Int,
        toolSnapshot: ItemStack,
    ) { toolWearCalls++ }
}

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)

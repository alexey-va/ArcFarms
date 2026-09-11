package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmRewardLedgerSnapshot
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.PendingFarmReward
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.MineBlockEffects
import ru.ruscrafting.farms.paper.mine.extraction.MineCartEffects
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteRewardGrantService
import ru.ruscrafting.farms.paper.worksite.WorksiteRewardLedger
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems

class MineV2FullFlowMockBukkitIntegrationTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("one V2 expedition runs from prospecting through one exact reward and completion") {
        val world = paper.server.addSimpleWorld("world")
        for (x in 0..20) for (z in 0..20) world.getBlockAt(x, 63, z).type = Material.STONE
        val prospects = (1..2).map { world.getBlockAt(it, 64, 2).also { block -> block.type = Material.STONE } }
        val ore = (3..6).map { world.getBlockAt(it, 64, 2).also { block -> block.type = Material.IRON_ORE } }
        val rail = (1..5).map { world.getBlockAt(it, 63, 5) }
        val player = paper.server.addPlayer("EndToEndMiner")
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        val port = immediateMinePort()
        val ledger = FullFlowRewardLedger()
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineFullFlowTest"), CuboidRegionGateway(), port,
            clock = { 1_000L }, journal = ImmediateMineJournal(), random = java.util.Random(7),
            serviceItems = FullFlowMineItems(), blockEffects = FullFlowMineBlocks,
            cartEffects = FullFlowMineCartEffects(), rewardGrants = WorksiteRewardGrantService(ledger),
        )
        graph.module.rebuild(listOf(mineV2Settings()), emptyMap(), 5_000L)
        val runtime = graph.registry.byId("old_shafts")!!
        graph.index.replaceZone(
            MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE, Material.IRON_ORE)),
            listOf(world.getChunkAt(0, 0)),
            prospects.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.PROSPECT)) } +
                ore.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.MINEABLE)) } +
                rail.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.RAIL)) },
        )

        graph.admin.start("old_shafts", player) shouldBe true
        graph.admin.start("old_shafts", player) shouldBe false
        graph.prospecting.onInteract(
            PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, player.inventory.itemInMainHand,
                prospects.first(), BlockFace.UP, EquipmentSlot.HAND,
            ),
        ) shouldBe true
        runtime.state.phase shouldBe MinePhase.MINING

        runtime.state.objective!!.targets.take(2).forEach { target ->
            val block = world.getBlockAt(target.position.x, target.position.y, target.position.z)
            graph.mining.onBreakHigh(BlockBreakEvent(block, player)) shouldBe true
        }
        runtime.state.phase shouldBe MinePhase.LOADING

        val crate = runtime.state.objective!!.targets.first()
        graph.loading.pickup(runtime, crate.id, player) shouldBe true
        val delivery = requireNotNull(graph.extraction.deliveryPoint(runtime))
        graph.loading.onMove(delivery.location(world), player) shouldBe true
        runtime.state.phase shouldBe MinePhase.EXTRACTION

        val route = requireNotNull(graph.extraction.routeFor(runtime))
        for (index in 1..route.finalIndex) {
            graph.extraction.push(runtime, player, route.sample(index).location(world)) shouldBe true
        }

        runtime.state.phase shouldBe MinePhase.COOLDOWN
        ledger.pending shouldHaveSize 1
        ledger.pending.single().zoneId shouldBe "mine_old_shafts"
        graph.extraction.push(runtime, player, route.sample(route.finalIndex).location(world)) shouldBe false
        ledger.pending shouldHaveSize 1
        verify(exactly = 1) { port.recordCompletion(ActivityKind.MINE, any()) }
    }
})

private class FullFlowRewardLedger : WorksiteRewardLedger {
    val pending = mutableListOf<PendingFarmReward>()
    override fun snapshot(): FarmRewardLedgerSnapshot = FarmRewardLedgerSnapshot(pending.toList(), emptyMap())
    override fun enqueueRewards(rewards: List<PendingFarmReward>) { pending += rewards.filter { next -> pending.none { it.id == next.id } } }
}

private class FullFlowMineItems : WorksiteServiceItems {
    private val issued = mutableMapOf<java.util.UUID, ServiceItemIdentity>()
    override fun issue(player: Player, identity: ServiceItemIdentity, material: Material, name: net.kyori.adventure.text.Component): ItemStack =
        ItemStack(material).also { item -> issued[player.uniqueId] = identity; player.inventory.addItem(item) }
    override fun consume(player: Player, expected: ServiceItemIdentity): Boolean =
        (issued[player.uniqueId] == expected).also { if (it) issued.remove(player.uniqueId) }
    override fun identity(item: ItemStack?): ServiceItemIdentity? = null
    override fun isServiceItem(item: ItemStack?): Boolean = false
}

private object FullFlowMineBlocks : MineBlockEffects {
    override fun completeExtraction(
        player: Player, block: org.bukkit.block.Block, original: Material, toolSlot: Int, toolSnapshot: ItemStack,
    ) = Unit
}

private class FullFlowMineCartEffects : MineCartEffects {
    private val positions = mutableMapOf<String, WorksitePosition>()
    override fun show(runtime: MineRuntime, position: WorksitePosition, yaw: Float) { positions[runtime.settings.id] = position }
    override fun hide(zoneId: String) { positions.remove(zoneId) }
    override fun position(zoneId: String): WorksitePosition? = positions[zoneId]
}

private fun WorksitePosition.location(world: org.bukkit.World) = Location(world, x + 0.5, y + 1.0, z + 0.5)
private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)

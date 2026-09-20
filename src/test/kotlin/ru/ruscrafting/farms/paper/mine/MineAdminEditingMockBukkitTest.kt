package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.CuboidRegionGateway

class MineAdminEditingMockBukkitTest : FunSpec({
    test("explicit admin editing bypasses mine event guards without clearing foreign cancellation") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("world")
            val admin = paper.server.addPlayer()
            val port = immediateMinePort()
            every { port.isAdminEditing(admin) } returns true
            val graph = testMineComponentGraph(paper.createSimplePlugin("MineEditGuardTest"),
                CuboidRegionGateway(), port, clock = { 1000L }, journal = ImmediateMineJournal())
            graph.module.rebuild(listOf(mineV2Settings()), emptyMap(), 5000L)
            for (x in listOf(2, 200)) {
                val block = world.getBlockAt(x, 64, 2).also { it.type = Material.STONE }
                for (cancelled in listOf(false, true)) {
                    // Paper handles LEFT_CLICK_BLOCK before damage/break. A denial here
                    // prevents the supposedly bypassed BlockBreakEvent from happening.
                    val click = PlayerInteractEvent(admin, Action.LEFT_CLICK_BLOCK,
                        ItemStack(Material.IRON_PICKAXE), block, BlockFace.UP, EquipmentSlot.HAND)
                    val expected = if (cancelled) Event.Result.DENY else Event.Result.DEFAULT
                    click.setUseInteractedBlock(expected)
                    click.setUseItemInHand(expected)
                    graph.module.onInteract(click, block, admin) shouldBe false
                    click.useInteractedBlock() shouldBe expected
                    click.useItemInHand() shouldBe expected
                    val broken = BlockBreakEvent(block, admin).also { it.isCancelled = cancelled }
                    graph.module.onBreakLowest(broken) shouldBe false
                    graph.module.onBreakHigh(broken) shouldBe false
                    broken.isCancelled shouldBe cancelled
                    val damage = BlockDamageEvent(admin, block, ItemStack(Material.IRON_PICKAXE), false).also { it.isCancelled = cancelled }
                    graph.module.onBlockDamage(damage) shouldBe false
                    damage.isCancelled shouldBe cancelled
                }
            }
        } finally { paper.close() }
    }
})

package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
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

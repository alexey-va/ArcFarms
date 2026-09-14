package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.worksite.LateBoundWorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemController

class MinePickaxeSupplyMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("active miners receive one temporary pickaxe and stale copies are replaced") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Miner")
        player.teleport(Location(world, 10.0, 64.0, 10.0))
        player.inventory.heldItemSlot = 4
        val plugin = paper.createSimplePlugin("MinePickaxeSupplyTest")
        val lateItems = LateBoundWorksiteServiceItems()
        val port = immediateMinePort().also {
            every { it.guarded(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
        }
        val graph = testMineComponentGraph(
            plugin, CuboidRegionGateway(), port, clock = { 1_000L },
            journal = ImmediateMineJournal(), serviceItems = lateItems,
        )
        graph.module.rebuild(listOf(mineV2Settings().copy(miningOnly = true)), emptyMap(), 5_000L)
        val serviceItems = WorksiteServiceItemController(plugin, graph.module).also(lateItems::bind)

        graph.module.tick(1_000L)
        graph.module.tick(2_000L)
        player.inventory.storageContents.filterNotNull().count(serviceItems::isServiceItem) shouldBe 1
        player.inventory.storageContents.filterNotNull().single(serviceItems::isServiceItem).type shouldBe Material.IRON_PICKAXE
        player.inventory.getItem(4)?.type shouldBe Material.IRON_PICKAXE

        val runtime = graph.registry.byId("old_shafts")!!
        runtime.state = runtime.state.copy(sequence = 2)
        graph.module.tick(3_000L)
        player.inventory.storageContents.filterNotNull().count(serviceItems::isServiceItem) shouldBe 1
        serviceItems.identity(player.inventory.storageContents.filterNotNull().single(serviceItems::isServiceItem))!!.sequence shouldBe 2L
    }
})

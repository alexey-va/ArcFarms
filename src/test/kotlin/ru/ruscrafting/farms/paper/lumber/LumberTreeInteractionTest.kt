package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.lumber.scene.LumberPresentation
import ru.ruscrafting.farms.paper.worksite.*
import java.util.concurrent.CompletableFuture

class LumberTreeInteractionTest : FunSpec({
    test("a solo worker with a full inventory gets one working axe after freeing a slot") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = TreeInteractionFixture(paper, fullInventory = true)
            val selected = f.player.inventory.heldItemSlot
            f.controller.tick(1_000L)
            f.controller.onBreakHigh(BlockBreakEvent(f.world.getBlockAt(139, 76, 202), f.player)) shouldBe true
            f.player.inventory.itemInMainHand.type shouldBe Material.STONE
            f.controller.state.chopHits shouldBe 0
            verify(exactly = 1) { f.audience.sendChat(f.player, MessageKey.FARM_ACTION_INVENTORY_FULL, any()) }

            f.player.inventory.setItem(selected, null)
            f.controller.tick(2_000L)
            f.player.inventory.itemInMainHand.type shouldBe Material.IRON_AXE
            f.controller.onBreakHigh(BlockBreakEvent(f.world.getBlockAt(139, 76, 202), f.player)) shouldBe true
            f.controller.state.chopHits shouldBe 1
            f.player.inventory.storageContents.count { it?.type == Material.IRON_AXE } shouldBe 1
        }
    }

    test("a trunk hit can reclaim a missing axe before the next tick and counts only once it is held") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = TreeInteractionFixture(paper, fullInventory = true)
            val block = f.world.getBlockAt(139, 76, 202)
            f.player.inventory.setItem(f.player.inventory.heldItemSlot, null)
            f.controller.onBreakHigh(BlockBreakEvent(block, f.player)) shouldBe true
            f.player.inventory.itemInMainHand.type shouldBe Material.IRON_AXE
            f.controller.state.chopHits shouldBe 0
            f.controller.onBreakHigh(BlockBreakEvent(block, f.player)) shouldBe true
            f.controller.state.chopHits shouldBe 1
        }
    }

    test("a native selected-trunk hit outside the small root ray counts once and preserves the block") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = TreeInteractionFixture(paper)
            val block = f.world.getBlockAt(139, 76, 202)
            val damage = BlockDamageEvent(f.player, block, f.player.inventory.itemInMainHand, false)
            f.controller.onDamage(damage) shouldBe true
            damage.isCancelled shouldBe true
            val broken = BlockBreakEvent(block, f.player)
            f.controller.onBreakHigh(broken) shouldBe true
            broken.isCancelled shouldBe true
            f.controller.state.chopHits shouldBe 1
            block.type shouldBe Material.OAK_LOG
        }
    }

    test("wrong forest timber is protected and gives the current action without a title") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = TreeInteractionFixture(paper)
            for (type in listOf(Material.OAK_LOG, Material.OAK_LEAVES)) {
                val block = f.world.getBlockAt(142, 73, 202).apply { this.type = type }
                val event = BlockBreakEvent(block, f.player)
                f.controller.onBreakLowest(event) shouldBe true
                f.controller.onBreakHigh(event) shouldBe true
                event.isCancelled shouldBe true
                block.type shouldBe type
                f.controller.state.chopHits shouldBe 0
            }
            verify { f.audience.sendActionBar(f.player, MessageKey.LUMBER_CHOPPING_HINT, any()) }
            verify { f.access.allowInteraction("lumber-task-hint:${f.player.uniqueId}", 1_500L) }
            verify(exactly = 0) { f.audience.showScreenTitle(any(), any<MessageKey>(), any(), any()) }
        }
    }

    test("forest protection still skips players without beta and timber outside the configured forest") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = TreeInteractionFixture(paper)
            val outside = f.world.getBlockAt(150, 73, 202).apply { type = Material.OAK_LOG }
            val outsideEvent = BlockBreakEvent(outside, f.player)
            f.controller.onBreakLowest(outsideEvent) shouldBe false
            f.controller.onBreakHigh(outsideEvent) shouldBe false
            outsideEvent.isCancelled shouldBe false
            f.player.addAttachment(paper.createSimplePlugin("LumberDeniedTimber"), LumbermillSettings.BETA_PERMISSION, false)
            val selectedEvent = BlockBreakEvent(f.world.getBlockAt(139, 76, 202), f.player)
            f.controller.onBreakLowest(selectedEvent) shouldBe false
            f.controller.onBreakHigh(selectedEvent) shouldBe false
            selectedEvent.isCancelled shouldBe false
            f.controller.state.chopHits shouldBe 0
            verify(exactly = 0) { f.audience.sendActionBar(any(), any(), any()) }
        }
    }
})

private class TreeInteractionFixture(paper: MockBukkitTestRuntime, fullInventory: Boolean = false) {
    private val rules = LumbermillSettings()
    val world = paper.server.addSimpleWorld(rules.world)
    private val plugin = paper.createSimplePlugin("LumberTreeInput")
    val player = paper.server.addPlayer().apply {
        teleport(Location(world, 139.5, 73.0, 204.5))
        addAttachment(plugin, LumbermillSettings.BETA_PERMISSION, true)
    }
    private val root = WorksitePosition(rules.world, 139, 73, 202)
    private val tree = LumberTreeSnapshot(root,
        (0..9).map { LumberBlockSnapshot(root.copy(y = root.y + it), "minecraft:oak_log[axis=y]") },
        listOf(LumberBlockSnapshot(root.copy(x = 140, y = 82), "minecraft:oak_leaves[persistent=false]")))
    private val token = mockk<RuntimeTaskSupervisor.Token>()
    private val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
        every { lifecycleToken() } returns token
        every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
    }
    val access = mockk<WorksiteAccessPort>(relaxed = true) {
        every { allowInteraction(any(), any()) } returns true
    }
    val audience = mockk<WorksiteAudiencePort>(relaxed = true)
    private val locale = mockk<ArcFarmsLocale> {
        every { render(any<MessageKey>(), any(), any()) } answers { Component.text(firstArg<MessageKey>().path) }
    }
    private val itemOwner = mockk<WorksiteServiceItemOwner>()
    private val items = WorksiteServiceItemController(plugin, itemOwner)
    val controller = LumberController(rules, "spawn", locale, access, audience,
        mockk<WorksiteStatePort>(relaxed = true) { every { persistAsync() } returns CompletableFuture.completedFuture(Unit) },
        tasks, items, mockk<LumberPresentation>(relaxed = true) { every { target(any()) } returns null },
        LumberEngine.start(LumberState(), tree, rules.cart, 0), { 1_000L }, mockk(relaxed = true), mockk(relaxed = true),
        incidentTethers = mockk(relaxed = true),
    )

    init {
        every { itemOwner.isActive(any()) } answers { controller.isActive(firstArg()) }
        if (fullInventory) {
            player.inventory.storageContents.indices.forEach { player.inventory.setItem(it, ItemStack(Material.STONE, 64)) }
        }
        tree.logs.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).type = Material.OAK_LOG }
        controller.activate()
        controller.onMove(player)
    }
}

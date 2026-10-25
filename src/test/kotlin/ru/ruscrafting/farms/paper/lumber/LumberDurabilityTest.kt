package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.lumber.scene.LumberPresentation
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture

class LumberDurabilityTest : FunSpec({
    test("accepted axe progress stays unpresented until storage succeeds and survives a retry") {
        MockBukkitTestRuntime.open().use { paper ->
            val rules = LumbermillSettings()
            var now = 1000L
            val world = paper.server.addSimpleWorld(rules.world)
            val player = paper.server.addPlayer().apply { teleport(Location(world, 139.5, 73.0, 202.5)) }
            val plugin = paper.createSimplePlugin("LumberDurability")
            player.addAttachment(plugin, LumbermillSettings.BETA_PERMISSION, true)
            val root = WorksitePosition(rules.world, 139, 73, 202)
            val tree = LumberTreeSnapshot(root, (0..9).map { LumberBlockSnapshot(root.copy(y = 73 + it), "minecraft:oak_log[axis=y]") },
                listOf(LumberBlockSnapshot(root.copy(x = 140, y = 82), "minecraft:oak_leaves[distance=1,persistent=false]")))
            val initial = LumberEngine.start(LumberState(), tree, rules.cart, 1000L)
            tree.logs.forEach { world.getBlockAt(it.position.x, it.position.y, it.position.z).type = org.bukkit.Material.OAK_LOG }
            val writes = ArrayDeque<CompletableFuture<Unit>>()
            val storage = mockk<WorksiteStatePort>(relaxed = true) {
                every { persistAsync() } answers { CompletableFuture<Unit>().also(writes::addLast) }
            }
            val retries = ArrayDeque<() -> Unit>()
            val token = mockk<RuntimeTaskSupervisor.Token>()
            val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
                every { lifecycleToken() } returns token
                every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
                every { runLater(token, any(), any()) } answers { retries.addLast(thirdArg()); true }
            }
            val frames = mutableListOf<LumberVisualView>()
            val scene = mockk<LumberPresentation>(relaxed = true) {
                every { target(any<Player>()) } returns LumberInput(LumberAction.CHOP_TREE)
                every { render(any()) } answers { frames += firstArg<LumberVisualView>() }
            }
            val axeIdentity = ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity(
                    ru.ruscrafting.farms.domain.ActivityKind.LUMBER, LumberEngine.ZONE_ID, 1, 1,
                    ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole("axe"), "lumber_axe")
            val items = mockk<ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems>(relaxed = true) {
                every { identity(any()) } returns axeIdentity
            }
            val controller = LumberController(rules, "spawn", mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), storage, tasks, items, scene, initial, { now }, mockk(relaxed = true), mockk(relaxed = true))
            controller.activate()
            writes.removeFirst().complete(Unit)
            val breaking = BlockBreakEvent(world.getBlockAt(139, 74, 202), player)
            controller.onBreakHigh(breaking) shouldBe true
            controller.state.chopHits shouldBe 1
            controller.updateVisuals()
            frames.last().state.chopHits shouldBe 0
            writes.removeFirst().completeExceptionally(IllegalStateException("simulated storage outage"))
            now += 10_000L
            retries.removeFirst().invoke()
            writes.removeFirst().complete(Unit)
            controller.updateVisuals()
            frames.last().state.chopHits shouldBe 1
            // Permission does not grant destructive native breaking of the selected terrain.
            every { items.identity(any()) } returns null
            val nativeBreak = BlockBreakEvent(world.getBlockAt(139, 73, 202), player)
            controller.onBreakLowest(nativeBreak) shouldBe true
            controller.onBreakHigh(nativeBreak) shouldBe true
            nativeBreak.isCancelled shouldBe true
            controller.state.chopHits shouldBe 1
            every { items.identity(any()) } returns axeIdentity
            repeat(4) { index ->
                now += 500L
                controller.onBreakHigh(BlockBreakEvent(world.getBlockAt(139, 74, 202), player))
                if (index < 3) writes.removeFirst().complete(Unit)
            }
            controller.state.phase shouldBe LumberPhase.FALLING
            now += 10_000L
            writes.removeFirst().complete(Unit)
            controller.updateVisuals()
            frames.last().state.phase shouldBe LumberPhase.FALLING
            frames.last().phaseAgeTicks shouldBe 0
            now += 2_999L
            controller.updateVisuals()
            frames.last().state.phase shouldBe LumberPhase.FALLING
            frames.last().phaseAgeTicks shouldBe 59
            now += 1L
            controller.updateVisuals()
            controller.state.phase shouldBe LumberPhase.DELIMBING
        }
    }
})

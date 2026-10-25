package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.lumber.scene.LumberPresentation
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.concurrent.CompletableFuture

class LumberSawClockTest : FunSpec({
    test("a saw pass starts at frame zero after delayed durable admission and still runs all 500 ticks") {
        MockBukkitTestRuntime.open().use { paper ->
            val rules = LumbermillSettings()
            var now = 1000L
            val world = paper.server.addSimpleWorld(rules.world)
            val player = paper.server.addPlayer().apply { teleport(Location(world, 139.5, 73.0, 202.5)) }
            player.addAttachment(paper.createSimplePlugin("LumberSawClock"), LumbermillSettings.BETA_PERMISSION, true)
            val root = WorksitePosition(rules.world, 139, 73, 202)
            val tree = LumberTreeSnapshot(root, (0..9).map { LumberBlockSnapshot(root.copy(y = 73 + it), "minecraft:oak_log[axis=y]") },
                listOf(LumberBlockSnapshot(root.copy(x = 140, y = 82), "minecraft:oak_leaves[distance=1,persistent=false]")))
            var initial = LumberEngine.start(LumberState(), tree, rules.cart, 0).copy(phase = LumberPhase.FALLING)
            initial = LumberEngine.tick(initial, 3000, 60, (0..2).map { root.copy(x = root.x + 2 + it * 2) })
            repeat(3) { initial = LumberEngine.act(initial, LumberInput(LumberAction.CUT_BRANCH, it), player.uniqueId, 3000, 5) }
            initial = LumberEngine.act(initial, LumberInput(LumberAction.LOAD_CART), player.uniqueId, 3000, 5)
            initial = LumberEngine.act(initial, LumberInput(LumberAction.TAKE_CART), player.uniqueId, 6000, 5)
            initial = LumberEngine.act(initial, LumberInput(LumberAction.LOAD_SAWMILL), player.uniqueId, 6000, 5)
            initial = LumberEngine.tick(initial, 9000, 60, emptyList())
            initial.phase shouldBe LumberPhase.SAWING
            val writes = ArrayDeque<CompletableFuture<Unit>>()
            val storage = mockk<WorksiteStatePort>(relaxed = true) {
                every { persistAsync() } answers { CompletableFuture<Unit>().also(writes::addLast) }
            }
            val token = mockk<RuntimeTaskSupervisor.Token>()
            val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
                every { lifecycleToken() } returns token
                every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
            }
            val frames = mutableListOf<LumberVisualView>()
            val scene = mockk<LumberPresentation>(relaxed = true) {
                every { target(any()) } returns LumberInput(LumberAction.SAW_LEVER)
                every { render(any()) } answers { frames += firstArg<LumberVisualView>() }
            }
            val controller = LumberController(rules, "spawn", mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                storage, tasks, mockk(relaxed = true), scene, initial, { now }, mockk(relaxed = true), mockk(relaxed = true))
            controller.activate()
            writes.removeFirst().complete(Unit)
            val click = mockk<PlayerInteractEvent>(relaxed = true) {
                every { this@mockk.player } returns player
                every { hand } returns EquipmentSlot.HAND
                every { action } returns Action.RIGHT_CLICK_AIR
            }
            controller.onInteract(click) shouldBe true
            now += 10_000L
            controller.updateVisuals()
            frames.last().state.sawRunning shouldBe false
            writes.removeFirst().complete(Unit)
            controller.updateVisuals()
            frames.last().state.sawRunning shouldBe true
            frames.last().sawElapsedTicks shouldBe 0
            now += 24_999L
            controller.updateVisuals()
            controller.state.sawPasses shouldBe 0
            frames.last().sawElapsedTicks shouldBe 499
            now += 1L
            controller.updateVisuals()
            controller.state.sawPasses shouldBe 1
        }
    }
})

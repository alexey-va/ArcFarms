package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.lumber.scene.LumberPresentation
import ru.ruscrafting.farms.paper.lumber.scene.LumberSceneGeometry
import ru.ruscrafting.farms.paper.worksite.*
import java.util.concurrent.CompletableFuture

class LumberBoardCartTest : FunSpec({
    test("board transfer is durable before movement and reacquiring a loaded cart does not replay it") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = BoardCartFixture(paper)
            f.takeBoards()
            f.controller.state.cart shouldBe LumberSceneGeometry.boardCartHome()
            f.controller.state.boardCartLoaded shouldBe false
            f.now += 1_999L
            f.controller.updateVisuals()
            f.controller.state.boardCartLoaded shouldBe false
            f.now += 1L
            f.delayWrites = true
            f.controller.updateVisuals()
            f.controller.state.boardCartLoaded shouldBe true
            f.frames.last().state.boardCartLoaded shouldBe false
            f.player.teleport(f.player.location.add(0.0, 0.0, .4))
            f.controller.updateVisuals()
            f.controller.state.cart shouldBe LumberSceneGeometry.boardCartHome()
            f.delayWrites = false
            f.writes.removeFirst().complete(Unit)
            f.controller.updateVisuals()
            f.frames.last().state.boardCartLoaded shouldBe true
            val moved = requireNotNull(f.controller.state.cart)
            (moved.z > LumberSceneGeometry.boardCartHome().z) shouldBe true
            f.controller.releasePlayer(f.player)
            f.controller.state.phase shouldBe LumberPhase.COLLECTING_BOARDS
            f.controller.state.cart shouldBe moved
            f.controller.state.boardCartLoaded shouldBe true
            f.takeBoards()
            f.controller.state.boardCartLoaded shouldBe true
            f.controller.state.cart shouldBe moved
            f.controller.updateVisuals()
            f.frames.last().state.boardCartLoaded shouldBe true
            f.controller.state.completedRounds shouldBe 0
        }
    }

    test("only the board cart reaching the internal store completes delivery once") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = BoardCartFixture(paper)
            f.takeBoards()
            f.now += 2_000L
            f.controller.updateVisuals()
            val store = f.rules.warehouse
            f.player.teleport(Location(f.world, store.x, store.y, store.z, 0f, 0f))
            f.controller.updateVisuals()
            f.controller.state.phase shouldBe LumberPhase.DELIVERING
            f.controller.state.completedRounds shouldBe 0
            repeat(30) { f.controller.updateVisuals() }
            f.controller.state.phase shouldBe LumberPhase.IDLE
            f.controller.state.completedRounds shouldBe 1
            f.controller.state.boardsCarrier shouldBe null
            f.controller.state.boardCartLoaded shouldBe false
            repeat(3) { f.controller.updateVisuals() }
            f.controller.state.completedRounds shouldBe 1
        }
    }
})

private class BoardCartFixture(paper: MockBukkitTestRuntime) {
    val rules = LumbermillSettings()
    val world = paper.server.addSimpleWorld(rules.world)
    private val home = LumberSceneGeometry.boardCartHome()
    val player = paper.server.addPlayer().apply {
        teleport(Location(world, home.x, home.y, home.z - LumberSceneGeometry.BOARD_CART_FOLLOW_DISTANCE, 0f, 0f))
        addAttachment(paper.createSimplePlugin("LumberBoardCart"), LumbermillSettings.BETA_PERMISSION, true)
    }
    var now = 1_000L
    var delayWrites = false
    val writes = ArrayDeque<CompletableFuture<Unit>>()
    val frames = mutableListOf<LumberVisualView>()
    private val token = mockk<RuntimeTaskSupervisor.Token>()
    private val tasks = mockk<WorksiteTaskPort>(relaxed = true) {
        every { lifecycleToken() } returns token
        every { runSync(token, any()) } answers { secondArg<() -> Unit>().invoke(); true }
    }
    private val storage = mockk<WorksiteStatePort>(relaxed = true) {
        every { persistAsync() } answers {
            if (delayWrites) CompletableFuture<Unit>().also(writes::addLast) else CompletableFuture.completedFuture(Unit)
        }
    }
    private val at = WorksitePosition(rules.world, 139, 73, 202)
    private val tree = LumberTreeSnapshot(at,
        (0..9).map { LumberBlockSnapshot(at.copy(y = at.y + it), "minecraft:oak_log[axis=y]") },
        listOf(LumberBlockSnapshot(at.copy(x = 140, y = 82), "minecraft:oak_leaves[persistent=false]")))
    val controller = LumberController(rules, "spawn", mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        storage, tasks, mockk(relaxed = true), mockk<LumberPresentation>(relaxed = true) {
            every { target(player) } returns LumberInput(LumberAction.COLLECT_BOARDS)
            every { render(any()) } answers { frames += firstArg<LumberVisualView>() }
        }, LumberState(roundId = 1, phase = LumberPhase.COLLECTING_BOARDS, tree = tree, sawPasses = 2),
        { now }, mockk(relaxed = true), mockk(relaxed = true),
    ).also { it.activate() }

    fun takeBoards() {
        controller.onDisplayInteract(player) shouldBe true
        controller.state.phase shouldBe LumberPhase.DELIVERING
        controller.state.boardsCarrier shouldBe player.uniqueId
    }
}

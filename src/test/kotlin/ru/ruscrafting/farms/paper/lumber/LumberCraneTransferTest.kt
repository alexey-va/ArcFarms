package ru.ruscrafting.farms.paper.lumber

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.domain.lumber.*
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.lumber.scene.LumberPresentation
import ru.ruscrafting.farms.paper.worksite.*
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LumberCraneTransferTest : FunSpec({
    test("automatic flight starts after durable admission and completes exactly once after fourteen active seconds") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = CraneFixture(paper)
            val ready = f.controller.state
            for (action in listOf(LumberAction.COLLECT_BOARDS, LumberAction.DELIVER_BOARDS)) {
                f.input = LumberInput(action)
                f.controller.onDisplayInteract(f.player) shouldBe true
                f.controller.state shouldBe ready
            }
            f.input = null
            f.advance(999L)
            f.controller.state.phase shouldBe LumberPhase.COLLECTING_BOARDS
            f.delayWrites = true
            f.advance(1L)
            f.controller.state.phase shouldBe LumberPhase.CRANE_TRANSFER
            f.frames.last().state.phase shouldBe LumberPhase.COLLECTING_BOARDS
            f.advance(10_000L)
            f.frames.last().state.phase shouldBe LumberPhase.COLLECTING_BOARDS
            f.delayWrites = false
            f.writes.removeFirst().complete(Unit)
            f.controller.updateVisuals()
            f.frames.last().state.phase shouldBe LumberPhase.CRANE_TRANSFER
            f.frames.last().phaseAgeTicks shouldBe 0
            f.controller.state.cart shouldBe null
            f.controller.state.boardsCarrier shouldBe null
            f.controller.state.boardCartLoaded shouldBe false
            f.advance(13_999L)
            f.controller.state.phase shouldBe LumberPhase.CRANE_TRANSFER
            f.controller.state.completedRounds shouldBe 0L
            f.frames.last().phaseAgeTicks shouldBe 279
            f.delayWrites = true
            f.advance(1L)
            f.controller.state.phase shouldBe LumberPhase.IDLE
            f.controller.state.completedRounds shouldBe 1L
            f.frames.last().state.completedRounds shouldBe 0L
            f.advance(5_000L)
            f.writes.size shouldBe 1
            f.delayWrites = false
            f.writes.removeFirst().complete(Unit)
            repeat(3) { f.controller.updateVisuals() }
            f.controller.state.completedRounds shouldBe 1L
            f.controller.state.contributors shouldBe ready.contributors
        }
    }

    test("zero participants freeze an in-flight load and returning workers see the remaining flight") {
        MockBukkitTestRuntime.open().use { paper ->
            val f = CraneFixture(paper)
            f.advance(1_000L)
            f.advance(5_000L)
            f.frames.last().phaseAgeTicks shouldBe 100
            val outside = paper.server.addSimpleWorld("outside_crane")
            f.player.teleport(Location(outside, 0.0, 73.0, 0.0))
            f.controller.onMove(f.player) shouldBe false
            val paused = f.controller.state
            f.advance(60_000L)
            f.controller.state shouldBe paused
            f.frames.last().phaseAgeTicks shouldBe 100
            f.player.teleport(Location(f.world, 189.5, 73.0, 198.75))
            f.controller.onMove(f.player) shouldBe true
            f.controller.updateVisuals()
            f.frames.last().phaseAgeTicks shouldBe 100
            f.advance(8_999L)
            f.controller.state.phase shouldBe LumberPhase.CRANE_TRANSFER
            f.advance(1L)
            f.controller.state.completedRounds shouldBe 1L
        }
    }

    test("old manual deliveries recover at output and an interrupted crane explicitly replays from frame zero") {
        val ready = lumberIncidentBoundary(roundId = 7, completedRounds = 4, passes = 2)
            .copy(incidentGeneration = 9, incidentHandledRoundId = 6, contributors = mapOf(UUID(0, 1) to 8))
        for (phase in listOf(LumberPhase.DELIVERING, LumberPhase.COLLECTING_BOARDS, LumberPhase.CRANE_TRANSFER)) {
            MockBukkitTestRuntime.open().use { paper ->
                val legacy = ready.copy(phase = phase, phaseStartedAt = 9_000L,
                    cart = if (phase == LumberPhase.CRANE_TRANSFER) null else LumberPose(ready.tree!!.root.world, 226.5, 74.0, 182.5),
                    boardsCarrier = if (phase == LumberPhase.DELIVERING) UUID(0, 42) else null,
                    boardCartLoaded = phase != LumberPhase.CRANE_TRANSFER)
                val f = CraneFixture(paper, legacy)
                f.controller.state.roundId shouldBe 7L
                f.controller.state.completedRounds shouldBe 4L
                f.controller.state.sawPasses shouldBe 2
                f.controller.state.cart shouldBe null
                f.controller.state.cartCarrier shouldBe null
                f.controller.state.boardsCarrier shouldBe null
                f.controller.state.boardCartLoaded shouldBe false
                if (phase != LumberPhase.CRANE_TRANSFER) {
                    f.controller.state.phase shouldBe LumberPhase.COLLECTING_BOARDS
                    f.advance(1_000L)
                } else f.controller.updateVisuals()
                f.frames.last().phaseAgeTicks shouldBe 0
                f.controller.state.phase shouldBe LumberPhase.CRANE_TRANSFER
                f.advance(13_999L)
                f.controller.state.completedRounds shouldBe 4L
                f.advance(1L)
                f.controller.state.completedRounds shouldBe 5L
                f.controller.state.incidentGeneration shouldBe 9L
                f.controller.state.incidentHandledRoundId shouldBe 6L
                f.controller.state.contributors shouldBe ready.contributors
            }
        }
    }

    test("a due smolder on legacy output cannot be skipped by a long idle before the next visual tick") {
        MockBukkitTestRuntime.open().use { paper ->
            val legacy = lumberIncidentBoundary(roundId = 4, completedRounds = 3, passes = 2)
                .copy(phase = LumberPhase.DELIVERING, boardCartLoaded = true,
                    cart = LumberPose("rc_atelier_cedar_sawmill", 226.5, 74.0, 182.5), boardsCarrier = UUID(0, 42))
            val f = CraneFixture(paper, legacy)
            f.advance(60_000L)
            f.controller.state.phase shouldBe LumberPhase.COLLECTING_BOARDS
            f.controller.state.incident?.type shouldBe LumberIncidentType.SAWDUST_SMOLDER
            f.advance(20_000L)
            f.controller.state.phase shouldBe LumberPhase.COLLECTING_BOARDS
            f.controller.state.completedRounds shouldBe 3L
        }
    }
})

private class CraneFixture(paper: MockBukkitTestRuntime,
    initial: LumberState = lumberIncidentBoundary(roundId = 1, completedRounds = 0, passes = 2),
) {
    private val rules = LumbermillSettings()
    val world = paper.server.addSimpleWorld(rules.world)
    val player = paper.server.addPlayer().apply {
        teleport(Location(world, 189.5, 73.0, 198.75))
        addAttachment(paper.createSimplePlugin("LumberCrane"), LumbermillSettings.BETA_PERMISSION, true)
    }
    var now = 1_000L
    var delayWrites = false
    var input: LumberInput? = null
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
    private val masks = mockk<LumberBlockMask>(relaxed = true) { every { ready(any(), any()) } returns true }
    val controller = LumberController(rules, "spawn", mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        storage, tasks, mockk(relaxed = true), mockk<LumberPresentation>(relaxed = true) {
            every { target(player) } answers { input }
            every { render(any()) } answers { frames += firstArg<LumberVisualView>() }
        }, initial, { now }, mockk(relaxed = true), masks, incidentTethers = mockk(relaxed = true),
    ).also { it.activate(); it.onMove(player) }

    fun advance(millis: Long) { now += millis; controller.updateVisuals() }
}

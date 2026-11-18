package ru.ruscrafting.farms.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.UUID

class WorksiteModuleRegistryTest : FunSpec({
    test("registry routes status tick availability and access by activity kind") {
        val player = mockk<Player>()
        val lumber = module(ActivityKind.LUMBER, "lumber", accessible = true)
        val mine = module(ActivityKind.MINE, "mine", accessible = false)
        val registry = WorksiteModuleRegistry(listOf(lumber, mine))

        registry.statuses().map(ActivityStatus::id) shouldBe listOf("lumber", "mine")
        registry.isAvailable(ActivityKind.LUMBER) shouldBe true
        registry.canAccess(player, ActivityKind.LUMBER) shouldBe true
        registry.canAccess(player, ActivityKind.MINE) shouldBe false
        registry.isAvailable(ActivityKind.FARM) shouldBe false

        registry.tick(42L, guardedTasks())
        verify(exactly = 1) { lumber.tick(42L) }
        verify(exactly = 1) { mine.tick(42L) }
    }

    test("recurring callbacks continue in activity order when one module throws") {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val tasks = guardedTasks(failures)
        val registry = WorksiteModuleRegistry(listOf(
            RecurringModule(ActivityKind.MINE, calls),
            RecurringModule(ActivityKind.LUMBER, calls, failing = true),
            RecurringModule(ActivityKind.FARM, calls),
        ))

        registry.tick(42L, tasks)
        registry.emitGuidance(tasks)
        registry.updateEveryTick(tasks)

        calls shouldBe listOf(
            "FARM:tick:42", "LUMBER:tick:42", "MINE:tick:42",
            "FARM:particles", "LUMBER:particles", "MINE:particles",
            "FARM:fast", "FARM:visuals", "LUMBER:fast", "LUMBER:visuals", "MINE:fast", "MINE:visuals",
        )
        failures shouldBe listOf("tick:LUMBER", "guidance_particles:LUMBER", "fast_tick:LUMBER", "carried_displays:LUMBER")
    }

    test("periodic guidance skips the bar sweep after failure and an explicit refresh still propagates it") {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val tasks = guardedTasks(failures)
        val farm = RecurringModule(ActivityKind.FARM, calls)
        val lumber = RecurringModule(ActivityKind.LUMBER, calls, failing = true)
        val mine = RecurringModule(ActivityKind.MINE, calls)
        val registry = WorksiteModuleRegistry(listOf(farm, lumber, mine))

        registry.updateGuidance(tasks) shouldBe null
        calls shouldBe listOf("FARM:guidance", "LUMBER:guidance", "MINE:guidance")
        failures shouldBe listOf("player_guidance:LUMBER")

        shouldThrow<IllegalStateException> { registry.updateGuidance() }
        lumber.failing = false
        registry.updateGuidance(tasks) shouldBe setOf(farm.bar, lumber.bar, mine.bar)
    }

    test("registry rejects two modules that claim the same activity") {
        shouldThrow<IllegalArgumentException> {
            WorksiteModuleRegistry(listOf(module(ActivityKind.MINE, "a", true), module(ActivityKind.MINE, "b", true)))
        }
    }

    test("registry dispatches optional event capabilities without concrete controller knowledge") {
        val lumber = RoutingModule(ActivityKind.LUMBER, interactionHandled = false)
        val mine = RoutingModule(ActivityKind.MINE, interactionHandled = true)
        val registry = WorksiteModuleRegistry(listOf(lumber, mine))
        val player = mockk<Player>()
        val block = mockk<Block>()
        val breakEvent = mockk<BlockBreakEvent>()
        val interaction = mockk<PlayerInteractEvent>()
        val from = mockk<Location>()
        val to = mockk<Location>()

        registry.onBreakHigh(ActivityKind.MINE, breakEvent) shouldBe true
        lumber.highBreaks shouldBe 0
        mine.highBreaks shouldBe 1

        registry.onBreakMonitor(breakEvent)
        lumber.monitoredBreaks shouldBe 1
        mine.monitoredBreaks shouldBe 1

        registry.onInteract(interaction, block, player) shouldBe true
        lumber.interactions shouldBe 1
        mine.interactions shouldBe 1

        registry.onMove(from, to, player) shouldBe true
        lumber.moves shouldBe 1
        mine.moves shouldBe 1
    }
})

private fun guardedTasks(failures: MutableList<String> = mutableListOf()): WorksiteTaskPort = mockk {
    every { guarded(any(), any()) } answers {
        try {
            secondArg<() -> Unit>().invoke()
        } catch (_: Exception) {
            failures += firstArg<String>()
        }
    }
}

private class RecurringModule(
    override val kind: ActivityKind,
    private val calls: MutableList<String>,
    var failing: Boolean = false,
) : WorksiteModule<Any>, WorksiteFastTickHandler, WorksiteFastVisualHandler, WorksiteGuidanceHandler {
    val bar = ActivityBarKey(UUID(0L, kind.ordinal.toLong()), kind.name)
    override val zoneCount = 1
    override fun states(): Map<String, Any> = emptyMap()
    override fun statuses(): List<ActivityStatus> = emptyList()
    override fun canAccess(player: Player) = true
    override fun tick(now: Long) = record("tick:$now")
    override fun tickFast() = record("fast")
    override fun updateVisuals() = record("visuals")
    override fun emitGuidance() = record("particles")

    override fun updateGuidance(expectedBars: MutableSet<ActivityBarKey>) {
        record("guidance")
        expectedBars += bar
    }

    private fun record(operation: String) {
        calls += "$kind:$operation"
        check(!failing) { "$kind failed during $operation" }
    }
}

private fun module(kind: ActivityKind, id: String, accessible: Boolean): WorksiteModule<Any> = mockk {
    every { this@mockk.kind } returns kind
    every { zoneCount } returns 1
    every { states() } returns emptyMap()
    every { statuses() } returns listOf(ActivityStatus(kind, id, "phase", "0/1"))
    every { tick(any()) } returns Unit
    every { isAvailable() } returns true
    every { canAccess(any()) } returns accessible
}

private class RoutingModule(
    override val kind: ActivityKind,
    private val interactionHandled: Boolean,
) : WorksiteModule<Any>, WorksiteBlockBreakHandler, WorksiteBlockInteractHandler, WorksiteMoveHandler {
    override val zoneCount: Int = 1
    var highBreaks: Int = 0
    var monitoredBreaks: Int = 0
    var interactions: Int = 0
    var moves: Int = 0

    override fun states(): Map<String, Any> = emptyMap()
    override fun statuses(): List<ActivityStatus> = emptyList()
    override fun tick(now: Long) = Unit
    override fun canAccess(player: Player): Boolean = true

    override fun onBreakHigh(event: BlockBreakEvent): Boolean {
        highBreaks++
        return true
    }

    override fun onBreakMonitor(event: BlockBreakEvent): Boolean {
        monitoredBreaks++
        return true
    }

    override fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        interactions++
        return interactionHandled
    }

    override fun onMove(from: Location, to: Location, player: Player): Boolean {
        moves++
        return interactionHandled
    }
}

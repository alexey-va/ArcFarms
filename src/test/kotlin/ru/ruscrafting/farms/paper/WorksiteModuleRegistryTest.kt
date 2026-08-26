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

        registry.tick(42L)
        verify(exactly = 1) { lumber.tick(42L) }
        verify(exactly = 1) { mine.tick(42L) }
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

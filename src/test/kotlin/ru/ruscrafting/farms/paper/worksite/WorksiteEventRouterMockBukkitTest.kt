package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.WorksiteBlockBreakHandler
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.WorksiteModuleRegistry

class WorksiteEventRouterMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("block breaks route once without farm knowing mine or lumber") {
        val farm = RoutingModule(ActivityKind.FARM, handlesBreak = false)
        val lumber = RoutingModule(ActivityKind.LUMBER, handlesBreak = false)
        val mine = RoutingModule(ActivityKind.MINE, handlesBreak = true)
        val registry = WorksiteModuleRegistry(listOf(farm, lumber, mine))
        val router = router(paper, registry)
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Miner")
        val event = BlockBreakEvent(world.getBlockAt(4, 64, 7), player)

        router.onBreakHigh(event)

        farm.breakCalls shouldBe 0
        lumber.breakCalls shouldBe 0
        mine.breakCalls shouldBe 1
    }

    test("quit teleport portal and death each release service items and module leases") {
        val releases = mutableListOf<WorksitePlayerReleaseReason>()
        val participant = RoutingModule(ActivityKind.FARM, release = releases::add)
        val registry = WorksiteModuleRegistry(listOf(participant))
        val router = router(paper, registry)
        val player = paper.server.addPlayer("Worker")

        val reasons = listOf(
            WorksitePlayerReleaseReason.QUIT,
            WorksitePlayerReleaseReason.TELEPORT_OUT,
            WorksitePlayerReleaseReason.PORTAL_OUT,
            WorksitePlayerReleaseReason.DEATH,
        )
        reasons.forEach { router.release(player, it) }

        releases shouldContainExactly reasons
    }
})

private fun router(
    paper: MockBukkitTestRuntime,
    registry: WorksiteModuleRegistry,
): WorksiteEventRouter {
    val items = WorksiteServiceItemController(paper.createSimplePlugin("WorksiteRouterTest"), registry)
    return WorksiteEventRouter(
        registry = registry,
        serviceItems = items,
        participantSafety = WorksiteParticipantSafety(items, listOf(registry)),
    )
}

private class RoutingModule(
    override val kind: ActivityKind,
    private val handlesBreak: Boolean = false,
    private val release: (WorksitePlayerReleaseReason) -> Unit = {},
) : WorksiteModule<Any>, WorksiteBlockBreakHandler, WorksiteParticipantOwner {
    override val zoneCount: Int = 1
    var breakCalls: Int = 0

    override fun states(): Map<String, Any> = emptyMap()
    override fun statuses(): List<ActivityStatus> = emptyList()
    override fun tick(now: Long) = Unit
    override fun canAccess(player: Player): Boolean = true

    override fun onBreakHigh(event: BlockBreakEvent): Boolean {
        breakCalls++
        return handlesBreak
    }

    override fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) = release(reason)
}

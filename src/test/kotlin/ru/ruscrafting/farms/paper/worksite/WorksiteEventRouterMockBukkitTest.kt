package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.WorksiteBlockBreakHandler
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.WorksiteModuleRegistry
import ru.ruscrafting.farms.paper.WorksitePlayerInteractHandler
import ru.ruscrafting.farms.paper.WorksiteTeleportRetention

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

    test("right click air reaches the general player interaction handler") {
        val farm = RoutingModule(ActivityKind.FARM, handlesInteraction = true)
        val registry = WorksiteModuleRegistry(listOf(farm))
        val router = router(paper, registry)
        val player = paper.server.addPlayer("Firefighter")
        val item = ItemStack(Material.CROSSBOW)
        player.inventory.setItemInMainHand(item)
        val event = PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_AIR,
            item,
            null,
            BlockFace.SELF,
            EquipmentSlot.HAND,
        )

        router.onInteract(event) shouldBe true

        farm.interactionCalls shouldBe 1
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

    test("horse dismount teleport keeps worksite participation while real teleport releases it") {
        val releases = mutableListOf<WorksitePlayerReleaseReason>()
        val participant = RoutingModule(ActivityKind.FARM, release = releases::add)
        val router = router(paper, WorksiteModuleRegistry(listOf(participant)))
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("CaravanDriver")
        val destination = player.location.clone().add(1.0, 0.0, 0.0)

        router.onTeleport(
            PlayerTeleportEvent(player, player.location, destination, PlayerTeleportEvent.TeleportCause.DISMOUNT),
        )
        releases shouldBe emptyList()

        router.onTeleport(
            PlayerTeleportEvent(player, destination, world.spawnLocation, PlayerTeleportEvent.TeleportCause.COMMAND),
        )
        releases shouldContainExactly listOf(WorksitePlayerReleaseReason.TELEPORT_OUT)
    }

    test("an off-site activity retains its service items across plugin teleports") {
        val releases = mutableListOf<WorksitePlayerReleaseReason>()
        val participant = RoutingModule(ActivityKind.FARM, retainTeleport = true, release = releases::add)
        val router = router(paper, WorksiteModuleRegistry(listOf(participant)))
        val player = paper.server.addPlayer("CaravanEscort")
        val destination = player.location.clone().add(20.0, 0.0, 0.0)

        router.onTeleport(
            PlayerTeleportEvent(player, player.location, destination, PlayerTeleportEvent.TeleportCause.PLUGIN),
        )

        releases shouldBe emptyList()
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
    private val handlesInteraction: Boolean = false,
    private val retainTeleport: Boolean = false,
    private val release: (WorksitePlayerReleaseReason) -> Unit = {},
) : WorksiteModule<Any>, WorksiteBlockBreakHandler, WorksitePlayerInteractHandler, WorksiteParticipantOwner,
    WorksiteTeleportRetention {
    override val zoneCount: Int = 1
    var breakCalls: Int = 0
    var interactionCalls: Int = 0

    override fun states(): Map<String, Any> = emptyMap()
    override fun statuses(): List<ActivityStatus> = emptyList()
    override fun tick(now: Long) = Unit
    override fun canAccess(player: Player): Boolean = true

    override fun onBreakHigh(event: BlockBreakEvent): Boolean {
        breakCalls++
        return handlesBreak
    }

    override fun onInteract(event: PlayerInteractEvent, player: Player): Boolean {
        interactionCalls++
        return handlesInteraction
    }

    override fun retainOnTeleport(player: Player): Boolean = retainTeleport

    override fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) = release(reason)
}

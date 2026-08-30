package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.extraction.MineCartEffects
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import java.util.UUID

class MineLoadingExtractionMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("ore crates return after exit and extraction cart keeps its last safe route sample") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Hauler")
        val floors = (1..5).map { x -> world.getBlockAt(x, 63, 1).also { it.type = Material.STONE } }
        val items = RecordingMineServiceItems()
        val carts = RecordingMineCartEffects()
        val state = MineShiftState(
            engineVersion = 2,
            phase = MinePhase.LOADING,
            sequence = 1,
            orderId = "ore_run",
            prospected = 1,
            mined = 2,
        )
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineLoadingTest"),
            CuboidRegionGateway(),
            immediateMinePort(),
            clock = { 1_000L },
            journal = ImmediateMineJournal(),
            random = java.util.Random(7),
            serviceItems = items,
            cartEffects = carts,
        )
        graph.module.rebuild(listOf(mineV2Settings()), mapOf("old_shafts" to state), 5_000L)
        items.activeCheck = graph.loading::isActive
        val runtime = graph.registry.byId("old_shafts")!!
        graph.index.replaceZone(
            MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE, Material.IRON_ORE)),
            listOf(world.getChunkAt(0, 0)),
            floors.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.RAIL)) },
        )
        runtime.state = graph.loading.begin(runtime, runtime.state)

        runtime.state.objective!!.targets shouldHaveSize 2
        val crateId = runtime.state.objective!!.targets.first().id
        graph.loading.pickup(runtime, crateId, player) shouldBe true
        runtime.state.objective!!.target(crateId)!!.status shouldBe ObjectiveTargetStatus.LEASED

        graph.loading.releasePlayer(player, WorksitePlayerReleaseReason.ZONE_EXIT) shouldBe true
        runtime.state.objective!!.target(crateId)!!.status shouldBe ObjectiveTargetStatus.AVAILABLE

        graph.loading.pickup(runtime, crateId, player) shouldBe true
        val delivery = requireNotNull(graph.extraction.deliveryPoint(runtime))
        graph.loading.onMove(delivery.location(world), player) shouldBe true
        runtime.state.phase shouldBe MinePhase.EXTRACTION

        val route = requireNotNull(graph.extraction.routeFor(runtime))
        graph.extraction.push(runtime, player, route.sample(1).location(world)) shouldBe true
        runtime.state.routeIndex shouldBe 1
        carts.position(runtime.settings.id) shouldBe route.sample(1)

        graph.extraction.push(runtime, player, Location(world, 19.5, 80.0, 19.5)) shouldBe false
        runtime.state.routeIndex shouldBe 1
        carts.position(runtime.settings.id) shouldBe route.sample(1)
    }
})

private class RecordingMineServiceItems : WorksiteServiceItems {
    private val issued = mutableMapOf<UUID, ServiceItemIdentity>()
    var activeCheck: (ServiceItemIdentity) -> Boolean = { true }
    override fun issue(player: Player, identity: ServiceItemIdentity, material: Material, name: net.kyori.adventure.text.Component): ItemStack {
        check(activeCheck(identity)) { "Service item was issued before its owner became active" }
        issued[player.uniqueId] = identity
        return ItemStack(material).also { player.inventory.addItem(it) }
    }
    override fun consume(player: Player, expected: ServiceItemIdentity): Boolean =
        (issued[player.uniqueId] == expected).also { matched -> if (matched) issued.remove(player.uniqueId) }
    override fun identity(item: ItemStack?): ServiceItemIdentity? = null
    override fun isServiceItem(item: ItemStack?): Boolean = false
}

private class RecordingMineCartEffects : MineCartEffects {
    private val positions = mutableMapOf<String, WorksitePosition>()
    override fun show(runtime: MineRuntime, position: WorksitePosition, yaw: Float) { positions[runtime.settings.id] = position }
    override fun hide(zoneId: String) { positions.remove(zoneId) }
    override fun position(zoneId: String): WorksitePosition? = positions[zoneId]
}

private fun WorksitePosition.location(world: org.bukkit.World) = Location(world, x + 0.5, y + 1.0, z + 0.5)
private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)

package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.ImmediateMineJournal
import ru.ruscrafting.farms.paper.mine.testMineComponentGraph
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.mine.mineV2Settings
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import java.util.UUID

class MineConstructionIncidentsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("missing construction anchors are replaced and abandoned kits cannot block the incident") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Builder")
        val anchors = (1..8).map { x -> world.getBlockAt(x, 64, 2).also { it.type = Material.STONE } }
        val items = ConstructionItems()
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineConstructionTest"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(), serviceItems = items,
        )
        graph.module.rebuild(
            listOf(mineV2Settings()),
            mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")),
            5_000L,
        )
        items.active = graph.caveIn::isActive
        val runtime = graph.registry.byId("old_shafts")!!
        graph.index.replaceZone(
            MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
            listOf(world.getChunkAt(0, 0)),
            anchors.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.SUPPORT)) },
        )

        graph.caveIn.start(runtime, required = 2, now = 1_000L) shouldBe true
        runtime.state.objective!!.targets shouldHaveSize 4
        val invalid = runtime.state.objective!!.targets.first()
        anchors.first { it.position() == invalid.position }.type = Material.AIR
        graph.caveIn.reconcile(runtime) shouldBe 1
        runtime.state.objective!!.targets shouldHaveSize 4
        runtime.state.objective!!.targets.none { it.position == invalid.position } shouldBe true

        graph.caveIn.pickupKit(runtime, player) shouldBe true
        runtime.state.incident!!.serviceLeases.values shouldBe setOf(player.uniqueId)
        graph.caveIn.releasePlayer(player.uniqueId) shouldBe true
        runtime.state.incident!!.serviceLeases shouldBe emptyMap()
        items.issued shouldBe emptyMap()
        runtime.state.objective!!.targets.all { it.status == ObjectiveTargetStatus.AVAILABLE } shouldBe true
    }
})

private class ConstructionItems : WorksiteServiceItems {
    val issued = mutableMapOf<UUID, ServiceItemIdentity>()
    var active: (ServiceItemIdentity) -> Boolean = { true }
    override fun issue(player: Player, identity: ServiceItemIdentity, material: Material, name: net.kyori.adventure.text.Component): ItemStack? {
        if (!active(identity)) return null
        return ItemStack(material).also { player.inventory.addItem(it); issued[player.uniqueId] = identity }
    }
    override fun consume(player: Player, expected: ServiceItemIdentity): Boolean =
        (issued[player.uniqueId] == expected).also { if (it) issued.remove(player.uniqueId) }
    override fun identity(item: ItemStack?): ServiceItemIdentity? = null
    override fun isServiceItem(item: ItemStack?): Boolean = false
}

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)

package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.ImmediateMineJournal
import ru.ruscrafting.farms.paper.mine.MineComponentGraph
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.mine.mineV2Settings
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems

class MineWorldIncidentsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("flood water pauses without players and reconstructs from the durable journal after restart") {
        val world = paper.server.addSimpleWorld("world")
        val pumps = (1..5).map { x -> world.getBlockAt(x, 64, 2).also { it.type = Material.WATER } }
        val journal = ImmediateMineJournal()
        val items = WorldIncidentItems()
        val graph = worldGraph(paper, journal, items, "FloodA")
        val runtime = graph.registry.byId("old_shafts")!!
        index(graph, runtime, pumps.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.PUMP)) })
        items.active = graph.flooding::isActive

        graph.flooding.start(runtime, required = 2, now = 1_000L) shouldBe true
        val initial = graph.flooding.waterPositions(runtime)
        initial.size shouldBe 4
        graph.module.tick(31_000L)
        graph.flooding.waterPositions(runtime) shouldContainExactlyInAnyOrder initial

        val persisted = runtime.state
        val restarted = worldGraph(paper, journal, items, "FloodB", persisted)
        val restartedRuntime = restarted.registry.byId("old_shafts")!!
        index(restarted, restartedRuntime, pumps.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.PUMP)) })
        restarted.flooding.waterPositions(restartedRuntime) shouldContainExactlyInAnyOrder initial

        restarted.module.cleanup("reload")
        initial.all { world.getBlockAt(it.x, it.y, it.z).type == Material.AIR } shouldBe true
    }

    test("power switches keep accepted order progress and temporary lights are fully restored") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Electrician")
        val switches = (1..5).map { x -> world.getBlockAt(x, 64, 5).also { it.type = Material.STONE } }
        val graph = worldGraph(paper, ImmediateMineJournal(), WorldIncidentItems(), "Power")
        val runtime = graph.registry.byId("old_shafts")!!
        index(graph, runtime, switches.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.POWER)) })

        graph.powerFailure.start(runtime, required = 2, now = 1_000L) shouldBe true
        val targets = runtime.state.objective!!.targets
        val lights = graph.powerFailure.lightPositions(runtime)
        lights.size shouldBe 4
        graph.powerFailure.relight(runtime, targets[1].id, player) shouldBe false
        runtime.state.incident!!.progress shouldBe 0
        graph.powerFailure.relight(runtime, targets[0].id, player) shouldBe true
        graph.powerFailure.relight(runtime, targets[1].id, player) shouldBe true
        runtime.state.phase shouldBe MinePhase.MINING
        lights.all { world.getBlockAt(it.x, it.y, it.z).type == Material.AIR } shouldBe true
    }
})

private fun worldGraph(
    paper: MockBukkitTestRuntime,
    journal: ImmediateMineJournal,
    items: WorldIncidentItems,
    name: String,
    state: MineShiftState = MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run"),
): MineComponentGraph = MineComponentGraph(
    paper.createSimplePlugin("MineWorld$name"), CuboidRegionGateway(), immediateMinePort(),
    clock = { 1_000L }, journal = journal, serviceItems = items,
).also { it.module.rebuild(listOf(mineV2Settings()), mapOf("old_shafts" to state), 5_000L) }

private fun index(graph: MineComponentGraph, runtime: ru.ruscrafting.farms.paper.mine.MineRuntime, targets: List<MineIndexedTarget>) {
    graph.index.replaceZone(
        MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
        listOf(runtime.region.world.getChunkAt(0, 0)), targets,
    )
}

private class WorldIncidentItems : WorksiteServiceItems {
    private val issued = mutableMapOf<java.util.UUID, ServiceItemIdentity>()
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

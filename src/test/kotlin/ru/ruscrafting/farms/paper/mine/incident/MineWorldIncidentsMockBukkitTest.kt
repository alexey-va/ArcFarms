package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Levelled
import org.bukkit.entity.Player
import org.bukkit.entity.Interaction
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.ImmediateMineJournal
import ru.ruscrafting.farms.paper.mine.MineComponentGraph
import ru.ruscrafting.farms.paper.mine.testMineComponentGraph
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
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
        val floorPlane = (0..14).flatMap { x -> (1..3).map { z ->
            world.getBlockAt(x, 63, z).also { it.type = Material.STONE }
        } }
        val floors = listOf(1, 4, 7, 10, 13).map { x -> world.getBlockAt(x, 63, 2) }
        val journal = ImmediateMineJournal()
        val items = WorldIncidentItems()
        val graph = worldGraph(paper, journal, items, "FloodA")
        val runtime = graph.registry.byId("old_shafts")!!
        index(graph, runtime, floors.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.NEST)) })
        items.active = graph.flooding::isActive

        graph.flooding.start(runtime, required = 2, now = 1_000L) shouldBe true
        val initial = graph.flooding.waterPositions(runtime)
        (initial.size >= 24) shouldBe true
        initial.all { position -> world.getBlockAt(position.x, position.y - 1, position.z).type == Material.STONE } shouldBe true
        initial.map { position -> (world.getBlockAt(position.x, position.y, position.z).blockData as Levelled).level }
            .let { levels -> (0 in levels && levels.any { it > 0 }) shouldBe true }
        graph.module.tick(31_000L)
        graph.flooding.waterPositions(runtime) shouldContainExactlyInAnyOrder initial

        val persisted = runtime.state
        val restarted = worldGraph(paper, journal, items, "FloodB", persisted)
        val restartedRuntime = restarted.registry.byId("old_shafts")!!
        index(restarted, restartedRuntime, floors.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.NEST)) })
        restarted.flooding.waterPositions(restartedRuntime) shouldContainExactlyInAnyOrder initial

        val player = paper.server.addPlayer("PumpOperator")
        items.active = restarted.flooding::isActive
        restarted.incidentSet.tick(restartedRuntime, 1_001L, emptyList())
        val targetFootprints = restartedRuntime.state.objective!!.targets.take(2)
            .associate { target -> target.id to restartedRuntime.floodFootprint(target.position).toSet() }
        restartedRuntime.state.objective!!.targets.take(2).forEachIndexed { targetIndex, target ->
            repeat(2) {
                val event = PlayerInteractEntityEvent(
                    player,
                    objectiveHitbox(restarted, world, MineIncidentEntityKind.FLOOD_MARKER_HITBOX, target.id),
                    EquipmentSlot.HAND,
                )
                restarted.incidentSet.onInteractEntity(event) shouldBe true
                event.isCancelled shouldBe true
            }
            if (targetIndex == 0) {
                restarted.flooding.reconcile(restartedRuntime)
                val otherWater = targetFootprints.getValue(restartedRuntime.state.objective!!.targets[1].id)
                targetFootprints.getValue(target.id).minus(otherWater)
                    .all { world.getBlockAt(it.x, it.y, it.z).type == Material.AIR } shouldBe true
                otherWater.all { world.getBlockAt(it.x, it.y, it.z).type == Material.WATER } shouldBe true
            }
        }
        items.toolIssues shouldBe 2
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
        graph.incidentSet.tick(runtime, 1_001L, emptyList())
        val targets = runtime.state.objective!!.targets
        val lights = graph.powerFailure.lightPositions(runtime)
        lights.size shouldBe 4
        graph.incidentSet.onInteractEntity(
            PlayerInteractEntityEvent(
                player,
                objectiveHitbox(graph, world, MineIncidentEntityKind.POWER_MARKER_HITBOX, targets[1].id),
                EquipmentSlot.HAND,
            ),
        ) shouldBe true
        runtime.state.incident!!.progress shouldBe 0
        listOf(targets[0], targets[1]).forEach { target ->
            val event = PlayerInteractEntityEvent(
                player,
                objectiveHitbox(graph, world, MineIncidentEntityKind.POWER_MARKER_HITBOX, target.id),
                EquipmentSlot.HAND,
            )
            graph.incidentSet.onInteractEntity(event) shouldBe true
            event.isCancelled shouldBe true
        }
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
): MineComponentGraph = testMineComponentGraph(
    paper.createSimplePlugin("MineWorld$name"), CuboidRegionGateway(), immediateMinePort(),
    clock = { 1_000L }, journal = journal, serviceItems = items,
).also { it.module.rebuild(listOf(mineV2Settings()), mapOf("old_shafts" to state), 5_000L) }

private fun index(graph: MineComponentGraph, runtime: ru.ruscrafting.farms.paper.mine.MineRuntime, targets: List<MineIndexedTarget>) {
    graph.index.replaceZone(
        MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
        listOf(runtime.region.world.getChunkAt(0, 0)), targets,
    )
}

private fun objectiveHitbox(
    graph: MineComponentGraph,
    world: org.bukkit.World,
    kind: MineIncidentEntityKind,
    targetId: String,
): Interaction = world.entities.filterIsInstance<Interaction>().single { entity ->
    graph.objectiveMarkers.identity(entity)?.let { it.kind == kind && it.targetId == targetId } == true
}

private class WorldIncidentItems : WorksiteServiceItems {
    private val issued = mutableMapOf<java.util.UUID, ServiceItemIdentity>()
    var active: (ServiceItemIdentity) -> Boolean = { true }
    var toolIssues: Int = 0
    override fun issue(player: Player, identity: ServiceItemIdentity, material: Material, name: net.kyori.adventure.text.Component): ItemStack? {
        if (!active(identity)) return null
        return ItemStack(material).also { player.inventory.addItem(it); issued[player.uniqueId] = identity }
    }
    override fun issueHeld(
        player: Player,
        identity: ServiceItemIdentity,
        material: Material,
        name: net.kyori.adventure.text.Component,
        customModelData: Int,
        itemModel: org.bukkit.NamespacedKey?,
    ): ItemStack? {
        toolIssues++
        return issue(player, identity, material, name)
    }
    override fun consume(player: Player, expected: ServiceItemIdentity): Boolean =
        (issued[player.uniqueId] == expected).also { if (it) issued.remove(player.uniqueId) }
    override fun identity(item: ItemStack?): ServiceItemIdentity? = null
    override fun isServiceItem(item: ItemStack?): Boolean = false
}

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)

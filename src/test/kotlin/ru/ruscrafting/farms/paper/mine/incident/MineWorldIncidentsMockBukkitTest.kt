package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Levelled
import org.bukkit.Sound
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
        restarted.incidentSet.tick(restartedRuntime, 1_001L, emptyList())
        val target = restartedRuntime.state.objective!!.targets.single()
        val targetFootprint = restartedRuntime.floodFootprint(target.position).toSet()
        val water = world.getBlockAt(
            targetFootprint.first().x,
            targetFootprint.first().y,
            targetFootprint.first().z,
        )
        val feedback = mockk<Player>(relaxed = true)
        every { feedback.uniqueId } returns player.uniqueId
        every { feedback.location } returns player.location
        restarted.flooding.onInteract(
            PlayerInteractEvent(
                feedback, Action.RIGHT_CLICK_BLOCK, ItemStack(Material.AIR),
                water, BlockFace.UP, EquipmentSlot.HAND,
            ),
        ) shouldBe true
        items.toolIssues shouldBe 0
        verify(exactly = 1) { feedback.playSound(any<Location>(), Sound.BLOCK_WATER_AMBIENT, 0.75f, 1.15f) }
        restartedRuntime.state.phase shouldBe MinePhase.MINING
        initial.all { world.getBlockAt(it.x, it.y, it.z).type == Material.AIR } shouldBe true
    }

    test("power switches accept any remaining target and restore temporary lights") {
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
        val powerFeedback = mockk<Player>(relaxed = true)
        every { powerFeedback.uniqueId } returns player.uniqueId
        every { powerFeedback.location } returns player.location
        graph.incidentSet.onInteractEntity(
            PlayerInteractEntityEvent(
                powerFeedback,
                objectiveHitbox(graph, world, MineIncidentEntityKind.POWER_MARKER_HITBOX, targets[1].id),
                EquipmentSlot.HAND,
            ),
        ) shouldBe true
        verify(exactly = 1) { powerFeedback.playSound(any<Location>(), Sound.BLOCK_LEVER_CLICK, 0.75f, 1.0f) }
        runtime.state.incident!!.progress shouldBe 1
        listOf(targets[0]).forEach { target ->
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

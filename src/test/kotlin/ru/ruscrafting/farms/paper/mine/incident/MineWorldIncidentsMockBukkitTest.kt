package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Interaction
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MineWorkingEngine
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowWorksiteSceneCodec
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder
import ru.ruscrafting.farms.paper.mine.ImmediateMineJournal
import ru.ruscrafting.farms.paper.mine.MineComponentGraph
import ru.ruscrafting.farms.paper.mine.testMineComponentGraph
import ru.ruscrafting.farms.paper.mine.prewarmIncidentCandidates
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import ru.ruscrafting.farms.paper.mine.working.MineWorkingLayout
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.mine.mineV2Settings
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedSceneRecord

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
        graph.prewarmIncidentCandidates(runtime)
        graph.flooding.start(runtime, required = 2, now = 1_000L) shouldBe true
        val initial = graph.flooding.waterPositions(runtime)
        (initial.size in 20..30) shouldBe true
        initial.all { position -> world.getBlockAt(position.x, position.y - 1, position.z).type == Material.STONE } shouldBe true
        val source = initial.first()
        val sourceBlock = world.getBlockAt(source.x, source.y, source.z)
        sourceBlock.type shouldBe Material.WATER
        sourceBlock.blockData.asString shouldBe "minecraft:water[level=0]"
        initial.drop(1).all { position -> world.getBlockAt(position.x, position.y, position.z).type == Material.AIR } shouldBe true
        graph.module.tick(31_000L)
        graph.flooding.waterPositions(runtime) shouldContainExactlyInAnyOrder initial
        sourceBlock.blockData.asString shouldBe "minecraft:water[level=0]"

        val persisted = runtime.state
        val restartedPort = immediateMinePort()
        val restarted = worldGraph(paper, journal, items, "FloodB", persisted, restartedPort)
        val restartedRuntime = restarted.registry.byId("old_shafts")!!
        index(restarted, restartedRuntime, floors.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.NEST)) })
        restarted.flooding.waterPositions(restartedRuntime) shouldContainExactlyInAnyOrder initial

        val player = paper.server.addPlayer("PumpOperator")
        restarted.incidentSet.tick(restartedRuntime, 1_001L, emptyList())
        val target = restartedRuntime.state.objective!!.targets.single()
        val feedback = mockk<Player>(relaxed = true)
        every { feedback.uniqueId } returns player.uniqueId
        every { feedback.location } returns player.location
        val beforeScoops = restarted.flooding.waterPositions(restartedRuntime).toSet()
        val first = beforeScoops.first()
        val guest = paper.server.addPlayer("FloodUnauthorized")
        every { restartedPort.hasAccess(guest, any()) } returns false
        val water = world.getBlockAt(first.x, first.y, first.z)
        guest.teleport(water.location.clone().add(.5, 1.0, .5))
        guest.inventory.setItemInMainHand(ItemStack(Material.BUCKET))
        val denied = org.bukkit.event.player.PlayerBucketFillEvent(guest, water, water, BlockFace.UP,
            Material.BUCKET, ItemStack(Material.WATER_BUCKET), EquipmentSlot.HAND)
        restarted.incidentSet.onBucketFill(denied) shouldBe true
        denied.isCancelled shouldBe true
        restarted.flooding.waterPositions(restartedRuntime).toSet() shouldBe beforeScoops
        val firstEvent = PlayerInteractEvent(
            feedback, Action.RIGHT_CLICK_BLOCK, ItemStack(Material.BUCKET),
            world.getBlockAt(first.x, first.y, first.z), BlockFace.UP, EquipmentSlot.HAND,
        )
        restarted.flooding.onInteract(firstEvent) shouldBe true
        firstEvent.item!!.type shouldBe Material.BUCKET
        items.toolIssues shouldBe 0
        verify(exactly = 1) { feedback.playSound(any<Location>(), Sound.BLOCK_WATER_AMBIENT, 0.75f, 1.15f) }
        restartedRuntime.state.phase shouldBe MinePhase.INCIDENT
        restarted.flooding.waterPositions(restartedRuntime).size shouldBe beforeScoops.size - 1
        while (restartedRuntime.state.phase == MinePhase.INCIDENT) {
            val next = restarted.flooding.waterPositions(restartedRuntime).first()
            val water = world.getBlockAt(next.x, next.y, next.z)
            // MockBukkit does not run vanilla fluid ticks. Mark the next durable
            // cell as water so this fixture can exercise the scoop handler; that
            // handler checks the material, not the flowing-water level.
            water.type shouldBe Material.AIR
            water.setType(Material.WATER, false)
            player.teleport(water.location.clone().add(.5, 1.0, .5))
            player.inventory.setItemInMainHand(ItemStack(Material.BUCKET))
            val scoop = org.bukkit.event.player.PlayerBucketFillEvent(player, water, water, BlockFace.UP,
                Material.BUCKET, ItemStack(Material.WATER_BUCKET), EquipmentSlot.HAND)
            val before = restarted.flooding.waterPositions(restartedRuntime).size
            restarted.incidentSet.onBucketFill(scoop) shouldBe true
            scoop.isCancelled shouldBe true
            player.inventory.itemInMainHand.type shouldBe Material.BUCKET
            restarted.flooding.waterPositions(restartedRuntime).size shouldBe before - 1
        }
        restartedRuntime.state.phase shouldBe MinePhase.MINING
        initial.all { world.getBlockAt(it.x, it.y, it.z).type == Material.AIR } shouldBe true
    }

    test("power failure is unavailable and an old active scene is retired with its lights") {
        val world = paper.server.addSimpleWorld("world")
        val switches = (1..5).map { x -> world.getBlockAt(x, 64, 5).also { it.type = Material.STONE } }
        val graph = worldGraph(paper, ImmediateMineJournal(), WorldIncidentItems(), "Power")
        val runtime = graph.registry.byId("old_shafts")!!
        index(graph, runtime, switches.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.POWER)) })
        graph.admin.forceIncident("old_shafts", MineIncidentType.POWER_FAILURE, 1_000L) shouldBe false
        // Reconstruct a persisted scene created by the previous version.
        graph.powerFailure.start(runtime, required = 2, now = 1_000L) shouldBe true
        val lights = graph.powerFailure.lightPositions(runtime)
        graph.incidentSet.tick(runtime, 1_001L, emptyList())
        runtime.state.phase shouldBe MinePhase.MINING
        lights.all { world.getBlockAt(it.x, it.y, it.z).type == Material.AIR } shouldBe true
    }

    listOf(0, 2, 3, 4).forEach { geometryVersion ->
        test("saved tunnel geometry v$geometryVersion is retired and its journal restores the old route") {
            val world = paper.server.addSimpleWorld("world")
            val plugin = paper.createSimplePlugin("MineLegacyWorkingV$geometryVersion")
            val settings = mineV2Settings().copy(
                reference = ZoneReference(world.name, null, CuboidBounds(-16, 50, -16, 64, 90, 64)),
            )
            val graph = testMineComponentGraph(
                plugin, CuboidRegionGateway(), immediateMinePort(),
                clock = { 1_000L }, journal = ImmediateMineJournal(),
            ).also { it.module.rebuild(listOf(settings), emptyMap(), 5_000L) }
            val runtime = graph.registry.byId("old_shafts")!!
            val placement = MineWorkingPlacement(
                WorksitePosition(world.name, 5, 63, 5), 0, "legacy-floor", geometryVersion = geometryVersion,
            )
            val plan = MineWorkingLayout.plan(MineIncidentType.TUNNEL_DRIVE, placement)
            val oldRoutePosition = plan.blocks.keys.maxBy(WorksitePosition::z)
            val activeData = plan.blocks.getValue(oldRoutePosition)
            // Keep the rollback visible even when this legacy route used stone at its endpoint.
            val originalData = if (activeData == "minecraft:stone") "minecraft:air" else "minecraft:stone"
            val oldRouteBlock = world.getBlockAt(oldRoutePosition.x, oldRoutePosition.y, oldRoutePosition.z)
            oldRouteBlock.setBlockData(MockBukkitFarmBlockDataDecoder.decode(originalData), false)

            val chunk = oldRouteBlock.chunk.also { it.load() }
            val journalKey = NamespacedKey(plugin, "mine_working_v1")
            val sceneId = 9
            val record = WorksitePreparedSceneRecord(
                world = world.name,
                zoneId = runtime.settings.id,
                sequence = runtime.state.sequence,
                sceneId = sceneId,
                x = oldRoutePosition.x,
                y = oldRoutePosition.y,
                z = oldRoutePosition.z,
                originalData = originalData,
                activeData = activeData,
                marker = "NONE",
                totalRecords = 1,
            )
            val codec = FarmMoleBurrowWorksiteSceneCodec(plugin, "mine_working")
            chunk.persistentDataContainer.set(
                journalKey,
                PersistentDataType.BYTE_ARRAY,
                codec.encode(listOf(record), world.name, chunk.x, chunk.z, world.minHeight, world.maxHeight),
            )
            oldRouteBlock.setBlockData(MockBukkitFarmBlockDataDecoder.decode(activeData), false)
            oldRouteBlock.blockData.asString shouldBe activeData

            runtime.state = runtime.state.copy(
                phase = MinePhase.INCIDENT,
                resumePhase = MinePhase.MINING,
                incident = MineIncidentState(
                    MineIncidentType.TUNNEL_DRIVE,
                    required = 93,
                    startedAt = 1_000L,
                    objectiveNonce = sceneId.toLong(),
                    working = MineWorkingEngine.initial(MineIncidentType.TUNNEL_DRIVE, placement),
                ),
            )

            graph.module.activateLoadedState()

            runtime.state.phase shouldBe MinePhase.MINING
            runtime.state.incident shouldBe null
            oldRouteBlock.blockData.asString shouldBe activeData
            graph.incidentSet.process()
            oldRouteBlock.blockData.asString shouldBe originalData
            chunk.persistentDataContainer.has(journalKey, PersistentDataType.BYTE_ARRAY) shouldBe false
        }
    }

})

private fun worldGraph(
    paper: MockBukkitTestRuntime,
    journal: ImmediateMineJournal,
    items: WorldIncidentItems,
    name: String,
    state: MineShiftState = MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run"),
    port: ru.ruscrafting.farms.paper.WorksiteRuntimePort = immediateMinePort(),
): MineComponentGraph = testMineComponentGraph(
    paper.createSimplePlugin("MineWorld$name"), CuboidRegionGateway(), port,
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

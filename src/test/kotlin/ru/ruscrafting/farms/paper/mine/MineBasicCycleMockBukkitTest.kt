package ru.ruscrafting.farms.paper.mine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityIdentity
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import java.util.UUID

class MineBasicCycleMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("idle mine guidance is available outside the region and auto-starts for a nearby miner") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("NearbyMiner")
        player.teleport(Location(world, 10.5, 70.0, 22.5))
        val graph = graph(paper, miningOnlySettings())
        val runtime = graph.registry.byId("old_shafts")!!
        indexMineables(graph, runtime, world)

        val view = requireNotNull(graph.guidance.view(player.uniqueId))
        val plain = PlainTextComponentSerializer.plainText()
        plain.serialize(view.title).isNotBlank() shouldBe true
        plain.serialize(view.subtitle).isNotBlank() shouldBe true
        plain.serialize(view.barName).isNotBlank() shouldBe true
        runtime.state.phase shouldBe MinePhase.IDLE
        runtime.region.contains(player.location) shouldBe false

        graph.module.tick(1_000L)

        runtime.state.phase shouldBe MinePhase.MINING
        runtime.state.objective shouldBe null
    }

    test("resource order counts matching ore anywhere and restored blocks without highlighted targets") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("ResourceMiner")
        player.teleport(Location(world, 5.5, 64.0, 5.5))
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        val original = miningOnlySettings()
        val settings = original.copy(orders = original.orders.map {
            it.copy(miningRequired = 100, miningMaterials = setOf("IRON_ORE"))
        })
        val graph = graph(paper, settings)
        val runtime = graph.registry.byId("old_shafts")!!
        val stone = world.getBlockAt(1, 64, 2).also { it.type = Material.STONE }
        val ore = world.getBlockAt(19, 64, 19).also { it.type = Material.IRON_ORE }
        graph.index.replaceZone(
            MineIndexDefinition(settings.id, runtime.region, setOf(Material.STONE, Material.IRON_ORE)),
            listOf(world.getChunkAt(0, 0), world.getChunkAt(1, 1)),
            listOf(stone, ore).map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.MINEABLE)) },
        )
        graph.module.tick(1_000L)
        runtime.state.phase shouldBe MinePhase.MINING
        runtime.state.objective shouldBe null
        graph.guidance.view(player.uniqueId)!!.targets.isEmpty() shouldBe true
        graph.mining.onBreakHigh(BlockBreakEvent(stone, player))
        runtime.state.mined shouldBe 0
        graph.mining.onBreakHigh(BlockBreakEvent(ore, player))
        runtime.state.mined shouldBe 1
        graph.mining.onBreakHigh(BlockBreakEvent(ore, player))
        runtime.state.mined shouldBe 1
        graph.recovery.processDue(4_000L)
        // The journal restored the position; either weighted material may have been selected.
        ore.type = Material.IRON_ORE
        graph.mining.onBreakHigh(BlockBreakEvent(ore, player))
        runtime.state.mined shouldBe 2
        val saved = graph.module.states()
        graph.module.rebuild(listOf(settings), saved, 5_000L)
        graph.registry.byId(settings.id)!!.state.mined shouldBe 2
    }

    test("decorative walls receive a connected order deposit and do not exceed the deficit") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("WallMiner")
        player.teleport(Location(world, 5.5, 64.0, 5.5))
        val original = miningOnlySettings()
        val settings = original.copy(orders = original.orders.map {
            it.copy(miningRequired = 16, miningMaterials = setOf("IRON_ORE"))
        })
        val graph = graph(paper, settings)
        val runtime = graph.registry.byId(settings.id)!!
        val wall = (2..5).flatMap { x -> (64..67).map { y ->
            world.getBlockAt(x, y, 2).also { it.type = if (x % 2 == 0) Material.ORANGE_TERRACOTTA else Material.SMOOTH_SANDSTONE }
        } }
        graph.index.replaceZone(MineIndexDefinition(settings.id, runtime.region, setOf(Material.STONE, Material.IRON_ORE)),
            listOf(world.getChunkAt(0, 0)), wall.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.SUPPORT)) })
        graph.module.tick(1_000L)
        runtime.state.phase shouldBe MinePhase.MINING
        wall.count { it.type == Material.IRON_ORE } shouldBe 16
        wall.forEach { graph.index.contains(settings.id, it, MineAnchorRole.MINEABLE) shouldBe true }
        graph.recovery.processDue(5_000L)
        graph.veins.tick(runtime, 6_000L)
        wall.count { it.type == Material.IRON_ORE } shouldBe 16
        runtime.state.mined shouldBe 0
    }

    test("startup validates the migrated legacy order before building the ordinary resource runtime") {
        paper.server.addSimpleWorld("world")
        val settings = miningOnlySettings()
        val legacy = ru.ruscrafting.farms.domain.MineShiftState(
            engineVersion = 2, phase = MinePhase.MINING, orderId = "removed_vein", sequence = 7, mined = 12,
        )
        ru.ruscrafting.farms.paper.MineController.validatePersisted(listOf(settings), mapOf(settings.id to legacy))
        val restored = MineRuntimeFactory.build(listOf(settings), mapOf(settings.id to legacy), 5_000L, CuboidRegionGateway()).single()
        restored.state.phase shouldBe MinePhase.IDLE
        restored.state.sequence shouldBe 7L
        restored.state.mined shouldBe 0
    }

    test("unauthorized and admin players outside a mine cannot auto-start it") {
        val world = paper.server.addSimpleWorld("world")
        val unauthorized = paper.server.addPlayer("UnauthorizedMiner")
        val admin = paper.server.addPlayer("AdminMiner")
        listOf(unauthorized, admin).forEach { it.teleport(Location(world, 10.5, 70.0, 22.5)) }
        val port = immediateMinePort().also {
            every { it.guarded(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
            every { it.hasAccess(unauthorized, any()) } returns false
            every { it.isAdminEditing(admin) } returns true
        }
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineBasicBoundaryTest"), CuboidRegionGateway(), port,
            clock = { 1_000L }, journal = ImmediateMineJournal(),
        )
        graph.module.rebuild(listOf(miningOnlySettings()), emptyMap(), 5_000L)
        val runtime = graph.registry.byId("old_shafts")!!
        indexMineables(graph, runtime, world)

        graph.module.tick(1_000L)

        runtime.state.phase shouldBe MinePhase.IDLE
        runtime.state.sequence shouldBe 0L
    }

    test("unsafe room entry preserves mining and legacy incident resolution still resumes the order") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("IncidentMiner")
        player.inventory.setItemInMainHand(ItemStack(Material.IRON_PICKAXE))
        player.teleport(Location(world, 5.5, 64.0, 5.5))
        val effects = RecordingIncidentEntities()
        val settings = miningOnlySettings()
        val graph = graph(paper, settings, effects)
        val runtime = graph.registry.byId("old_shafts")!!
        val mineable = (1..4).map { x -> world.getBlockAt(x, 64, 2).also { it.type = Material.STONE } }
        val nests = (1..6).map { x -> world.getBlockAt(x, 64, 5).also { it.type = Material.STONE } }
        graph.index.replaceZone(
            MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
            listOf(world.getChunkAt(0, 0)),
            mineable.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.MINEABLE)) } +
                nests.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.NEST)) },
        )

        graph.module.tick(1_000L)
        runtime.state.phase shouldBe MinePhase.MINING
        graph.mining.onBreakHigh(BlockBreakEvent(mineable.first(), player)) shouldBe true
        runtime.state.mined shouldBe 1

        graph.module.tick(2_000L)

        // This legacy fixture places the player inside a nest block. New rooms must
        // reject that entrance instead of silently using the old nest handler.
        // Automatic prepared-room entry is covered by mine-auto-event.spec.js on Paper.
        runtime.state.phase shouldBe MinePhase.MINING
        runtime.state.mined shouldBe 1
        effects.count(MineIncidentEntityKind.CREATURE) shouldBe 0
        graph.creatureNest.start(runtime, 3, 2_000L) shouldBe true
        runtime.state.phase shouldBe MinePhase.INCIDENT
        runtime.state.incident?.type shouldBe MineIncidentType.CREATURE_NEST
        runtime.state.resumePhase shouldBe MinePhase.MINING
        effects.count(MineIncidentEntityKind.CREATURE) shouldBe 6
        runtime.state.objective!!.targets.take(3).forEach { target ->
            graph.creatureNest.defeat(runtime, target.id, player) shouldBe true
        }
        runtime.state.phase shouldBe MinePhase.MINING
        runtime.state.incident shouldBe null
        runtime.state.objective shouldBe null
        graph.mining.onBreakHigh(BlockBreakEvent(mineable[1], player)) shouldBe true
        runtime.state.phase shouldBe MinePhase.EXTRACTION
        graph.module.tick(3_000L)
        runtime.state.phase shouldBe MinePhase.COOLDOWN
        graph.module.tick(3_001L)
        runtime.state.sequence shouldBe 1L
    }
})

private fun graph(
    paper: MockBukkitTestRuntime,
    settings: ru.ruscrafting.farms.config.MineZoneSettings,
    effects: MineIncidentEntityEffects = RecordingIncidentEntities(),
) = testMineComponentGraph(
    paper.createSimplePlugin("MineBasicCycleTest"), CuboidRegionGateway(),
    immediateMinePort().also {
        every { it.guarded(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
    },
    clock = { 1_000L }, journal = ImmediateMineJournal(), incidentEntityEffects = effects,
).also { it.module.rebuild(listOf(settings), emptyMap(), 5_000L) }

private fun miningOnlySettings() = mineV2Settings().let { original ->
    original.copy(miningOnly = true, guidanceRadius = 24.0, incidentCountMin = 1, incidentCountMax = 1,
        orders = original.orders.map { it.copy(incidentTypes = listOf(MineIncidentType.CREATURE_NEST), miningMaterials = setOf("STONE")) })
}

private fun indexMineables(graph: MineComponentGraph, runtime: MineRuntime, world: org.bukkit.World) {
    val blocks = (1..4).map { x -> world.getBlockAt(x, 64, 2).also { it.type = Material.STONE } }
    graph.index.replaceZone(
        MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
        listOf(world.getChunkAt(0, 0)),
        blocks.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.MINEABLE)) },
    )
}

private class RecordingIncidentEntities : MineIncidentEntityEffects {
    private val identities = linkedMapOf<UUID, MineIncidentEntityIdentity>()
    private val entities = linkedMapOf<UUID, Entity>()

    override fun spawn(runtime: MineRuntime, kind: MineIncidentEntityKind, targetId: String, position: WorksitePosition): UUID {
        val world = runtime.region.world
        val entity = world.spawnEntity(Location(world, position.x + 0.5, position.y + 1.0, position.z + 0.5), EntityType.ARMOR_STAND)
        identities[entity.uniqueId] = MineIncidentEntityIdentity(kind, runtime.settings.id, runtime.state.sequence, targetId)
        entities[entity.uniqueId] = entity
        return entity.uniqueId
    }

    override fun identity(entity: Entity): MineIncidentEntityIdentity? = identities[entity.uniqueId]
    override fun entity(id: UUID): Entity? = entities[id]?.takeIf { it.isValid }
    override fun remove(id: UUID) { entities.remove(id)?.remove(); identities.remove(id) }

    override fun reconcileChunk(
        runtime: MineRuntime,
        chunk: Chunk,
        kind: MineIncidentEntityKind,
        expected: Map<String, WorksitePosition>,
    ): Map<String, UUID> {
        val canonical = linkedMapOf<String, UUID>()
        expected.forEach { (targetId, position) -> canonical[targetId] = spawn(runtime, kind, targetId, position) }
        return canonical
    }

    override fun cleanup(runtime: MineRuntime, kind: MineIncidentEntityKind) {
        identities.filterValues { it.zoneId == runtime.settings.id && it.kind == kind }.keys.toList().forEach(::remove)
    }

    fun count(kind: MineIncidentEntityKind): Int = identities.values.count { it.kind == kind }
}

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)

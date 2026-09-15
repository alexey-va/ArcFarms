package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidRegionGateway
import ru.ruscrafting.farms.paper.mine.ImmediateMineJournal
import ru.ruscrafting.farms.paper.mine.MineComponentGraph
import ru.ruscrafting.farms.paper.mine.testMineComponentGraph
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.immediateMinePort
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityIdentity
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineIndexedTarget
import ru.ruscrafting.farms.paper.mine.mineV2Settings
import java.util.UUID

class MineEntityIncidentsMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("creature incident requires both farm-style glowing nests and their creatures") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Guard")
        val floors = (1..6).map { x -> world.getBlockAt(x, 63, 2).also { it.type = Material.STONE } }
        val decoration = world.getBlockAt(8, 63, 2).also { it.type = Material.OAK_PLANKS }
        val effects = RecordingIncidentEntities()
        val graph = entityGraph(paper, effects, "Creatures")
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, floors + decoration)

        graph.creatureNest.start(runtime, required = 2, now = 1_000L) shouldBe true
        graph.creatureNest.spawnedCount(runtime) shouldBe 2
        graph.creatureNest.nestCount(runtime) shouldBe 2
        effects.count(MineIncidentEntityKind.CREATURE_NEST_DISPLAY) shouldBe 2
        effects.count(MineIncidentEntityKind.CREATURE_NEST_HITBOX) shouldBe 2
        val targets = runtime.state.objective!!.targets
        targets.filter { it.role.value == "creature_nest" }.all { target ->
            world.getBlockAt(target.position.x, target.position.y, target.position.z).type == Material.STONE
        } shouldBe true
        val creatures = targets.filter { it.role.value == "creature" }
        val nests = targets.filter { it.role.value == "creature_nest" }
        creatures.forEach { graph.creatureNest.defeat(runtime, it.id, player) shouldBe true }
        runtime.state.phase shouldBe MinePhase.INCIDENT
        nests.forEach { graph.creatureNest.destroyNest(runtime, it.id, player) shouldBe true }
        runtime.state.phase shouldBe MinePhase.MINING
        runtime.state.contributors[player.uniqueId] shouldBe 4
        effects.count(MineIncidentEntityKind.CREATURE) shouldBe 0
        effects.count(MineIncidentEntityKind.CREATURE_NEST_DISPLAY) shouldBe 0
        effects.count(MineIncidentEntityKind.CREATURE_NEST_HITBOX) shouldBe 0
    }

    test("admin force switches the active mine incident and clears its scene like the farm") {
        val world = paper.server.addSimpleWorld("world")
        val floors = (1..6).map { x -> world.getBlockAt(x, 63, 2).also { it.type = Material.STONE } }
        val effects = RecordingIncidentEntities()
        val graph = entityGraph(paper, effects, "AdminSwitch")
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, floors)

        graph.creatureNest.start(runtime, required = 2, now = 1_000L) shouldBe true
        effects.count(MineIncidentEntityKind.CREATURE) shouldBe 2
        effects.count(MineIncidentEntityKind.CREATURE_NEST_DISPLAY) shouldBe 2

        graph.admin.forceIncident("old_shafts", MineIncidentType.TRACK_DAMAGE, 2_000L) shouldBe true

        runtime.state.phase shouldBe MinePhase.INCIDENT
        runtime.state.incident!!.type shouldBe MineIncidentType.TRACK_DAMAGE
        effects.count(MineIncidentEntityKind.CREATURE) shouldBe 0
        effects.count(MineIncidentEntityKind.CREATURE_NEST_DISPLAY) shouldBe 0
        effects.count(MineIncidentEntityKind.CREATURE_NEST_HITBOX) shouldBe 0
    }

    test("block interaction incidents reuse one recoverable glowing marker scene") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Inspector")
        val walls = (1..6).map { x -> world.getBlockAt(x, 63, 2).also { it.type = Material.STONE } }
        val effects = RecordingIncidentEntities()
        val graph = entityGraph(paper, effects, "Markers")
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, walls)

        graph.gasLeak.start(runtime, required = 2, now = 1_000L) shouldBe true
        graph.incidentSet.tick(runtime, 1_001L, emptyList())
        effects.count(MineIncidentEntityKind.GAS_MARKER) shouldBe 4
        val targets = runtime.state.objective!!.targets
        graph.gasLeak.useVent(runtime, targets[0].id, player) shouldBe true
        graph.gasLeak.useVent(runtime, targets[1].id, player) shouldBe true
        graph.incidentSet.tick(runtime, 1_002L, emptyList())
        effects.count(MineIncidentEntityKind.GAS_MARKER) shouldBe 0
    }

    test("lost miner is reconstructed once and escort completes at the indexed route entrance") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Rescuer")
        val floors = (1..6).map { x -> world.getBlockAt(x, 63, 5).also { it.type = Material.STONE } }
        val effects = RecordingIncidentEntities()
        val graph = entityGraph(paper, effects, "RescueA")
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, floors)

        graph.lostMiner.start(runtime, now = 1_000L) shouldBe true
        graph.lostMiner.canonicalCount(runtime) shouldBe 1
        effects.count(MineIncidentEntityKind.MINER_CAMP_LANTERN) shouldBe 1
        effects.count(MineIncidentEntityKind.MINER_CAMP_SUPPLIES) shouldBe 1
        val target = runtime.state.objective!!.targets.first()
        effects.spawn(runtime, MineIncidentEntityKind.MINER, target.id, target.position)
        effects.count(MineIncidentEntityKind.MINER) shouldBe 2

        val persisted = runtime.state
        val restarted = testMineComponentGraph(
            paper.createSimplePlugin("MineEntityRescueB"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 2_000L }, journal = ImmediateMineJournal(), incidentEntityEffects = effects,
        )
        restarted.module.rebuild(listOf(mineV2Settings()), mapOf("old_shafts" to persisted), 5_000L)
        val restartedRuntime = restarted.registry.byId("old_shafts")!!
        replaceEntityIndex(restarted, restartedRuntime, floors)
        restarted.lostMiner.reconcileChunk(restartedRuntime, world.getChunkAt(0, 0)) shouldBe 1
        effects.count(MineIncidentEntityKind.MINER) shouldBe 1

        effects.remove(effects.singleId(MineIncidentEntityKind.MINER))
        restarted.lostMiner.reconcileMissing(restartedRuntime) shouldBe 1
        effects.count(MineIncidentEntityKind.MINER) shouldBe 1

        restarted.lostMiner.beginEscort(restartedRuntime, player) shouldBe true
        val entrance = requireNotNull(restarted.extraction.deliveryPoint(restartedRuntime))
        restarted.lostMiner.onMove(Location(world, entrance.x + 0.5, entrance.y + 1.0, entrance.z + 0.5), player) shouldBe true
        restartedRuntime.state.phase shouldBe MinePhase.MINING
        effects.count(MineIncidentEntityKind.MINER) shouldBe 0
        effects.count(MineIncidentEntityKind.MINER_CAMP_LANTERN) shouldBe 0
        effects.count(MineIncidentEntityKind.MINER_CAMP_SUPPLIES) shouldBe 0
    }
})

private fun entityGraph(paper: MockBukkitTestRuntime, effects: RecordingIncidentEntities, name: String): MineComponentGraph =
    testMineComponentGraph(
        paper.createSimplePlugin("MineEntity$name"), CuboidRegionGateway(), immediateMinePort(),
        clock = { 1_000L }, journal = ImmediateMineJournal(), incidentEntityEffects = effects,
    ).also { graph ->
        graph.module.rebuild(
            listOf(mineV2Settings()),
            mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")),
            5_000L,
        )
    }

private fun replaceEntityIndex(graph: MineComponentGraph, runtime: MineRuntime, floors: List<org.bukkit.block.Block>) {
    graph.index.replaceZone(
        MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
        listOf(runtime.region.world.getChunkAt(0, 0)),
        floors.map {
            MineIndexedTarget(
                it.position(),
                setOf(MineAnchorRole.NEST, MineAnchorRole.MINER, MineAnchorRole.RAIL, MineAnchorRole.SUPPORT),
            )
        },
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
        entities.toMap().forEach { (id, entity) ->
            val identity = identities[id] ?: return@forEach
            if (identity.zoneId != runtime.settings.id || identity.sequence != runtime.state.sequence || identity.kind != kind) return@forEach
            if (identity.targetId !in expected || canonical.putIfAbsent(identity.targetId, id) != null) remove(id)
        }
        expected.forEach { (targetId, position) ->
            if (targetId !in canonical) canonical[targetId] = spawn(runtime, kind, targetId, position)
        }
        return canonical
    }

    override fun cleanup(runtime: MineRuntime, kind: MineIncidentEntityKind) {
        identities.filterValues { it.zoneId == runtime.settings.id && it.kind == kind }.keys.toList().forEach(::remove)
    }

    fun count(kind: MineIncidentEntityKind): Int = identities.values.count { it.kind == kind }
    fun singleId(kind: MineIncidentEntityKind): UUID = identities.filterValues { it.kind == kind }.keys.single()
}

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)

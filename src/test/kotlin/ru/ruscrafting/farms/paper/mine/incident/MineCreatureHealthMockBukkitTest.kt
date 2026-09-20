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

class MineCreatureHealthMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }
    test("nest pests spawn at eight HP and reconciliation preserves wounds") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Guard")
        val floors = (1..18).flatMap { x -> (1..8).map { z -> world.getBlockAt(x, 63, z).also { it.type = Material.STONE } } }
        val decoration = world.getBlockAt(8, 63, 2).also { it.type = Material.OAK_PLANKS }
        val effects = HealthIncidentEntities()
        val graph = entityGraph(paper, effects, "Creatures")
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, floors + decoration)
        repeat(100) { graph.candidateStock.prewarm(runtime, 1000L) }

        graph.creatureNest.start(runtime, required = 2, now = 1_000L) shouldBe true
        graph.creatureNest.spawnedCount(runtime) shouldBe 2
        graph.creatureNest.nestCount(runtime) shouldBe 2
        val pest = effects.entity(effects.ids(MineIncidentEntityKind.CREATURE).first()) as org.bukkit.entity.LivingEntity
        pest.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue shouldBe 8.0
        pest.health shouldBe 8.0
        pest.getAttribute(org.bukkit.attribute.Attribute.ARMOR)!!.baseValue shouldBe 0.0
        pest.getAttribute(org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS)!!.baseValue shouldBe 0.0
        pest.health = 4.0
        graph.creatureNest.reconcileChunk(runtime, pest.location.chunk)
        pest.health shouldBe 4.0
        effects.count(MineIncidentEntityKind.CREATURE_NEST_DISPLAY) shouldBe 2
        effects.count(MineIncidentEntityKind.CREATURE_NEST_HITBOX) shouldBe 2
        val targets = runtime.state.objective!!.targets
        targets.filter { it.role.value == "creature_nest" }.all { target ->
            world.getBlockAt(target.position.x, target.position.y, target.position.z).type == Material.STONE
        } shouldBe true
        val creatures = targets.filter { it.role.value == "creature" }
        val nests = targets.filter { it.role.value == "creature_nest" }
        val firstNestPositions = nests.map { it.position }.toSet()
        creatures.forEach { graph.creatureNest.defeat(runtime, it.id, player) shouldBe true }
        runtime.state.phase shouldBe MinePhase.INCIDENT
        nests.forEach { graph.creatureNest.destroyNest(runtime, it.id, player) shouldBe true }
        runtime.state.phase shouldBe MinePhase.MINING
        runtime.state.contributors[player.uniqueId] shouldBe 4
        effects.count(MineIncidentEntityKind.CREATURE) shouldBe 0
        effects.count(MineIncidentEntityKind.CREATURE_NEST_DISPLAY) shouldBe 0
        effects.count(MineIncidentEntityKind.CREATURE_NEST_HITBOX) shouldBe 0

        graph.creatureNest.start(runtime, required = 2, now = 2_000L) shouldBe true
        val secondNestPositions = runtime.state.objective!!.targets
            .filter { it.role.value == "creature_nest" }
            .map { it.position }
            .toSet()
        (secondNestPositions != firstNestPositions) shouldBe true
    }

})
private fun entityGraph(paper: MockBukkitTestRuntime, effects: HealthIncidentEntities, name: String): MineComponentGraph =
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

private fun replaceEntityIndex(
    graph: MineComponentGraph,
    runtime: MineRuntime,
    floors: List<org.bukkit.block.Block>,
    role: MineAnchorRole = MineAnchorRole.NEST,
) {
    graph.index.replaceZone(
        MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
        listOf(runtime.region.world.getChunkAt(0, 0)),
        floors.map {
            MineIndexedTarget(
                it.position(),
                if (role == MineAnchorRole.NEST) {
                    setOf(MineAnchorRole.NEST, MineAnchorRole.MINER, MineAnchorRole.RAIL, MineAnchorRole.SUPPORT)
                } else {
                    setOf(role)
                },
            )
        },
    )
}

private class HealthIncidentEntities : MineIncidentEntityEffects {
    private val identities = linkedMapOf<UUID, MineIncidentEntityIdentity>()
    private val entities = linkedMapOf<UUID, Entity>()

    override fun spawn(runtime: MineRuntime, kind: MineIncidentEntityKind, targetId: String, position: WorksitePosition): UUID {
        val world = runtime.region.world
        val entity = world.spawnEntity(Location(world, position.x + 0.5, position.y + 1.0, position.z + 0.5), if (kind == MineIncidentEntityKind.CREATURE) EntityType.HUSK else EntityType.ARMOR_STAND)
        // MockBukkit does not install vanilla husk armor attributes automatically.
        if (kind == MineIncidentEntityKind.CREATURE && entity is org.bukkit.entity.LivingEntity) {
            entity.registerAttribute(org.bukkit.attribute.Attribute.ARMOR)
            entity.registerAttribute(org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS)
            entity.getAttribute(org.bukkit.attribute.Attribute.ARMOR)!!.baseValue = 2.0
        }
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
            if ((entity.location.blockX shr 4) != chunk.x || (entity.location.blockZ shr 4) != chunk.z) return@forEach
            val identity = identities[id] ?: return@forEach
            if (identity.zoneId != runtime.settings.id || identity.sequence != runtime.state.sequence || identity.kind != kind) return@forEach
            val position = expected[identity.targetId]
            if (position == null || (position.x shr 4) != chunk.x || (position.z shr 4) != chunk.z ||
                canonical.putIfAbsent(identity.targetId, id) != null
            ) remove(id)
        }
        expected.filterValues { position ->
            (position.x shr 4) == chunk.x && (position.z shr 4) == chunk.z
        }.forEach { (targetId, position) ->
            if (targetId !in canonical) canonical[targetId] = spawn(runtime, kind, targetId, position)
        }
        return canonical
    }

    override fun cleanup(runtime: MineRuntime, kind: MineIncidentEntityKind) {
        identities.filterValues { it.zoneId == runtime.settings.id && it.kind == kind }.keys.toList().forEach(::remove)
    }

    fun count(kind: MineIncidentEntityKind): Int = identities.values.count { it.kind == kind }
    fun ids(kind: MineIncidentEntityKind): List<UUID> = identities.filterValues { it.kind == kind }.keys.toList()
    fun singleId(kind: MineIncidentEntityKind): UUID = identities.filterValues { it.kind == kind }.keys.single()
    fun id(kind: MineIncidentEntityKind, targetId: String): UUID = identities.entries
        .single { it.value.kind == kind && it.value.targetId == targetId }.key
}

private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)

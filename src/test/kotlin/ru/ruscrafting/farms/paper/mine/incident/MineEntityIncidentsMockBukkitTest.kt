package ru.ruscrafting.farms.paper.mine.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.bukkit.Chunk
import org.bukkit.block.BlockFace
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.BlockDisplay
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffectType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetState
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.ZoneReference
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
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
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

        // The admin force must exercise a real indexed objective pool. Reindex these
        // authored points as supports before switching away from the nest scene.
        replaceEntityIndex(graph, runtime, floors, MineAnchorRole.SUPPORT)
        graph.admin.forceIncident("old_shafts", MineIncidentType.GAS_LEAK, 2_000L) shouldBe true
        graph.incidentSet.tick(runtime, 2_001L, emptyList())

        runtime.state.phase shouldBe MinePhase.INCIDENT
        runtime.state.incident!!.type shouldBe MineIncidentType.GAS_LEAK
        effects.count(MineIncidentEntityKind.CREATURE) shouldBe 0
        effects.count(MineIncidentEntityKind.CREATURE_NEST_DISPLAY) shouldBe 0
        effects.count(MineIncidentEntityKind.CREATURE_NEST_HITBOX) shouldBe 0
        effects.count(MineIncidentEntityKind.GAS_MARKER) shouldBe 4
        effects.count(MineIncidentEntityKind.GAS_MARKER_HITBOX) shouldBe 4
    }

    test("block interaction incidents reuse one recoverable glowing marker scene") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("Inspector")
        player.teleport(Location(world, 1.5, 64.0, 2.5))
        val walls = (1..6).map { x -> world.getBlockAt(x, 63, 2).also { it.type = Material.STONE } }
        val effects = RecordingIncidentEntities()
        val graph = entityGraph(paper, effects, "Markers")
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, walls)

        graph.gasLeak.start(runtime, required = 2, now = 1_000L) shouldBe true
        val healthBeforeGas = player.health
        graph.incidentSet.tick(runtime, 1_001L, listOf(player))
        player.health shouldBe healthBeforeGas - 1.0
        player.hasPotionEffect(PotionEffectType.NAUSEA) shouldBe true
        graph.incidentSet.tick(runtime, 1_500L, listOf(player))
        player.health shouldBe healthBeforeGas - 1.0
        effects.count(MineIncidentEntityKind.GAS_MARKER) shouldBe 4
        effects.count(MineIncidentEntityKind.GAS_MARKER_HITBOX) shouldBe 4
        effects.ids(MineIncidentEntityKind.GAS_MARKER_HITBOX).take(runtime.state.incident!!.required).forEach { id ->
            val event = PlayerInteractEntityEvent(player, requireNotNull(effects.entity(id)), EquipmentSlot.HAND)
            graph.incidentSet.onInteractEntity(event) shouldBe true
            event.isCancelled shouldBe true
        }
        runtime.state.phase shouldBe MinePhase.MINING
        effects.count(MineIncidentEntityKind.GAS_MARKER) shouldBe 0
        effects.count(MineIncidentEntityKind.GAS_MARKER_HITBOX) shouldBe 0
    }

    test("pre-upgrade multi-target flooding is retired before it can rebuild remote spills") {
        val world = paper.server.addSimpleWorld("world")
        val effects = RecordingIncidentEntities()
        val graph = entityGraph(paper, effects, "LegacyFlood")
        val runtime = graph.registry.byId("old_shafts")!!
        val targets = listOf(WorksitePosition(world.name, 3, 63, 3), WorksitePosition(world.name, 12, 63, 12))
            .mapIndexed { index, position ->
                ObjectiveTargetState("legacy_flood_${index + 1}", position, ObjectiveTargetRole("flood_pump"), index.toLong())
            }
        runtime.state = runtime.state.copy(
            phase = MinePhase.INCIDENT,
            sequence = 2,
            resumePhase = MinePhase.MINING,
            incident = MineIncidentState(MineIncidentType.FLOODING, required = 2),
            objective = WorksiteObjectiveState(
                WorksiteObjectiveKey(runtime.settings.id, "incident_flooding", 2),
                required = 2,
                targets = targets,
            ),
        )

        graph.incidentSet.tick(runtime, 1_001L, emptyList())

        runtime.state.phase shouldBe MinePhase.MINING
        runtime.state.incident shouldBe null
        runtime.state.objective shouldBe null
    }

    test("production objective markers put a responsive hitbox in open space") {
        val world = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("MineProductionMarkers")
        val graph = testMineComponentGraph(
            plugin, CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(),
        )
        graph.module.rebuild(
            listOf(mineV2Settings()),
            mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")),
            5_000L,
        )
        val runtime = graph.registry.byId("old_shafts")!!
        val supports = (1..4).map { x -> world.getBlockAt(x, 63, 2).also { it.type = Material.STONE } }
        replaceEntityIndex(graph, runtime, supports)

        graph.gasLeak.start(runtime, required = 2, now = 1_000L) shouldBe true
        graph.incidentSet.tick(runtime, 1_001L, emptyList())

        world.entities.filterIsInstance<BlockDisplay>().size shouldBe 4
        world.entities.filterIsInstance<BlockDisplay>().all { display ->
            display.block.material == Material.STONE &&
                display.isGlowing &&
                display.brightness?.blockLight == 15 && display.brightness?.skyLight == 15 &&
                display.transformation.scale.x == 1.002f &&
                display.transformation.translation.x == -0.001f
        } shouldBe true
        world.entities.filterIsInstance<Interaction>().size shouldBe 4
        world.entities.filterIsInstance<Interaction>().all { interaction ->
            interaction.isResponsive && interaction.interactionWidth == 0.8f &&
                interaction.interactionHeight == 1.0f && interaction.location.block.type.isAir &&
                interaction.location.y == 64.0
        } shouldBe true
    }

    test("crystal resonance uses real amethyst blocks without synthetic markers") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("CrystalOperator")
        val crystals = (1..5).map { x ->
            world.getBlockAt(x, 64, 5).also { it.type = Material.AMETHYST_CLUSTER }
        }
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineCrystalDirectTargets"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(),
        )
        graph.module.rebuild(
            listOf(mineV2Settings()),
            mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")),
            5_000L,
        )
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, crystals, MineAnchorRole.CRYSTAL)

        graph.crystalResonance.start(runtime, required = 2, now = 1_000L) shouldBe true
        graph.incidentSet.tick(runtime, 1_001L, emptyList())
        val highlights = world.entities.filterIsInstance<BlockDisplay>()
        val targets = runtime.state.objective!!.targets.toList()
        targets.size shouldBe 4
        highlights.size shouldBe targets.count { it.status != ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus.COMPLETED }
        highlights.all { display ->
            display.isGlowing && display.block.material == Material.AMETHYST_CLUSTER
        } shouldBe true
        world.entities.filterIsInstance<ItemDisplay>().shouldBeEmpty()
        world.entities.filterIsInstance<Interaction>().shouldBeEmpty()

        targets.take(2).forEachIndexed { index, target ->
            graph.incidentSet.onInteract(
                org.bukkit.event.player.PlayerInteractEvent(
                    player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null,
                    world.getBlockAt(target.position.x, target.position.y, target.position.z),
                    org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND,
                ),
            ) shouldBe true
            if (index == 0) {
                runtime.state.phase shouldBe MinePhase.INCIDENT
                world.entities.filterIsInstance<BlockDisplay>().size shouldBe 3
            }
        }
        runtime.state.phase shouldBe MinePhase.MINING
        world.entities.filterIsInstance<BlockDisplay>().shouldBeEmpty()
    }

    test("crystal resonance refuses a target that is no longer a real cluster") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("CrystalTamper")
        val crystals = (1..3).map { x -> world.getBlockAt(x, 64, 5).also { it.type = Material.AMETHYST_CLUSTER } }
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineCrystalMaterialGuard"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(),
        )
        graph.module.rebuild(
            listOf(mineV2Settings()),
            mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")),
            5_000L,
        )
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, crystals, MineAnchorRole.CRYSTAL)
        graph.crystalResonance.start(runtime, required = 1, now = 1_000L) shouldBe true
        val target = runtime.state.objective!!.targets.first()
        val changed = world.getBlockAt(target.position.x, target.position.y, target.position.z).also {
            it.type = Material.AMETHYST_BLOCK
        }

        graph.crystalResonance.hit(runtime, target.id, player, insideForgivingWindow = true) shouldBe false
        runtime.state.phase shouldBe MinePhase.INCIDENT
        graph.incidentSet.tick(runtime, 1_001L, emptyList())
        runtime.state.phase shouldBe MinePhase.MINING
        changed.type shouldBe Material.AMETHYST_BLOCK
    }

    test("persisted crystal target waits for its chunk instead of aborting during recovery") {
        val world = paper.server.addSimpleWorld("world")
        val effects = RecordingIncidentEntities()
        val graph = entityGraph(paper, effects, "CrystalUnloaded")
        val runtime = graph.registry.byId("old_shafts")!!
        val position = WorksitePosition(world.name, 20, 64, 2)
        runtime.state = runtime.state.copy(
            phase = MinePhase.INCIDENT,
            sequence = 2,
            resumePhase = MinePhase.MINING,
            incident = MineIncidentState(MineIncidentType.CRYSTAL_RESONANCE, required = 1),
            objective = WorksiteObjectiveState(
                WorksiteObjectiveKey(runtime.settings.id, "incident_crystal_resonance", 2),
                required = 1,
                targets = listOf(ObjectiveTargetState("persisted_cluster", position, ObjectiveTargetRole("crystal_node"), 0)),
            ),
        )
        world.getChunkAt(1, 0).load()
        world.unloadChunk(1, 0, false)

        graph.incidentSet.tick(runtime, 1_001L, emptyList())

        runtime.state.phase shouldBe MinePhase.INCIDENT
        runtime.state.incident?.type shouldBe MineIncidentType.CRYSTAL_RESONANCE
    }

    test("completing a target removes its display and hitbox from an isolated chunk") {
        val world = paper.server.addSimpleWorld("world")
        val player = paper.server.addPlayer("VentOperator")
        val effects = RecordingIncidentEntities()
        val graph = testMineComponentGraph(
            paper.createSimplePlugin("MineMarkerChunkCleanup"), CuboidRegionGateway(), immediateMinePort(),
            clock = { 1_000L }, journal = ImmediateMineJournal(), incidentEntityEffects = effects,
        )
        val settings = mineV2Settings().copy(
            reference = ZoneReference("world", null, CuboidBounds(0, 50, 0, 127, 90, 20)),
        )
        graph.module.rebuild(
            listOf(settings),
            mapOf("old_shafts" to MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 1, orderId = "ore_run")),
            5_000L,
        )
        val runtime = graph.registry.byId("old_shafts")!!
        val supports = listOf(1, 33, 65, 97).map { x ->
            world.getBlockAt(x, 63, 2).also { it.type = Material.STONE }
        }
        graph.index.replaceZone(
            MineIndexDefinition(runtime.settings.id, runtime.region, setOf(Material.STONE)),
            supports.map { it.chunk }.distinctBy { it.x to it.z },
            supports.map { MineIndexedTarget(it.position(), setOf(MineAnchorRole.SUPPORT)) },
        )

        graph.gasLeak.start(runtime, required = 2, now = 1_000L) shouldBe true
        graph.incidentSet.tick(runtime, 1_001L, emptyList())
        effects.count(MineIncidentEntityKind.GAS_MARKER) shouldBe 4
        effects.count(MineIncidentEntityKind.GAS_MARKER_HITBOX) shouldBe 4

        val first = runtime.state.objective!!.targets.first()
        graph.gasLeak.useVent(runtime, first.id, player) shouldBe true
        graph.incidentSet.tick(runtime, 1_002L, emptyList())

        effects.count(MineIncidentEntityKind.GAS_MARKER) shouldBe 3
        effects.count(MineIncidentEntityKind.GAS_MARKER_HITBOX) shouldBe 3
    }

    test("a marker incident aborts cleanly when rebuilding encloses an active target") {
        val world = paper.server.addSimpleWorld("world")
        val supports = (1..6).map { x -> world.getBlockAt(x, 63, 2).also { it.type = Material.STONE } }
        val effects = RecordingIncidentEntities()
        val graph = entityGraph(paper, effects, "BlockedMarker")
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, supports)

        graph.gasLeak.start(runtime, required = 2, now = 1_000L) shouldBe true
        graph.incidentSet.tick(runtime, 1_001L, emptyList())
        val target = runtime.state.objective!!.targets.first().position
        val block = world.getBlockAt(target.x, target.y, target.z)
        listOf(BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST)
            .forEach { block.getRelative(it).type = Material.STONE }

        graph.incidentSet.tick(runtime, 1_002L, emptyList())

        runtime.state.phase shouldBe MinePhase.MINING
        effects.count(MineIncidentEntityKind.GAS_MARKER) shouldBe 0
        effects.count(MineIncidentEntityKind.GAS_MARKER_HITBOX) shouldBe 0
    }

    test("lost miner uses one maze entrance and completes when the miner is found") {
        requiredMockBukkitScenario {
        val world = paper.server.addSimpleWorld("world")
        for (chunkX in -3..3) for (chunkZ in -3..3) world.getChunkAt(chunkX, chunkZ).load()
        val player = paper.server.addPlayer("Rescuer")
        val floors = (1..6).map { x -> world.getBlockAt(x, 63, 5).also { it.type = Material.STONE } }
        val effects = RecordingIncidentEntities()
        val graph = entityGraph(paper, effects, "RescueA")
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, floors)

        graph.lostMiner.start(runtime, now = 1_000L) shouldBe true
        repeat(64) { graph.lostMiner.process() }
        graph.lostMiner.reconcileMissing(runtime) shouldBe 1
        graph.lostMiner.canonicalCount(runtime) shouldBe 1
        // One public entrance and one native click-out marker share the maze entrance kind.
        effects.count(MineIncidentEntityKind.MINER_MAZE_ENTRANCE) shouldBe 2
        effects.count(MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX) shouldBe 2
        effects.count(MineIncidentEntityKind.MINER_CAMP_LANTERN) shouldBe 0
        effects.count(MineIncidentEntityKind.MINER_CAMP_SUPPLIES) shouldBe 0
        (effects.count(MineIncidentEntityKind.RESCUE_CREATURE) > 0) shouldBe true
        val target = runtime.state.objective!!.targets.first()
        effects.spawn(runtime, MineIncidentEntityKind.MINER, target.id, target.position)
        effects.count(MineIncidentEntityKind.MINER) shouldBe 2
        graph.lostMiner.reconcileChunk(runtime, world.getChunkAt(target.position.x shr 4, target.position.z shr 4)) shouldBe 1
        effects.count(MineIncidentEntityKind.MINER) shouldBe 1

        // Clicking the entrance from beyond the authored surface must not move the player.
        player.teleport(Location(world, target.position.x + 40.5, target.position.y + 1.0, target.position.z + 0.5))
        val distantEntryEvent = PlayerInteractEntityEvent(
            player,
            requireNotNull(effects.entity(effects.id(MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX, target.id))),
            EquipmentSlot.HAND,
        )
        graph.incidentSet.onInteractEntity(distantEntryEvent) shouldBe true
        distantEntryEvent.isCancelled shouldBe true
        runtime.region.contains(player.location) shouldBe false

        // The normal entry is valid only at the actual indexed surface anchor.
        player.teleport(Location(world, target.position.x + 0.5, target.position.y + 1.0, target.position.z + 0.5))
        val entryEvent = PlayerInteractEntityEvent(
            player,
            requireNotNull(effects.entity(effects.id(MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX, target.id))),
            EquipmentSlot.HAND,
        )
        graph.incidentSet.onInteractEntity(entryEvent) shouldBe true
        entryEvent.isCancelled shouldBe true
        runtime.region.contains(player.location) shouldBe false

        graph.lostMiner.releasePlayer(player, WorksitePlayerReleaseReason.SHUTDOWN) shouldBe true
        runtime.region.contains(player.location) shouldBe true
        val reentryEvent = PlayerInteractEntityEvent(
            player,
            requireNotNull(effects.entity(effects.id(MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX, target.id))),
            EquipmentSlot.HAND,
        )
        graph.incidentSet.onInteractEntity(reentryEvent) shouldBe true
        reentryEvent.isCancelled shouldBe true
        runtime.region.contains(player.location) shouldBe false

        // A spectator in flight that walks out of the maze must keep the native exit.
        val outside = Location(world, 30.5, 64.0, 30.5)
        player.gameMode = GameMode.SPECTATOR
        player.allowFlight = true
        player.isFlying = true
        player.teleport(outside)
        graph.incidentSet.onMove(outside, player) shouldBe false
        player.location.blockX shouldBe outside.blockX
        player.location.blockZ shouldBe outside.blockZ
        player.gameMode shouldBe GameMode.SPECTATOR
        player.isFlying shouldBe true

        // Re-enter as a normal player and use the native exit marker, rather than a
        // synthetic command or a forced teleport.
        player.gameMode = GameMode.SURVIVAL
        player.isFlying = false
        player.teleport(Location(world, target.position.x + 0.5, target.position.y + 1.0, target.position.z + 0.5))
        val thirdEntryEvent = PlayerInteractEntityEvent(
            player,
            requireNotNull(effects.entity(effects.id(MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX, target.id))),
            EquipmentSlot.HAND,
        )
        graph.incidentSet.onInteractEntity(thirdEntryEvent) shouldBe true
        runtime.region.contains(player.location) shouldBe false
        val exitEvent = PlayerInteractEntityEvent(
            player,
            requireNotNull(effects.entity(effects.id(MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX, "exit:${target.id}"))),
            EquipmentSlot.HAND,
        )
        graph.incidentSet.onInteractEntity(exitEvent) shouldBe true
        exitEvent.isCancelled shouldBe true
        runtime.region.contains(player.location) shouldBe true

        // Re-enter for the rescue itself. Finding the NPC requires an active travel
        // session and being inside the maze, close to the NPC itself.
        player.teleport(Location(world, target.position.x + 0.5, target.position.y + 1.0, target.position.z + 0.5))
        val finalEntryEvent = PlayerInteractEntityEvent(
            player,
            requireNotNull(effects.entity(effects.id(MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX, target.id))),
            EquipmentSlot.HAND,
        )
        graph.incidentSet.onInteractEntity(finalEntryEvent) shouldBe true
        finalEntryEvent.isCancelled shouldBe true
        runtime.region.contains(player.location) shouldBe false

        // Move to the canonical NPC location so the distance and maze containment
        // guards are exercised by the successful interaction.
        val miner = requireNotNull(effects.entity(effects.singleId(MineIncidentEntityKind.MINER)))
        player.teleport(miner.location)
        runtime.region.contains(player.location) shouldBe false
        val foundEvent = PlayerInteractEntityEvent(
            player,
            requireNotNull(effects.entity(effects.singleId(MineIncidentEntityKind.MINER))),
            EquipmentSlot.HAND,
        )
        graph.incidentSet.onInteractEntity(foundEvent) shouldBe true
        foundEvent.isCancelled shouldBe true
        runtime.state.phase shouldBe MinePhase.MINING
        runtime.region.contains(player.location) shouldBe true
        effects.count(MineIncidentEntityKind.MINER) shouldBe 0
        effects.count(MineIncidentEntityKind.MINER_MAZE_ENTRANCE) shouldBe 0
        effects.count(MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX) shouldBe 0
        effects.count(MineIncidentEntityKind.MINER_CAMP_LANTERN) shouldBe 0
        effects.count(MineIncidentEntityKind.MINER_CAMP_SUPPLIES) shouldBe 0
        effects.count(MineIncidentEntityKind.RESCUE_CREATURE) shouldBe 0
        }
    }

    test("repeated admin lost-miner waits until the previous maze is restored") {
        requiredMockBukkitScenario {
        val world = paper.server.addSimpleWorld("world")
        for (chunkX in -3..3) for (chunkZ in -3..3) world.getChunkAt(chunkX, chunkZ).load()
        val floors = (1..6).map { x -> world.getBlockAt(x, 63, 5).also { it.type = Material.STONE } }
        val effects = RecordingIncidentEntities()
        val graph = entityGraph(paper, effects, "RescueRepeat")
        val runtime = graph.registry.byId("old_shafts")!!
        replaceEntityIndex(graph, runtime, floors)
        // Classic staged mines offer rescue during extraction; both forced starts
        // must use a scheduler-eligible phase rather than bypassing its contract.
        runtime.state = runtime.state.copy(phase = MinePhase.EXTRACTION)

        graph.lostMiner.start(runtime, now = 1_000L) shouldBe true
        repeat(64) { graph.lostMiner.process() }
        graph.admin.forceIncident("old_shafts", MineIncidentType.LOST_MINER, 2_000L) shouldBe false
        runtime.state.phase shouldBe MinePhase.EXTRACTION

        repeat(64) { graph.lostMiner.process() }
        graph.lostMiner.isRestoring(runtime) shouldBe false
        io.kotest.assertions.withClue(graph.admin.incidentDiagnostics("old_shafts", MineIncidentType.LOST_MINER)) {
            graph.admin.forceIncident("old_shafts", MineIncidentType.LOST_MINER, 3_000L) shouldBe true
        }
        runtime.state.incident?.type shouldBe MineIncidentType.LOST_MINER
        }
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

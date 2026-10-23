package ru.ruscrafting.farms.paper.mine.incident.entity

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetState
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.mineV2Settings

class MineObjectiveMarkerChunkCleanupMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("idle cleanup waits for supplied entities and never scans unrelated chunks") {
        val nativeWorld = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("MineMarkerCleanup")
        val world = mockk<World>()
        val loadedChunk = mockk<Chunk>()
        val laterChunk = mockk<Chunk>()
        every { loadedChunk.world } returns world
        every { loadedChunk.isLoaded } returns true
        every { loadedChunk.isEntitiesLoaded } returns false
        every { laterChunk.world } returns world
        every { laterChunk.isLoaded } returns true
        every { laterChunk.isEntitiesLoaded } returns true
        every { world.loadedChunks } returns arrayOf(loadedChunk, laterChunk)
        val settings = mineV2Settings()
        val runtime = MineRuntime(
            settings,
            CuboidActivityRegion(world, settings.id, CuboidBounds(0, 0, 0, 15, 128, 15)),
            5_000L,
            MineShiftState(engineVersion = 2, phase = MinePhase.MINING, sequence = 4),
        )

        fun tagged(kind: MineIncidentEntityKind, sequence: Long, zoneId: String = settings.id, x: Double = 1.5) =
            nativeWorld.spawnEntity(Location(nativeWorld, x, 64.0, 1.5), EntityType.ARMOR_STAND).also { entity ->
                entity.persistentDataContainer.apply {
                    set(NamespacedKey(plugin, "mine_incident_entity"), PersistentDataType.INTEGER, 1)
                    set(NamespacedKey(plugin, "mine_incident_kind"), PersistentDataType.STRING, kind.name)
                    set(NamespacedKey(plugin, "mine_incident_zone"), PersistentDataType.STRING, zoneId)
                    set(NamespacedKey(plugin, "mine_incident_sequence"), PersistentDataType.LONG, sequence)
                    set(NamespacedKey(plugin, "mine_incident_target"), PersistentDataType.STRING, "target")
                }
            }

        val oldMarker = tagged(MineIncidentEntityKind.GAS_MARKER, 1)
        val currentHitbox = tagged(MineIncidentEntityKind.POWER_MARKER_HITBOX, 4)
        val otherKind = tagged(MineIncidentEntityKind.CREATURE, 1)
        val otherZone = tagged(MineIncidentEntityKind.GAS_MARKER, 1, zoneId = "another_mine")
        val untagged = nativeWorld.spawnEntity(Location(nativeWorld, 2.5, 64.0, 1.5), EntityType.ARMOR_STAND)
        val lateOrphan = tagged(MineIncidentEntityKind.FLOOD_MARKER, 2, x = 65.5)
        val event = EntitiesLoadEvent(loadedChunk, listOf(oldMarker, currentHitbox, otherKind, otherZone, untagged))
        every { laterChunk.entities } returns arrayOf(lateOrphan)
        val scene = MineObjectiveMarkerScene(PaperMineIncidentEntityEffects(plugin))

        scene.reconcileChunk(runtime, loadedChunk)

        oldMarker.isValid shouldBe true
        currentHitbox.isValid shouldBe true
        verify(exactly = 0) { loadedChunk.entities }
        verify(exactly = 0) { laterChunk.entities }
        verify(exactly = 0) { world.loadedChunks }

        scene.onEntitiesLoad(runtime, event.chunk, event.entities)
        scene.onEntitiesLoad(runtime, event.chunk, event.entities)

        oldMarker.isValid shouldBe false
        currentHitbox.isValid shouldBe false
        otherKind.isValid shouldBe true
        otherZone.isValid shouldBe true
        untagged.isValid shouldBe true
        lateOrphan.isValid shouldBe true
        verify(exactly = 0) { loadedChunk.entities }
        verify(exactly = 0) { laterChunk.entities }
        verify(exactly = 0) { world.loadedChunks }

        scene.reconcileChunk(runtime, laterChunk)

        lateOrphan.isValid shouldBe false
        verify(exactly = 1) { laterChunk.entities }
        verify(exactly = 0) { world.loadedChunks }
    }

    test("active markers wait for entities then retain one canonical marker across both events") {
        val nativeWorld = paper.server.addSimpleWorld("world")
        val plugin = paper.createSimplePlugin("MineActiveMarkerCleanup")
        val world = mockk<World>()
        val chunk = mockk<Chunk>()
        every { world.name } returns nativeWorld.name
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns false
        every { world.isChunkLoaded(0, 0) } returns true
        every { world.getChunkAt(0, 0) } returns chunk
        every { chunk.world } returns world
        every { chunk.x } returns 0
        every { chunk.z } returns 0
        every { chunk.isLoaded } returns true
        every { chunk.isEntitiesLoaded } returns false
        val settings = mineV2Settings()
        val position = WorksitePosition(nativeWorld.name, 3, 63, 3)
        nativeWorld.getBlockAt(position.x, position.y, position.z).type = Material.AMETHYST_CLUSTER
        val runtime = MineRuntime(
            settings,
            CuboidActivityRegion(world, settings.id, CuboidBounds(0, 0, 0, 15, 128, 15)),
            5_000L,
            MineShiftState(
                engineVersion = 2, phase = MinePhase.INCIDENT, sequence = 4,
                incident = MineIncidentState(MineIncidentType.CRYSTAL_RESONANCE, required = 1),
                objective = WorksiteObjectiveState(
                    WorksiteObjectiveKey(settings.id, "incident_crystal_resonance", 4), required = 1,
                    targets = listOf(ObjectiveTargetState("crystal_1", position, ObjectiveTargetRole("crystal"), 0)),
                ),
            ),
        )
        val effects = PaperMineIncidentEntityEffects(plugin)
        val marker = requireNotNull(effects.entity(effects.spawn(runtime, MineIncidentEntityKind.CRYSTAL_MARKER, "crystal_1", position)))
        val duplicate = requireNotNull(effects.entity(effects.spawn(runtime, MineIncidentEntityKind.CRYSTAL_MARKER, "crystal_1", position)))
        val oldMarker = requireNotNull(effects.entity(effects.spawn(
            runtime.copy(state = runtime.state.copy(sequence = 1)), MineIncidentEntityKind.CRYSTAL_MARKER, "crystal_1", position,
        )))
        val event = EntitiesLoadEvent(chunk, listOf(marker, duplicate, oldMarker))
        val scene = MineObjectiveMarkerScene(effects)

        scene.reconcileChunk(runtime, chunk)

        nativeWorld.entities.size shouldBe 3
        verify(exactly = 0) { chunk.entities }
        verify(exactly = 0) { world.loadedChunks }

        scene.onEntitiesLoad(runtime, event.chunk, event.entities)
        scene.onEntitiesLoad(runtime, event.chunk, event.entities)

        marker.isValid shouldBe true
        duplicate.isValid shouldBe false
        oldMarker.isValid shouldBe false
        nativeWorld.entities.map { it.uniqueId } shouldBe listOf(marker.uniqueId)
        verify(exactly = 0) { chunk.entities }
        verify(exactly = 0) { world.loadedChunks }

        every { chunk.isEntitiesLoaded } returns true
        every { chunk.entities } returns arrayOf(marker)
        scene.reconcileChunk(runtime, chunk)

        nativeWorld.entities.map { it.uniqueId } shouldBe listOf(marker.uniqueId)
        verify(exactly = 1) { chunk.entities }
        verify(exactly = 0) { world.loadedChunks }
    }
})

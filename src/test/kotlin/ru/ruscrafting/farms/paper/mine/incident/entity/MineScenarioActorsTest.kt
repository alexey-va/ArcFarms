package ru.ruscrafting.farms.paper.mine.incident.entity

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.World
import org.bukkit.event.entity.EntityDeathEvent
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.domain.MineScenarioAction
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import java.util.UUID

class MineScenarioActorsTest : FunSpec({
    test("reconcile tracks incomplete targets and death only credits killed husks") {
        val target = ObjectiveTargetState("stage_defend_0", WorksitePosition("world", 1, 64, 1), ObjectiveTargetRole("defend"), 0)
        val id = UUID.randomUUID()
        val effects = FakeEffects(id)
        val actors = MineScenarioActors(effects)
        val runtime = runtime()
        actors.reconcileChunk(runtime, mockk<Chunk>(), MineScenarioAction.DEFEND, listOf(target))
        effects.chunkReconciles shouldBe 1
        effects.spawned shouldBe 0

        val entity = mockk<LivingEntity>()
        every { entity.uniqueId } returns id
        every { entity.killer } returns mockk()
        val event = mockk<EntityDeathEvent>(relaxed = true)
        every { event.entity } returns entity
        every { event.drops } returns mutableListOf()
        effects.identityValue = MineIncidentEntityIdentity(MineIncidentEntityKind.CREATURE, "mine", 7, target.id)
        actors.death(event)?.targetId shouldBe target.id
    }

    test("normal reconcile repairs tracked gaps without chunk scans or broad cleanup") {
        val target = ObjectiveTargetState("stage_defend_0", WorksitePosition("world", 1, 64, 1), ObjectiveTargetRole("defend"), 0)
        val effects = FakeEffects(UUID.randomUUID())
        val actors = MineScenarioActors(effects)
        actors.reconcile(runtime(), MineScenarioAction.DEFEND, listOf(target))
        effects.spawned shouldBe 1
        effects.chunkReconciles shouldBe 0
        effects.cleanups shouldBe 0
    }

    test("changing to a non-actor stage removes tracked actors without a world cleanup") {
        val target = ObjectiveTargetState("stage_defend_0", WorksitePosition("world", 1, 64, 1), ObjectiveTargetRole("defend"), 0)
        val effects = FakeEffects(UUID.randomUUID())
        val actors = MineScenarioActors(effects)
        actors.reconcile(runtime(), MineScenarioAction.DEFEND, listOf(target))
        actors.reconcile(runtime(), MineScenarioAction.INTERACT, listOf(target))
        effects.removed shouldBe 1
        effects.cleanups shouldBe 0
    }

    test("injured miner uses a villager actor and follows a carrier without moving the player") {
        val owner = UUID.randomUUID()
        val target = ObjectiveTargetState(
            "stage_stretcher_0", WorksitePosition("world", 1, 64, 1), ObjectiveTargetRole("carry"), 0,
            ObjectiveTargetStatus.LEASED, owner, 1_000L,
        )
        val effects = FakeEffects(UUID.randomUUID())
        val actors = MineScenarioActors(effects)
        val runtime = runtime(MineIncidentType.INJURED_MINER)
        val player = mockk<Player>(relaxed = true)
        val world = mockk<World>()
        every { player.uniqueId } returns owner
        every { world.name } returns "world"
        every { player.location } returns org.bukkit.Location(world, 10.5, 64.0, 10.5, 90f, 0f)
        mockkStatic(Bukkit::class)
        try {
            every { Bukkit.getPlayer(owner) } returns player
            actors.reconcile(runtime, MineScenarioAction.CARRY, listOf(target))
            effects.spawnedKinds shouldBe listOf(MineIncidentEntityKind.MINER)
            verify { effects.spawnedEntity.teleport(any<org.bukkit.Location>()) }
            every { effects.spawnedEntity.world } returns world
            every { effects.spawnedEntity.location } returns player.location
            actors.reconcile(runtime, MineScenarioAction.CARRY, listOf(target.copy(status = ObjectiveTargetStatus.AVAILABLE, leasedBy = null, leaseExpiresAt = 0L)))
            verify { effects.spawnedEntity.teleport(match<org.bukkit.Location> { it.x == 1.5 && it.y == 65.0 && it.z == 1.5 }) }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }

    test("injured miner cleanup includes the villager kind") {
        val target = ObjectiveTargetState("stage_jacks_0", WorksitePosition("world", 1, 64, 1), ObjectiveTargetRole("interact"), 0)
        val effects = FakeEffects(UUID.randomUUID())
        val actors = MineScenarioActors(effects)
        actors.reconcile(runtime(MineIncidentType.INJURED_MINER), MineScenarioAction.INTERACT, listOf(target))
        actors.cleanup(runtime(MineIncidentType.INJURED_MINER))
        effects.cleanedKinds shouldContain MineIncidentEntityKind.MINER
    }
}) {
    companion object {
        private fun runtime(type: MineIncidentType? = null): MineRuntime {
            val settings = mockk<MineZoneSettings>()
            every { settings.id } returns "mine"
            val runtime = mockk<MineRuntime>()
            every { runtime.settings } returns settings
            every { runtime.state } returns MineShiftState(sequence = 7, incident = type?.let { MineIncidentState(it, 2) })
            return runtime
        }
    }

    private class FakeEffects(private val id: UUID) : MineIncidentEntityEffects {
        var spawned = 0
        var removed = 0
        var chunkReconciles = 0
        var cleanups = 0
        val spawnedKinds = mutableListOf<MineIncidentEntityKind>()
        val cleanedKinds = mutableListOf<MineIncidentEntityKind>()
        val spawnedEntity = mockk<Entity>(relaxed = true)
        var identityValue: MineIncidentEntityIdentity? = null
        override fun spawn(runtime: MineRuntime, kind: MineIncidentEntityKind, targetId: String, position: WorksitePosition): UUID {
            spawned++
            spawnedKinds += kind
            return id
        }
        override fun identity(entity: Entity): MineIncidentEntityIdentity? = identityValue
        override fun entity(id: UUID): Entity? = if (id == this.id) spawnedEntity else null
        override fun remove(id: UUID) { removed++ }
        override fun reconcileChunk(runtime: MineRuntime, chunk: Chunk, kind: MineIncidentEntityKind, expected: Map<String, WorksitePosition>): Map<String, UUID> {
            chunkReconciles++
            return expected.keys.associateWith { id }
        }
        override fun cleanup(runtime: MineRuntime, kind: MineIncidentEntityKind) { cleanups++; cleanedKinds += kind }
    }
}

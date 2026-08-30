package ru.ruscrafting.farms.paper.farm.incident.bird

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.EntityType
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.persistence.PersistentDataType
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmSpecialIncidentSettings
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink

class FarmBirdIncidentMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var server: ServerMock
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        world = server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach { paper.close() }

    test("an authorized arrow collision defeats a protected farm bird exactly once") {
        val player = server.addPlayer("Archer")
        val special = mockk<FarmSpecialIncidentSettings>(relaxed = true) {
            every { birdRangedContribution } returns 2
            every { birdMeleeContribution } returns 1
        }
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "communal_farm"
            every { permission } returns "arcfarms.farm"
            every { crops } returns setOf("WHEAT")
            every { specialIncidents } returns special
        }
        val runtime = FarmRuntime(
            settings = zone,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(
                phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.BIRDS,
                incidentRequired = 1,
                sequence = 7,
            ),
        )
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { players(any()) } returns listOf(player)
            every { hasAccess(player, "arcfarms.farm") } returns true
        }
        val locale = mockk<ArcFarmsLocale>(relaxed = true) {
            every { render(MessageKey.FARM_BIRD_NAME, any(), any()) } returns Component.text("Птица")
        }
        val plugin = paper.createSimplePlugin("BirdIncidentTest")
        val controller = FarmBirdIncident(
            plugin = plugin,
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            locale = locale,
            debug = ArcFarmsDebug({ false }) {},
            access = port,
            audience = port,
            state = port,
            tasks = port,
            ledger = mockk<FarmBlockLedger>(relaxed = true),
            beds = FarmIncidentBedProvider { emptySet() },
            transitions = FarmTransitionSink { target, result, _ -> target.state = result.state },
        )
        val bird = world.spawnEntity(Location(world, 4.5, 65.0, 4.5), EntityType.ARMOR_STAND)
        bird.persistentDataContainer.set(
            NamespacedKey(plugin, "farm_bird_zone"),
            PersistentDataType.STRING,
            "communal_farm",
        )
        bird.persistentDataContainer.set(
            NamespacedKey(plugin, "farm_bird_sequence"),
            PersistentDataType.LONG,
            7L,
        )
        val arrow = mockk<AbstractArrow>(relaxed = true) {
            every { shooter } returns player
            every { type } returns EntityType.ARROW
        }
        val event = ProjectileHitEvent(arrow, bird)

        controller.onProjectileHit(event, listOf(runtime)) shouldBe true
        controller.onProjectileHit(ProjectileHitEvent(arrow, bird), listOf(runtime)) shouldBe true
        event.isCancelled shouldBe true
        runtime.state.phase shouldBe FarmPhase.HARVESTING
        runtime.state.incidentResolved shouldBe true
        runtime.state.contributors[player.uniqueId] shouldBe 2
        verify(exactly = 1) { arrow.remove() }
        bird.isValid shouldBe false
    }

    test("bird incident places twice the visible flock while keeping the configured defeat quota") {
        val available = (0 until 10).mapTo(linkedSetOf()) { x -> FarmPlotPosition(world.name, x, 64, 0) }
        val special = mockk<FarmSpecialIncidentSettings>(relaxed = true) {
            every { birdCount(available.size) } returns 3
            every { birdSpawnMultiplier } returns 2
        }
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "communal_farm"
            every { specialIncidents } returns special
        }
        val runtime = FarmRuntime(
            zone,
            CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
            emptyMap(),
            emptyList(),
            mockk(relaxed = true),
            FarmShiftState(phase = FarmPhase.INCIDENT, incidentType = FarmIncidentType.BIRDS, sequence = 4),
        )
        val controller = FarmBirdIncident(
            plugin = paper.createSimplePlugin("BirdPlanningTest"),
            settings = { mockk(relaxed = true) },
            locale = mockk(relaxed = true),
            debug = ArcFarmsDebug({ false }) {},
            access = mockk<WorksiteRuntimePort>(relaxed = true),
            audience = mockk<WorksiteRuntimePort>(relaxed = true),
            state = mockk<WorksiteRuntimePort>(relaxed = true),
            tasks = mockk<WorksiteRuntimePort>(relaxed = true),
            ledger = mockk(relaxed = true),
            beds = FarmIncidentBedProvider { available },
            transitions = FarmTransitionSink { target, result, _ -> target.state = result.state },
        )

        controller.initialize(runtime) shouldBe true
        runtime.state.incidentRequired shouldBe 3
        runtime.state.specialIncident?.plots?.size shouldBe 6
    }

    test("a WorldGuard-cancelled arrow damage event is resolved directly and only once") {
        val player = server.addPlayer("ProtectedArcher")
        val special = mockk<FarmSpecialIncidentSettings>(relaxed = true) {
            every { birdRangedContribution } returns 3
            every { birdMeleeContribution } returns 1
        }
        val zone = mockk<FarmZoneSettings>(relaxed = true) {
            every { id } returns "communal_farm"
            every { permission } returns "arcfarms.farm"
            every { crops } returns setOf("WHEAT")
            every { specialIncidents } returns special
        }
        val runtime = FarmRuntime(
            settings = zone,
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(
                phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.BIRDS,
                incidentRequired = 2,
                sequence = 9,
            ),
        )
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { hasAccess(player, "arcfarms.farm") } returns true
        }
        val plugin = paper.createSimplePlugin("CancelledBirdDamageTest")
        val controller = FarmBirdIncident(
            plugin = plugin,
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            locale = mockk(relaxed = true),
            debug = ArcFarmsDebug({ false }) {},
            access = port,
            audience = port,
            state = port,
            tasks = port,
            ledger = mockk(relaxed = true),
            beds = FarmIncidentBedProvider { emptySet() },
            transitions = FarmTransitionSink { target, result, _ -> target.state = result.state },
        )
        val bird = world.spawnEntity(Location(world, 5.5, 65.0, 5.5), EntityType.ARMOR_STAND)
        bird.persistentDataContainer.set(NamespacedKey(plugin, "farm_bird_zone"), PersistentDataType.STRING, "communal_farm")
        bird.persistentDataContainer.set(NamespacedKey(plugin, "farm_bird_sequence"), PersistentDataType.LONG, 9L)
        val arrow = mockk<AbstractArrow>(relaxed = true) { every { shooter } returns player }
        val damage = EntityDamageByEntityEvent(arrow, bird, EntityDamageEvent.DamageCause.PROJECTILE, 1.0).apply {
            isCancelled = true
        }

        controller.onDamage(damage, listOf(runtime)) shouldBe true
        controller.onDamage(damage, listOf(runtime)) shouldBe true

        damage.isCancelled shouldBe true
        runtime.state.incidentProgress shouldBe 1
        runtime.state.contributors[player.uniqueId] shouldBe 3
        verify(exactly = 1) { arrow.remove() }
    }
})

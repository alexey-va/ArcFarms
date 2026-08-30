package ru.ruscrafting.farms.paper.farm.incident.fire

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmBarnFireSettings
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockPassability
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink

class FarmBarnFireIncidentMockBukkitTest : FunSpec({
    test("barn fire uses real protected fire blocks and the water cone extinguishes nearby hotspots") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("farm")
            world.getChunkAt(0, 0).load()
            for (x in 1..15) for (z in 1..15) world.getBlockAt(x, 64, z).type = Material.STONE
            val anchor = FarmPointPosition(world.name, 8.5, 65.0, 8.5)
            val fire = FarmBarnFireSettings(
                hotspotCount = 3,
                spawnPerTick = 3,
                placementRadius = 2,
                minSpacing = 1.0,
                verticalSearch = 2,
                sprayRange = 18.0,
                sprayHitRadius = 4.0,
                sprayCooldownTicks = 1,
                particleStep = 0.5,
                flameParticleIntervalTicks = 5,
                particleHotspotLimit = 3,
            )
            val zone = mockk<FarmZoneSettings> {
                every { id } returns "communal_farm"
                every { permission } returns "arcfarms.farm"
                every { barnFire } returns fire
            }
            val runtime = FarmRuntime(
                settings = zone,
                region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
                orders = emptyMap(),
                orderList = emptyList(),
                rules = mockk(relaxed = true),
                state = FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 3,
                    placementSequence = 9,
                    orderId = "test_order",
                    incidentType = FarmIncidentType.BARN_FIRE,
                ),
            )
            val port = mockk<WorksiteRuntimePort>(relaxed = true) {
                every { hasAccess(any(), any()) } returns true
                every { allowInteraction(any(), any()) } returns true
            }
            val controller = FarmBarnFireIncident(
                settings = {
                    mockk<ArcFarmsConfig> {
                        every { particles } returns false
                        every { sounds } returns false
                    }
                },
                debug = ArcFarmsDebug({ false }) {},
                access = port,
                audience = port,
                state = port,
                points = FarmPointProvider { _, kind ->
                    kind shouldBe FarmPointKind.PEN
                    anchor
                },
                transitions = FarmTransitionSink { target, result, _ -> target.state = result.state },
                blockPassability = MockBukkitFarmBlockPassability,
            )

            controller.initialize(runtime) shouldBe true
            controller.ensure(runtime)
            world.entities.size shouldBe 0
            val hotspots = runtime.state.specialIncident!!.points.map { it.location(world) }
            hotspots.size shouldBe 3
            hotspots.forEach { hotspot ->
                hotspot.block.type shouldBe Material.FIRE
                controller.protects(hotspot) shouldBe true
            }

            val player = paper.addPlayer("Firefighter")
            player.inventory.setItemInMainHand(ItemStack(Material.SPYGLASS))
            player.teleport(Location(world, 8.5, 65.0, 2.5))
            val aimed = player.location.clone().setDirection(
                anchor.location(world).toVector().subtract(player.eyeLocation.toVector()).normalize(),
            )
            player.teleport(aimed)
            val event = PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_AIR,
                player.inventory.itemInMainHand,
                null,
                BlockFace.SELF,
                EquipmentSlot.HAND,
            )

            controller.spray(event, runtime) shouldBe true
            event.isCancelled shouldBe true
            runtime.state.phase shouldBe FarmPhase.HARVESTING
            hotspots.forEach { hotspot ->
                hotspot.block.type shouldBe Material.AIR
                controller.protects(hotspot) shouldBe false
            }
        } finally {
            paper.close()
        }
    }

    test("loading the modeled crossbow is intercepted and fires the water jet at range") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("farm")
            for (chunkX in -2..1) for (chunkZ in -2..1) world.getChunkAt(chunkX, chunkZ).load()
            for (x in -32..31) for (z in -32..31) world.getBlockAt(x, 64, z).type = Material.STONE
            val anchor = FarmPointPosition(world.name, 8.5, 65.0, 8.5)
            val fire = FarmBarnFireSettings(
                hotspotCount = 1,
                spawnPerTick = 1,
                placementRadius = 1,
                minSpacing = 1.0,
                verticalSearch = 2,
                sprayRange = 32.0,
                sprayHitRadius = 3.2,
                sprayCooldownTicks = 1,
                particleStep = 0.5,
                flameParticleIntervalTicks = 5,
                particleHotspotLimit = 1,
            )
            val zone = mockk<FarmZoneSettings> {
                every { id } returns "communal_farm"
                every { permission } returns "arcfarms.farm"
                every { barnFire } returns fire
            }
            val runtime = FarmRuntime(
                settings = zone,
                region = CuboidActivityRegion(world, "farm", CuboidBounds(-64, 0, -64, 64, 128, 64)),
                orders = emptyMap(),
                orderList = emptyList(),
                rules = mockk(relaxed = true),
                state = FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 3,
                    placementSequence = 9,
                    orderId = "test_order",
                    incidentType = FarmIncidentType.BARN_FIRE,
                ),
            )
            val port = mockk<WorksiteRuntimePort>(relaxed = true) {
                every { hasAccess(any(), any()) } returns true
                every { allowInteraction(any(), any()) } returns true
            }
            val controller = FarmBarnFireIncident(
                settings = {
                    mockk<ArcFarmsConfig> {
                        every { particles } returns false
                        every { sounds } returns false
                    }
                },
                debug = ArcFarmsDebug({ false }) {},
                access = port,
                audience = port,
                state = port,
                points = FarmPointProvider { _, kind ->
                    kind shouldBe FarmPointKind.PEN
                    anchor
                },
                transitions = FarmTransitionSink { target, result, _ -> target.state = result.state },
                blockPassability = MockBukkitFarmBlockPassability,
            )
            controller.initialize(runtime) shouldBe true
            controller.ensure(runtime)
            val hotspot = runtime.state.specialIncident!!.points.single().location(world)

            val player = paper.addPlayer("Firefighter")
            val crossbow = ItemStack(Material.CROSSBOW)
            player.inventory.setItemInMainHand(crossbow)
            val origin = hotspot.clone().subtract(0.0, 0.0, 25.0)
            player.teleport(origin.setDirection(hotspot.toVector().subtract(origin.toVector()).normalize()))
            val event = EntityLoadCrossbowEvent(player, crossbow, EquipmentSlot.HAND)

            controller.spray(event, runtime) shouldBe true
            event.isCancelled shouldBe true
            event.shouldConsumeItem() shouldBe false
            runtime.state.phase shouldBe FarmPhase.HARVESTING
            hotspot.block.type shouldBe Material.AIR
        } finally {
            paper.close()
        }
    }
})

private fun FarmPointPosition.location(world: org.bukkit.World): Location = Location(world, x, y, z, yaw, pitch)

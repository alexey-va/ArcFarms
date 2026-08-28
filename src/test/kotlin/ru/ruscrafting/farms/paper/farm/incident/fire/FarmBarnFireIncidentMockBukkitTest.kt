package ru.ruscrafting.farms.paper.farm.incident.fire

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
import ru.ruscrafting.farms.paper.farm.FarmPointProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink

class FarmBarnFireIncidentMockBukkitTest : FunSpec({
    test("barn fire uses real protected fire blocks and resolves through the water jet") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("farm")
            world.getChunkAt(0, 0).load()
            for (x in 1..15) for (z in 1..15) world.getBlockAt(x, 64, z).type = Material.STONE
            val anchor = FarmPointPosition(world.name, 8.5, 65.0, 8.5)
            val fire = FarmBarnFireSettings(
                hotspotCount = 1,
                spawnPerTick = 1,
                placementRadius = 4,
                minSpacing = 2.0,
                verticalSearch = 2,
                sprayRange = 18.0,
                sprayHitRadius = 1.25,
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
                port = port,
                points = FarmPointProvider { _, kind ->
                    kind shouldBe FarmPointKind.PEN
                    anchor
                },
                transitions = FarmTransitionSink { target, result, _ -> target.state = result.state },
            )

            controller.initialize(runtime) shouldBe true
            controller.ensure(runtime)
            world.entities.size shouldBe 0
            val hotspot = runtime.state.specialIncident!!.points.single().location(world)
            hotspot.block.type shouldBe Material.FIRE
            controller.protects(hotspot) shouldBe true

            val target = runtime.state.specialIncident!!.points.single()
            val player = paper.addPlayer("Firefighter")
            player.inventory.setItemInMainHand(ItemStack(Material.SPYGLASS))
            player.teleport(Location(world, 8.5, 65.0, 2.5))
            val aimed = player.location.clone().setDirection(
                target.location(world).toVector().subtract(player.eyeLocation.toVector()).normalize(),
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
            world.getBlockAt(target.x.toInt(), target.y.toInt(), target.z.toInt()).type shouldBe Material.AIR
            controller.protects(hotspot) shouldBe false
        } finally {
            paper.close()
        }
    }
})

private fun FarmPointPosition.location(world: org.bukkit.World): Location = Location(world, x, y, z, yaw, pitch)

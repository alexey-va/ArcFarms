package ru.ruscrafting.farms.paper.farm.incident.drought

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink

class FarmDroughtIsolationMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
        world.getChunkAt(2, 0).load()
    }

    afterEach {
        paper.close()
    }

    test("clearing one farm drought preserves water owned by another farm") {
        val player = paper.addPlayer("Admin")
        player.inventory.setItemInMainHand(ItemStack(Material.WATER_BUCKET))
        val first = runtime(world, "first", 0, 15, 4)
        val second = runtime(world, "second", 32, 47, 36)
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { hasAccess(any(), any()) } returns true
            every { runLater(any(), any()) } returns true
        }
        val controller = FarmDroughtIncident(
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            debug = ArcFarmsDebug({ false }) {},
            access = port,
            audience = port,
            state = port,
            tasks = port,
            blockLedger = mockk<FarmBlockLedger>(relaxed = true),
            blockRegistry = mockk<FarmBlockRegistry>(relaxed = true),
            beds = FarmIncidentBedProvider { emptySet() },
            transitions = FarmTransitionSink { runtime, result, _ -> runtime.state = result.state },
            clock = { 1_000L },
        )
        val firstSource = pour(player, world, first, 4)
        val secondSource = pour(player, world, second, 36)

        controller.handleInteraction(firstSource.event, first) shouldBe true
        controller.handleInteraction(secondSource.event, second) shouldBe true
        controller.hasActiveWater("first") shouldBe true
        controller.hasActiveWater("second") shouldBe true

        controller.clearZone("first", "admin_reset")

        controller.hasActiveWater("first") shouldBe false
        controller.hasActiveWater("second") shouldBe true
        firstSource.water.type shouldBe Material.AIR
        secondSource.water.type shouldBe Material.WATER
    }
}) {
    companion object {
        private data class Pour(val event: PlayerInteractEvent, val water: org.bukkit.block.Block)

        private fun runtime(world: WorldMock, id: String, minX: Int, maxX: Int, targetX: Int): FarmRuntime {
            val target = FarmPlotPosition(world.name, targetX, 64, 4)
            return FarmRuntime(
                settings = mockk<FarmZoneSettings>(relaxed = true) {
                    every { this@mockk.id } returns id
                    every { permission } returns "arcfarms.farm"
                    every { droughtInitialBeds } returns 1
                    every { droughtGrowthBeds } returns 1
                    every { droughtGrowthSeconds } returns 60
                    every { droughtPatches } returns 1
                },
                region = CuboidActivityRegion(world, id, CuboidBounds(minX, 0, 0, maxX, 128, 15)),
                orders = emptyMap(),
                orderList = emptyList(),
                rules = mockk(relaxed = true),
                state = FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    incidentType = FarmIncidentType.DROUGHT,
                    incidentRequired = 1,
                    droughtPlots = setOf(target),
                ),
            )
        }

        private fun pour(player: org.bukkit.entity.Player, world: WorldMock, runtime: FarmRuntime, x: Int): Pour {
            val soil = world.getBlockAt(x, 64, 4).apply { type = Material.DIRT }
            val water = soil.getRelative(BlockFace.UP)
            val event = PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                player.inventory.itemInMainHand,
                soil,
                BlockFace.UP,
                EquipmentSlot.HAND,
            )
            return Pour(event, water)
        }
    }
}

package ru.ruscrafting.farms.paper.farm.incident.drought

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmIncidentBedProvider
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.arc.paper.testing.MockBukkitTestRuntime

class FarmDroughtIncidentMockBukkitTest : FunSpec({
    lateinit var server: ServerMock
    lateinit var world: WorldMock
    lateinit var paper: MockBukkitTestRuntime

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        world = server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach {
        paper.close()
    }

    test("invalid infinite bucket placement is consumed and explains the drought target") {
        val player = server.addPlayer("Worker")
        player.teleport(world.spawnLocation)
        player.inventory.setItemInMainHand(ItemStack(Material.WATER_BUCKET))
        val clicked = world.getBlockAt(4, 64, 4).apply { type = Material.STONE }
        val event = PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_BLOCK,
            player.inventory.itemInMainHand,
            clicked,
            BlockFace.UP,
            EquipmentSlot.HAND,
        )
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { hasAccess(player, any()) } returns true
        }
        val runtime = FarmRuntime(
            settings = mockk<FarmZoneSettings> {
                every { id } returns "communal_farm"
                every { permission } returns "arcfarms.farm"
                every { droughtWaterRadius } returns 5
                every { droughtWaterSettleTicks } returns 21L
            },
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(
                phase = FarmPhase.INCIDENT,
                incidentType = FarmIncidentType.DROUGHT,
                incidentRequired = 0,
            ),
        )
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
            transitions = FarmTransitionSink { _, _, _ -> },
            clock = { 1_000L },
        )

        controller.handleInteraction(event, runtime) shouldBe true
        event.isCancelled shouldBe true
        verify(exactly = 1) { port.sendActionBar(player, MessageKey.FARM_DROUGHT_REQUIRED, emptyMap()) }
    }
})

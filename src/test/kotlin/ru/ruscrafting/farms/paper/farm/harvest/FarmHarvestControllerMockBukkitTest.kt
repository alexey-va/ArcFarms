package ru.ruscrafting.farms.paper.farm.harvest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.event.block.BlockBreakEvent
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmShiftStarter
import ru.ruscrafting.farms.paper.farm.FarmTaskHintSink
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.recovery.FarmFixedCropRecoveryController
import ru.ruscrafting.farms.paper.farm.recovery.FarmIncidentRecoveryController

class FarmHarvestControllerMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.server.addSimpleWorld("farm")
        world.getChunkAt(0, 0).load()
    }

    afterEach {
        paper.close()
    }

    test("fixed crop break stays cancelled while durable recovery accepts the harvest") {
        val plugin = paper.createSimplePlugin("FarmHarvestTest")
        val player = paper.addPlayer("Worker")
        val block = world.getBlockAt(5, 64, 5).apply { type = Material.MELON }
        val order = FarmOrder("test_order", mapOf(Material.MELON.name to 10))
        val runtime = FarmRuntime(
            settings = mockk<FarmZoneSettings> {
                every { id } returns "communal_farm"
                every { permission } returns "arcfarms.farm"
                every { crops } returns setOf(Material.MELON.name)
            },
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
            orders = mapOf(order.id to order),
            orderList = listOf(order),
            rules = FarmRules(listOf(50), 1, 1_000L),
            state = FarmShiftState(phase = FarmPhase.HARVESTING, orderId = order.id, sequence = 7L),
        )
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { hasAccess(player, "arcfarms.farm") } returns true
        }
        val fixedCrops = mockk<FarmFixedCropRecoveryController>(relaxed = true) {
            every { prepareHarvest(runtime, player, block, any(), any()) } returns true
        }
        val incidentRecovery = mockk<FarmIncidentRecoveryController>(relaxed = true) {
            every { pending(runtime) } returns false
        }
        val controller = FarmHarvestController(
            settings = { mockk<ArcFarmsConfig>(relaxed = true) },
            locale = mockk<ArcFarmsLocale>(relaxed = true),
            debug = ArcFarmsDebug({ false }) {},
            port = port,
            ledger = FarmBlockLedger(plugin),
            fixedCrops = fixedCrops,
            incidentRecovery = incidentRecovery,
            drought = mockk<FarmDroughtIncident>(relaxed = true),
            placement = mockk<FarmPlacementService>(relaxed = true),
            transitions = mockk<FarmTransitionSink>(relaxed = true),
            shiftStarter = mockk<FarmShiftStarter>(relaxed = true),
            taskHints = mockk<FarmTaskHintSink>(relaxed = true),
            runtimes = { listOf(runtime) },
            clock = { 1_000L },
        )
        val event = BlockBreakEvent(block, player).apply { expToDrop = 5 }

        controller.onBreak(event, runtime)

        event.isCancelled shouldBe true
        event.isDropItems shouldBe false
        event.expToDrop shouldBe 0
        block.type shouldBe Material.MELON
        verify(exactly = 1) { fixedCrops.prepareHarvest(runtime, player, block, 1_000L, any()) }
    }
})

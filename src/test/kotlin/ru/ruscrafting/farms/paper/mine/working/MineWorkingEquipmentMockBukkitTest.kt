package ru.ruscrafting.farms.paper.mine.working

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.MineOrderSettings
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.config.ZoneReference
import ru.ruscrafting.farms.domain.MineIncidentState
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.MineWorkingEngine
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemController
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemOwner
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.concurrent.CompletableFuture

class MineWorkingEquipmentMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var player: PlayerMock
    lateinit var runtime: MineRuntime
    lateinit var equipment: MineWorkingEquipment
    lateinit var state: WorksiteStatePort

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        val world = paper.server.addSimpleWorld("mine_working_equipment_test")
        val plugin = paper.createSimplePlugin("MineWorkingEquipmentTest")
        player = paper.server.addPlayer("EquipmentWorker")
        val placement = MineWorkingPlacement(WorksitePosition(world.name, 0, 64, 0), 0, "fixture-floor")
        val type = MineIncidentType.TUNNEL_DRIVE
        runtime = MineRuntime(
            settings = settings(world.name),
            region = CuboidActivityRegion(world, "mine_working_equipment_test", CuboidBounds(-16, 50, -16, 32, 80, 40)),
            cooldownMillis = 0L,
            state = MineShiftState(
                engineVersion = 2,
                phase = MinePhase.INCIDENT,
                sequence = 7L,
                incident = MineIncidentState(
                    type = type,
                    required = 1,
                    objectiveNonce = 9L,
                    working = MineWorkingEngine.initial(type, placement),
                ),
            ),
        )
        val registry = MineRuntimeRegistry().also { it.replace(listOf(runtime)) }
        state = mockk(relaxed = true)
        every { state.persistAsync() } returns CompletableFuture.completedFuture(Unit)
        lateinit var items: WorksiteServiceItemController
        items = WorksiteServiceItemController(plugin, object : WorksiteServiceItemOwner {
            override fun isActive(identity: ServiceItemIdentity): Boolean = equipment.isActive(identity)
            override fun release(playerId: java.util.UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) =
                equipment.release(playerId, identity, reason)
        })
        equipment = MineWorkingEquipment(registry, items, state, null)
    }

    afterEach { paper.close() }

    test("reserves the lease before service-item creation") {
        equipment.issue(runtime, player, "supports", Material.SPRUCE_LOG, cargo = false) shouldBe true

        runtime.state.incident?.serviceLeases?.keys?.single() shouldBe
            "supports_${player.uniqueId.toString().replace("-", "")}"
        player.inventory.storageContents.filterNotNull().count { it.type == Material.SPRUCE_LOG } shouldBe 1
    }

    test("rail cassette cannot duplicate its shared lease and is reclaimed on scene cleanup") {
        equipment.issue(runtime,player,"rail_cassette",Material.RAIL,cargo=true) shouldBe true
        equipment.issue(runtime,player,"rail_cassette",Material.RAIL,cargo=true) shouldBe true
        player.inventory.storageContents.filterNotNull().count { it.type==Material.RAIL } shouldBe 1
        val other=paper.server.addPlayer("OtherWorker")
        equipment.issue(runtime,other,"rail_cassette",Material.RAIL,cargo=true) shouldBe false
        equipment.has(runtime,player,"rail_cassette") shouldBe true
        equipment.clear(runtime)
        equipment.has(runtime,player,"rail_cassette") shouldBe false
        runtime.state.incident!!.serviceLeases shouldBe emptyMap()
        player.inventory.storageContents.filterNotNull().none { it.type==Material.RAIL } shouldBe true
    }
    test("failed service-item creation rolls back the reserved lease") {
        repeat(player.inventory.storageContents.size) { slot ->
            player.inventory.setItem(slot, org.bukkit.inventory.ItemStack(Material.COBBLESTONE, slot + 1))
        }

        equipment.issue(runtime, player, "supports", Material.SPRUCE_LOG, cargo = false) shouldBe false
        runtime.state.incident?.serviceLeases shouldBe emptyMap()
    }
}) {
    companion object {
        private fun settings(world: String) = MineZoneSettings(
            id = "mine_working_equipment_test",
            priority = 0,
            reference = ZoneReference(world, null, CuboidBounds(-16, 50, -16, 32, 80, 40)),
            permission = "arcfarms.mine",
            cartQuota = 4,
            hazardTrigger = 2,
            supportsRequired = 1,
            restoreSeconds = 2,
            temporaryMaterial = "DEEPSLATE",
            baseMaterial = "STONE",
            materialWeights = linkedMapOf("STONE" to 1),
            engineVersion = 2,
            orders = listOf(
                MineOrderSettings(
                    id = "fixture_order",
                    prospectingRequired = 1,
                    miningRequired = 1,
                    loadingRequired = 1,
                    incidentTypes = listOf(MineIncidentType.TUNNEL_DRIVE),
                ),
            ),
            incidentCountMin = 1,
            incidentCountMax = 1,
        )
    }
}

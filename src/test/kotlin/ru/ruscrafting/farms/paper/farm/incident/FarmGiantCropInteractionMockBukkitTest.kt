package ru.ruscrafting.farms.paper.farm.incident

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
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.FarmEventRouter
import ru.ruscrafting.farms.paper.farm.admin.FarmRouteAdminService
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.bird.FarmBirdIncident
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.incident.fire.FarmBarnFireIncident
import ru.ruscrafting.farms.paper.farm.incident.frost.FarmFrostIncident
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingIncident
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.perk.FarmPerkController
import ru.ruscrafting.farms.paper.farm.presentation.FarmHudController
import ru.ruscrafting.farms.paper.farm.recovery.FarmFixedCropRecoveryController
import ru.ruscrafting.farms.paper.farm.scene.FarmContractSceneController
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyController
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import java.util.concurrent.CompletableFuture

class FarmGiantCropInteractionMockBukkitTest : FunSpec({
    test("one left click routes an owned giant crop hit without requiring a tool") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 73,
                    placementSequence = 24,
                    orderId = "harvest_festival",
                    incidentType = FarmIncidentType.GIANT_CROP,
                ),
            )
            val player = fixture.paper.addPlayer("FruitBreaker")
            val block = fixture.world.getBlockAt(8, 64, 8).also { it.type = Material.PUMPKIN }
            val special = mockk<FarmSpecialIncidentController>(relaxed = true)
            every { special.handleGiantCropHit(runtime, player, block) } returns true
            val router = router(fixture, runtime, special)
            val event = PlayerInteractEvent(
                player,
                Action.LEFT_CLICK_BLOCK,
                ItemStack(Material.AIR),
                block,
                BlockFace.UP,
                EquipmentSlot.HAND,
            )

            router.onInteract(event) shouldBe true

            event.isCancelled shouldBe true
            verify(exactly = 1) { special.handleGiantCropHit(runtime, player, block) }
        } }
    }
})

private fun router(
    fixture: FarmIncidentScenarioFixture,
    runtime: FarmRuntime,
    special: FarmSpecialIncidentController,
): FarmEventRouter = FarmEventRouter(
    locale = fixture.locale,
    debug = ArcFarmsDebug({ false }) {},
    access = fixture.port,
    audience = fixture.port,
    state = fixture.port,
    runtimes = { listOf(runtime) },
    worldAdmin = mockk<FarmWorldAdminService>(relaxed = true),
    ledger = mockk<FarmBlockLedger>(relaxed = true),
    registry = mockk<FarmBlockRegistry>(relaxed = true),
    fixedCrops = mockk<FarmFixedCropRecoveryController>(relaxed = true),
    field = mockk<FarmFieldController>(relaxed = true),
    care = mockk<FarmCareController>(relaxed = true),
    drought = mockk<FarmDroughtIncident>(relaxed = true),
    pests = mockk<FarmPestIncident>(relaxed = true),
    birds = mockk<FarmBirdIncident>(relaxed = true),
    foodDelivery = mockk<FarmFoodDeliveryIncident>(relaxed = true),
    routeAdmin = mockk<FarmRouteAdminService>(relaxed = true),
    perks = mockk<FarmPerkController>(relaxed = true),
    special = special,
    processing = mockk<FarmProcessingIncident>(relaxed = true),
    barnFire = mockk<FarmBarnFireIncident>(relaxed = true),
    frost = mockk<FarmFrostIncident>(relaxed = true),
    delivery = mockk<FarmDeliveryController>(relaxed = true),
    supplies = mockk<FarmSupplyController>(relaxed = true),
    scene = mockk<FarmContractSceneController>(relaxed = true),
    harvest = mockk<FarmHarvestController>(relaxed = true),
    hud = mockk<FarmHudController>(relaxed = true),
    transitions = mockk(relaxed = true),
    shiftStartPending = { false },
    persistAsync = { CompletableFuture.completedFuture(Unit) },
    clock = { 1_000L },
)

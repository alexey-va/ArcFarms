package ru.ruscrafting.farms.paper.farm.incident

import io.papermc.paper.event.entity.EntityLoadCrossbowEvent
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
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.FarmEventRouter
import ru.ruscrafting.farms.paper.farm.admin.FarmRouteAdminService
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.enterprise.FarmEnterprisePort
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
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyKind
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import ru.ruscrafting.farms.config.CuboidBounds
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
            val router = farmEventRouter(fixture, runtime, special = special)
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

    listOf(Material.MELON, Material.PUMPKIN).forEach { material ->
        test("one left click instantly routes an ordinary ${material.name.lowercase()} harvest") {
            requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
                val runtime = fixture.runtime(
                    FarmShiftState(
                        phase = FarmPhase.HARVESTING,
                        sequence = 75,
                        orderId = "harvest_festival",
                    ),
                )
                val player = fixture.paper.addPlayer("FruitHarvester")
                val block = fixture.world.getBlockAt(9, 64, 9).also { it.type = material }
                val harvest = mockk<FarmHarvestController>(relaxed = true)
                every { harvest.onFixedCropHit(runtime, player, block) } returns true
                val router = farmEventRouter(fixture, runtime, harvest = harvest)
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
                verify(exactly = 1) { harvest.onFixedCropHit(runtime, player, block) }
            } }
        }
    }

    test("fire hose routes right click air by its tagged farm from fifteen blocks away") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 74,
                    placementSequence = 25,
                    orderId = "harvest_festival",
                    incidentType = FarmIncidentType.BARN_FIRE,
                ),
            ).copy(
                region = CuboidActivityRegion(fixture.world, "fire_test", CuboidBounds(40, 0, 40, 52, 128, 52)),
            )
            val player = fixture.paper.addPlayer("LongRangeFirefighter")
            val supplies = FarmSupplyController(
                fixture.plugin,
                fixture.locale,
                ArcFarmsDebug({ false }) {},
                { fixture.settings },
            )
            supplies.give(runtime, FarmSupplyKind.FIRE, player) shouldBe true
            player.teleport(fixture.location(fixture.barnPoint).add(0.0, 0.0, 15.0))
            val barnFire = mockk<FarmBarnFireIncident>(relaxed = true)
            every { barnFire.spray(any<PlayerInteractEvent>(), runtime) } returns true
            val router = farmEventRouter(fixture, runtime, supplies = supplies, barnFire = barnFire)
            val event = PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_AIR,
                player.inventory.itemInMainHand,
                null,
                BlockFace.SELF,
                EquipmentSlot.HAND,
            )

            router.onInteract(event) shouldBe true

            verify(exactly = 1) { barnFire.spray(event, runtime) }
        } }
    }

    test("tagged fire hose crossbow loading is cancelled and routed to water spray") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 76,
                    placementSequence = 26,
                    orderId = "harvest_festival",
                    incidentType = FarmIncidentType.BARN_FIRE,
                ),
            )
            val player = fixture.paper.addPlayer("CrossbowFirefighter")
            val supplies = FarmSupplyController(
                fixture.plugin,
                fixture.locale,
                ArcFarmsDebug({ false }) {},
                { fixture.settings },
            )
            supplies.give(runtime, FarmSupplyKind.FIRE, player) shouldBe true
            val equipment = player.inventory.itemInMainHand
            val barnFire = mockk<FarmBarnFireIncident>(relaxed = true)
            every { barnFire.spray(any<EntityLoadCrossbowEvent>(), runtime) } returns true
            val router = farmEventRouter(fixture, runtime, supplies = supplies, barnFire = barnFire)
            val event = EntityLoadCrossbowEvent(player, equipment, EquipmentSlot.HAND)

            router.onLoadCrossbow(event) shouldBe true

            event.isCancelled shouldBe true
            event.shouldConsumeItem() shouldBe false
            verify(exactly = 1) { barnFire.spray(event, runtime) }
        } }
    }
})

internal fun farmEventRouter(
    fixture: FarmIncidentScenarioFixture,
    runtime: FarmRuntime,
    special: FarmSpecialIncidentController = mockk(relaxed = true),
    supplies: FarmSupplyController = mockk(relaxed = true),
    barnFire: FarmBarnFireIncident = mockk(relaxed = true),
    harvest: FarmHarvestController = mockk(relaxed = true),
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
    barnFire = barnFire,
    frost = mockk<FarmFrostIncident>(relaxed = true),
    delivery = mockk<FarmDeliveryController>(relaxed = true),
    enterprise = mockk<FarmEnterprisePort>(relaxed = true),
    supplies = supplies,
    scene = mockk<FarmContractSceneController>(relaxed = true),
    harvest = harvest,
    hud = mockk<FarmHudController>(relaxed = true),
    transitions = mockk(relaxed = true),
    shiftStartPending = { false },
    persistAsync = { CompletableFuture.completedFuture(Unit) },
    clock = { 1_000L },
)

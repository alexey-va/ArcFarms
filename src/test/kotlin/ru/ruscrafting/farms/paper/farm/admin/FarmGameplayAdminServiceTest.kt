package ru.ruscrafting.farms.paper.farm.admin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmShiftLauncher
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.enterprise.FarmEnterprisePort
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.bird.FarmBirdIncident
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.incident.fire.FarmBarnFireIncident
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.presentation.FarmGuidanceController
import ru.ruscrafting.farms.paper.farm.recovery.FarmIncidentRecoveryController
import ru.ruscrafting.farms.paper.farm.scene.FarmContractSceneController
import ru.ruscrafting.farms.paper.farm.shift.FarmOrderCycleController
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyController
import java.util.concurrent.CompletableFuture

class FarmGameplayAdminServiceTest : FunSpec({
    test("starting an order cycle while the operator is already inside launches a shift immediately") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("sp11")
            world.getChunkAt(0, 0).load()
            val player = paper.addPlayer("CycleOperator")
            player.teleport(org.bukkit.Location(world, 8.5, 65.0, 8.5))
            val runtime = FarmRuntime(
                settings = mockk<FarmZoneSettings>(relaxed = true) { every { id } returns "communal_farm" },
                region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
                orders = emptyMap(),
                orderList = emptyList(),
                rules = mockk(relaxed = true),
                state = FarmShiftState(phase = FarmPhase.IDLE, sequence = 12),
            )
            val zoneId = runtime.settings.id
            val port = mockk<WorksiteRuntimePort>(relaxed = true)
            val cycle = mockk<FarmOrderCycleController>(relaxed = true) {
                every { set(any(), any()) } returns true
            }
            var launchCalls = 0
            val launcher = FarmShiftLauncher { active, actor, _, order ->
                active shouldBe runtime
                actor shouldBe player
                order shouldBe null
                launchCalls++
                true
            }
            val service = cycleService(runtime, port, cycle, launcher)

            service.startCycle(player, zoneId) shouldBe true

            launchCalls shouldBe 1
        } finally {
            paper.close()
        }
    }

    test("admin can start the configured terminal delivery and frost incidents") {
        val paper = MockBukkitTestRuntime.open()
        try {
            val world = paper.server.addSimpleWorld("sp11")
            world.getChunkAt(0, 0).load()
            val player = paper.addPlayer("Operator")
            player.teleport(world.spawnLocation)
            val plot = FarmPlotPosition(world.name, 4, 64, 4)
            world.getBlockAt(plot.x, plot.y, plot.z).type = Material.FARMLAND
            val order = FarmOrder("test_order", mapOf(Material.WHEAT.name to 64))
            val runtime = FarmRuntime(
                settings = mockk<FarmZoneSettings>(relaxed = true) {
                    every { id } returns "communal_farm"
                },
                region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
                orders = mapOf(order.id to order),
                orderList = listOf(order),
                rules = FarmRules(listOf(50), 8, 1_000L),
                state = FarmShiftState(
                    phase = FarmPhase.HARVESTING,
                    sequence = 7L,
                    orderId = order.id,
                    preparationCrop = Material.WHEAT.name,
                    preparationPatch = listOf(plot),
                    preparationRequired = 1,
                    preparationProgress = 1,
                    plantingProgress = 1,
                ),
            )
            val recovery = mockk<FarmIncidentRecoveryController>(relaxed = true) {
                every { pending(runtime) } returns false
            }
            val transitions = mockk<FarmTransitionSink>(relaxed = true)
            val harvest = mockk<FarmHarvestController>(relaxed = true) {
                every { nextRequiredCrop(any(), order) } returns null
            }
            val service = FarmGameplayAdminService(
                locale = mockk<ArcFarmsLocale>(relaxed = true),
                debug = ArcFarmsDebug({ false }) {},
                access = mockk<WorksiteRuntimePort>(relaxed = true),
                port = mockk<WorksiteRuntimePort>(relaxed = true),
                state = mockk<WorksiteRuntimePort>(relaxed = true),
                runtimes = { listOf(runtime) },
                orderCycle = mockk<FarmOrderCycleController>(relaxed = true),
                worldAdmin = mockk<FarmWorldAdminService>(relaxed = true),
                field = mockk<FarmFieldController>(relaxed = true),
                care = mockk<FarmCareController>(relaxed = true),
                drought = mockk<FarmDroughtIncident>(relaxed = true),
                pests = mockk<FarmPestIncident>(relaxed = true),
                birds = mockk<FarmBirdIncident>(relaxed = true),
                foodDelivery = mockk<FarmFoodDeliveryIncident>(relaxed = true),
                special = mockk<FarmSpecialIncidentController>(relaxed = true),
                processing = mockk<FarmProcessingIncident>(relaxed = true),
                barnFire = mockk<FarmBarnFireIncident>(relaxed = true),
                frost = mockk(relaxed = true),
                incidentRecovery = recovery,
                delivery = mockk<FarmDeliveryController>(relaxed = true),
                enterprise = mockk<FarmEnterprisePort>(relaxed = true),
                scene = mockk<FarmContractSceneController>(relaxed = true),
                supplies = mockk<FarmSupplyController>(relaxed = true),
                harvest = harvest,
                placement = mockk<FarmPlacementService>(relaxed = true),
                guidance = mockk<FarmGuidanceController>(relaxed = true),
                ledger = mockk<FarmBlockLedger>(relaxed = true),
                registry = mockk<FarmBlockRegistry>(relaxed = true),
                transitions = transitions,
                shiftLauncher = mockk<FarmShiftLauncher>(relaxed = true),
                persistAsync = { CompletableFuture.completedFuture(Unit) },
                clock = { 0L },
            )

            service.setStage(player, runtime.settings.id, "food-delivery") shouldBe true
            runtime.state.incidentType shouldBe FarmIncidentType.FOOD_DELIVERY
            verify(exactly = 1) { transitions.apply(runtime, match { it.state.incidentType == FarmIncidentType.FOOD_DELIVERY }, player) }

            service.setStage(player, runtime.settings.id, "frost") shouldBe true
            runtime.state.incidentType shouldBe FarmIncidentType.FROST
            verify(exactly = 1) { transitions.apply(runtime, match { it.state.incidentType == FarmIncidentType.FROST }, player) }
        } finally {
            paper.close()
        }
    }

    test("admin stage cleanup restores the complete incident journal instead of one tick budget") {
        val runtime = FarmRuntime(
            settings = mockk<FarmZoneSettings>(relaxed = true) {
                every { id } returns "communal_farm"
                every { restoreBlocksPerTick } returns 24
            },
            region = mockk<ActivityRegion>(relaxed = true),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk<FarmRules>(relaxed = true),
            state = FarmShiftState(),
        )
        val recovery = mockk<FarmIncidentRecoveryController>(relaxed = true) {
            every { restore(runtime, Int.MAX_VALUE, false) } returns 96
            every { pending(runtime) } returns false
        }
        val service = FarmGameplayAdminService(
            locale = mockk<ArcFarmsLocale>(relaxed = true),
            debug = ArcFarmsDebug({ false }) {},
            access = mockk<WorksiteRuntimePort>(relaxed = true),
            port = mockk<WorksiteRuntimePort>(relaxed = true),
            state = mockk<WorksiteRuntimePort>(relaxed = true),
            runtimes = { listOf(runtime) },
            orderCycle = mockk<FarmOrderCycleController>(relaxed = true),
            worldAdmin = mockk<FarmWorldAdminService>(relaxed = true),
            field = mockk<FarmFieldController>(relaxed = true),
            care = mockk<FarmCareController>(relaxed = true),
            drought = mockk<FarmDroughtIncident>(relaxed = true),
            pests = mockk<FarmPestIncident>(relaxed = true),
            birds = mockk<FarmBirdIncident>(relaxed = true),
            foodDelivery = mockk<FarmFoodDeliveryIncident>(relaxed = true),
            special = mockk<FarmSpecialIncidentController>(relaxed = true),
            processing = mockk<FarmProcessingIncident>(relaxed = true),
            barnFire = mockk<FarmBarnFireIncident>(relaxed = true),
            frost = mockk(relaxed = true),
            incidentRecovery = recovery,
            delivery = mockk<FarmDeliveryController>(relaxed = true),
            enterprise = mockk<FarmEnterprisePort>(relaxed = true),
            scene = mockk<FarmContractSceneController>(relaxed = true),
            supplies = mockk<FarmSupplyController>(relaxed = true),
            harvest = mockk<FarmHarvestController>(relaxed = true),
            placement = mockk<FarmPlacementService>(relaxed = true),
            guidance = mockk<FarmGuidanceController>(relaxed = true),
            ledger = mockk<FarmBlockLedger>(relaxed = true),
            registry = mockk<FarmBlockRegistry>(relaxed = true),
            transitions = mockk<FarmTransitionSink>(relaxed = true),
            shiftLauncher = mockk<FarmShiftLauncher>(relaxed = true),
            persistAsync = { CompletableFuture.completedFuture(Unit) },
            clock = { 0L },
        )
        val method = FarmGameplayAdminService::class.java.getDeclaredMethod(
            "clearActiveObjective",
            FarmRuntime::class.java,
            Player::class.java,
        ).apply { isAccessible = true }

        method.invoke(service, runtime, mockk<Player>(relaxed = true)) shouldBe true
        verify(exactly = 1) { recovery.restore(runtime, Int.MAX_VALUE, false) }
        verify(exactly = 0) { recovery.restore(runtime, 24, any()) }
    }

    test("successful admin reset releases and persists the exact enterprise reservation") {
        val runtime = FarmRuntime(
            settings = mockk<FarmZoneSettings>(relaxed = true) { every { id } returns "communal_farm" },
            region = mockk(relaxed = true),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(phase = FarmPhase.HARVESTING, sequence = 14),
        )
        val enterprise = mockk<FarmEnterprisePort> {
            every { orderCancelled("communal_farm", 14) } returns true
        }
        val cycle = mockk<FarmOrderCycleController> {
            every { set("communal_farm", true) } returns true
        }
        val field = mockk<FarmFieldController>(relaxed = true) {
            every { commitAfterRecovery(runtime, any(), any()) } answers { thirdArg<() -> Unit>().invoke() }
        }
        var persisted = 0
        val service = cycleService(
            runtime = runtime,
            port = mockk(relaxed = true),
            cycle = cycle,
            launcher = mockk(relaxed = true),
            enterprise = enterprise,
            field = field,
            persistAsync = { persisted++; CompletableFuture.completedFuture(Unit) },
        )

        service.setStage(mockk(relaxed = true), "communal_farm", "reset") shouldBe true

        verify(exactly = 1) { enterprise.orderCancelled("communal_farm", 14) }
        persisted shouldBe 1
    }

    test("failed admin reset keeps the enterprise reservation") {
        val runtime = FarmRuntime(
            settings = mockk<FarmZoneSettings>(relaxed = true) { every { id } returns "communal_farm" },
            region = mockk(relaxed = true),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(phase = FarmPhase.HARVESTING, sequence = 15),
        )
        val recovery = mockk<FarmIncidentRecoveryController>(relaxed = true) {
            every { pending(runtime) } returns true
        }
        val enterprise = mockk<FarmEnterprisePort>(relaxed = true)
        var persisted = 0
        val service = cycleService(
            runtime = runtime,
            port = mockk(relaxed = true),
            cycle = mockk(relaxed = true),
            launcher = mockk(relaxed = true),
            enterprise = enterprise,
            incidentRecovery = recovery,
            persistAsync = { persisted++; CompletableFuture.completedFuture(Unit) },
        )

        service.setStage(mockk(relaxed = true), "communal_farm", "reset") shouldBe false

        verify(exactly = 0) { enterprise.orderCancelled(any(), any()) }
        persisted shouldBe 0
    }
})

private fun cycleService(
    runtime: FarmRuntime,
    port: WorksiteRuntimePort,
    cycle: FarmOrderCycleController,
    launcher: FarmShiftLauncher,
    enterprise: FarmEnterprisePort = mockk(relaxed = true),
    incidentRecovery: FarmIncidentRecoveryController = mockk(relaxed = true),
    field: FarmFieldController = mockk(relaxed = true),
    persistAsync: () -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) },
): FarmGameplayAdminService = FarmGameplayAdminService(
    locale = mockk<ArcFarmsLocale>(relaxed = true),
    debug = ArcFarmsDebug({ false }) {},
    access = port,
    port = port,
    state = port,
    runtimes = { listOf(runtime) },
    orderCycle = cycle,
    worldAdmin = mockk<FarmWorldAdminService>(relaxed = true),
    field = field,
    care = mockk<FarmCareController>(relaxed = true),
    drought = mockk<FarmDroughtIncident>(relaxed = true),
    pests = mockk<FarmPestIncident>(relaxed = true),
    birds = mockk<FarmBirdIncident>(relaxed = true),
    foodDelivery = mockk<FarmFoodDeliveryIncident>(relaxed = true),
    special = mockk<FarmSpecialIncidentController>(relaxed = true),
    processing = mockk<FarmProcessingIncident>(relaxed = true),
    barnFire = mockk<FarmBarnFireIncident>(relaxed = true),
    frost = mockk(relaxed = true),
    incidentRecovery = incidentRecovery,
    delivery = mockk<FarmDeliveryController>(relaxed = true),
    enterprise = enterprise,
    scene = mockk<FarmContractSceneController>(relaxed = true),
    supplies = mockk<FarmSupplyController>(relaxed = true),
    harvest = mockk<FarmHarvestController>(relaxed = true),
    placement = mockk<FarmPlacementService>(relaxed = true),
    guidance = mockk<FarmGuidanceController>(relaxed = true),
    ledger = mockk<FarmBlockLedger>(relaxed = true),
    registry = mockk<FarmBlockRegistry>(relaxed = true),
    transitions = mockk<FarmTransitionSink>(relaxed = true),
    shiftLauncher = launcher,
    persistAsync = persistAsync,
    clock = { 5_000L },
)

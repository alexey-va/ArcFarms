package ru.ruscrafting.farms.paper.farm.admin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmShiftLauncher
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.bird.FarmBirdIncident
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.presentation.FarmGuidanceController
import ru.ruscrafting.farms.paper.farm.recovery.FarmIncidentRecoveryController
import ru.ruscrafting.farms.paper.farm.scene.FarmContractSceneController
import ru.ruscrafting.farms.paper.farm.shift.FarmOrderCycleController
import ru.ruscrafting.farms.paper.farm.supply.FarmSupplyController
import java.util.concurrent.CompletableFuture

class FarmGameplayAdminServiceTest : FunSpec({
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
            port = mockk<WorksiteRuntimePort>(relaxed = true),
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
            incidentRecovery = recovery,
            delivery = mockk<FarmDeliveryController>(relaxed = true),
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
})

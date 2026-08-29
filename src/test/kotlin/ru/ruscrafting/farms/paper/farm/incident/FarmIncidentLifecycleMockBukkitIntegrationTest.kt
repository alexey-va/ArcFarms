package ru.ruscrafting.farms.paper.farm.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockSpreadEvent
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmProcessingStage
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.WorksiteModuleRegistry
import ru.ruscrafting.farms.paper.farm.FarmEventRouter
import ru.ruscrafting.farms.paper.farm.admin.FarmRouteAdminService
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.delivery.FarmDeliveryController
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import ru.ruscrafting.farms.paper.farm.harvest.FarmHarvestController
import ru.ruscrafting.farms.paper.farm.incident.bird.FarmBirdIncident
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.incident.pest.FarmPestIncident
import ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingSceneRole
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

class FarmIncidentLifecycleMockBukkitIntegrationTest : FunSpec({
    test("two workers complete crop processing across a persisted controller restart") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            var runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 41,
                    placementSequence = 13,
                    orderId = "bakery_supply",
                    incidentType = FarmIncidentType.PROCESSING,
                    incidentCrop = "WHEAT",
                ),
            )
            var processing = fixture.processing()
            val workers = listOf(fixture.paper.addPlayer("Miller"), fixture.paper.addPlayer("Packer"))

            processing.initialize(runtime) shouldBe true
            repeat(fixture.zone.processing.inputPackages) { index ->
                val worker = workers[index % workers.size]
                fixture.clickProcessing(processing, runtime, worker, FarmProcessingSceneRole.RAW_INTERACTION, 0)
                fixture.deliverProcessingCargo(processing, runtime, worker, raw = true, tick = index * 5L)
            }
            runtime.state.processing?.stage shouldBe FarmProcessingStage.OPERATING

            processing.ensure(runtime)
            fixture.processingDisplays(FarmProcessingSceneRole.MACHINE).size shouldBe 1
            fixture.processingDisplays(FarmProcessingSceneRole.WHEEL).size shouldBe 0
            fixture.processingDisplays(FarmProcessingSceneRole.INPUT_RACK).size shouldBe 0
            val machine = fixture.processingDisplays(FarmProcessingSceneRole.MACHINE).single()
            val pulseCenter = fixture.zone.processing.dialPeriodTicks / 2 - runtime.state.placementSequence * 17L
            processing.update(listOf(runtime), Math.floorMod(pulseCenter, fixture.zone.processing.dialPeriodTicks.toLong()))
            machine.isGlowing shouldBe true
            processing.update(listOf(runtime), 0L)
            machine.isGlowing shouldBe false

            val period = fixture.zone.processing.dialPeriodTicks.toLong()
            repeat(2) { index ->
                advanceToDialCenter(fixture, runtime.state.placementSequence, period)
                fixture.clickProcessing(
                    processing,
                    runtime,
                    workers[index % workers.size],
                    FarmProcessingSceneRole.MACHINE_INTERACTION,
                )
                fixture.paper.performTicks(period)
            }
            runtime.state.processing?.cyclesCompleted shouldBe 2

            processing.cleanup("simulated_restart")
            runtime = fixture.persistAndReload(runtime)
            processing = fixture.processing()
            processing.ensure(runtime)
            runtime.state.processing?.cyclesCompleted shouldBe 2

            repeat(fixture.zone.processing.machineCycles - 2) { index ->
                advanceToDialCenter(fixture, runtime.state.placementSequence, period)
                fixture.clickProcessing(
                    processing,
                    runtime,
                    workers[index % workers.size],
                    FarmProcessingSceneRole.MACHINE_INTERACTION,
                )
                fixture.paper.performTicks(period)
            }
            runtime.state.processing?.stage shouldBe FarmProcessingStage.PACKING

            repeat(fixture.zone.processing.outputPackages) { index ->
                val worker = workers[(index + 1) % workers.size]
                fixture.clickProcessing(processing, runtime, worker, FarmProcessingSceneRole.PRODUCT_INTERACTION, 0)
                fixture.deliverProcessingCargo(processing, runtime, worker, raw = false, tick = 100L + index * 5L)
            }
            processing.ensure(runtime)

            runtime.state.phase shouldBe FarmPhase.HARVESTING
            runtime.state.incidentType shouldBe null
            runtime.state.processing shouldBe null
            runtime.state.incidentsResolved shouldBe 1
            runtime.state.contributors.values.sum() shouldBe
                fixture.zone.processing.inputPackages + fixture.zone.processing.machineCycles +
                fixture.zone.processing.outputPackages
            runtime.state.contributors.getValue(workers[0].uniqueId) shouldBeGreaterThan 0
            runtime.state.contributors.getValue(workers[1].uniqueId) shouldBeGreaterThan 0
            fixture.world.entities.filter(processing::owns) shouldHaveSize 0
        } }
    }

    test("real barn fire survives persistence, remains protected, and completes cooperatively") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            var runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 52,
                    placementSequence = 19,
                    orderId = "harvest_festival",
                    incidentType = FarmIncidentType.BARN_FIRE,
                    incidentCrop = "WHEAT",
                ),
            )
            var fire = fixture.barnFire()
            val workers = listOf(fixture.paper.addPlayer("FirefighterOne"), fixture.paper.addPlayer("FirefighterTwo"))

            fire.initialize(runtime) shouldBe true
            fire.ensure(runtime)
            val allPoints = requireNotNull(runtime.state.specialIncident).points
            allPoints shouldHaveSize fixture.zone.barnFire.hotspotCount
            allPoints.count { fixture.location(it).block.type == Material.FIRE } shouldBe
                fixture.zone.barnFire.spawnPerTick
            repeat(64) {
                if (allPoints.all { fixture.location(it).block.type == Material.FIRE }) return@repeat
                fire.ensure(runtime)
            }
            allPoints.count { fixture.location(it).block.type == Material.FIRE } shouldBe allPoints.size

            val router = fireSafetyRouter(fixture, runtime, fire)
            val hotspot = fixture.location(allPoints.first()).block
            BlockFadeEvent(hotspot, hotspot.state).also(router::onBlockFade).isCancelled shouldBe true
            BlockBurnEvent(fixture.world.getBlockAt(45, 64, 45), hotspot).also(router::onBlockBurn).isCancelled shouldBe true
            val ignite = BlockIgniteEvent(
                fixture.world.getBlockAt(60, 65, 60),
                BlockIgniteEvent.IgniteCause.SPREAD,
                null,
                hotspot,
            )
            router.onBlockIgnite(ignite)
            ignite.isCancelled shouldBe true
            BlockSpreadEvent(
                fixture.world.getBlockAt(60, 65, 60),
                hotspot,
                fixture.world.getBlockAt(60, 65, 60).state,
            ).also(router::onBlockSpread).isCancelled shouldBe true
            BlockBurnEvent(fixture.world.getBlockAt(70, 64, 70), null)
                .also(router::onBlockBurn).isCancelled shouldBe false

            repeat(3) { index -> fixture.sprayFire(fire, runtime, workers[index % 2], index) }
            requireNotNull(runtime.state.specialIncident).active shouldHaveSize allPoints.size - 3
            allPoints.count { fixture.location(it).block.type == Material.FIRE } shouldBe allPoints.size - 3

            runtime = fixture.persistAndReload(runtime)
            fire = fixture.barnFire()
            fire.ensure(runtime)
            requireNotNull(runtime.state.specialIncident).active shouldHaveSize allPoints.size - 3
            allPoints.count { fire.protects(fixture.location(it)) } shouldBe allPoints.size - 3

            requireNotNull(runtime.state.specialIncident).active.sorted().forEachIndexed { offset, index ->
                fixture.sprayFire(fire, runtime, workers[offset % workers.size], index)
            }
            fire.ensure(runtime)

            runtime.state.phase shouldBe FarmPhase.HARVESTING
            runtime.state.incidentType shouldBe null
            runtime.state.specialIncident shouldBe null
            runtime.state.incidentsResolved shouldBe 1
            allPoints.count { fixture.location(it).block.type == Material.FIRE } shouldBe 0
            allPoints.count { fire.protects(fixture.location(it)) } shouldBe 0
            runtime.state.contributors.values.sum() shouldBe allPoints.size
            runtime.state.contributors.getValue(workers[0].uniqueId) shouldBeGreaterThan 0
            runtime.state.contributors.getValue(workers[1].uniqueId) shouldBeGreaterThan 0
        } }
    }
})

private fun advanceToDialCenter(
    fixture: FarmIncidentScenarioFixture,
    placementSequence: Long,
    period: Long,
) {
    val shifted = Bukkit.getCurrentTick().toLong() + placementSequence * 17L
    val center = period / 2L
    val advance = Math.floorMod(center - Math.floorMod(shifted, period), period)
    if (advance > 0L) fixture.paper.performTicks(advance)
}

private fun fireSafetyRouter(
    fixture: FarmIncidentScenarioFixture,
    runtime: ru.ruscrafting.farms.paper.FarmRuntime,
    fire: ru.ruscrafting.farms.paper.farm.incident.fire.FarmBarnFireIncident,
): FarmEventRouter = FarmEventRouter(
    locale = fixture.locale,
    debug = ArcFarmsDebug({ false }) {},
    port = fixture.port,
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
    special = mockk<FarmSpecialIncidentController>(relaxed = true),
    processing = mockk(relaxed = true),
    barnFire = fire,
    delivery = mockk<FarmDeliveryController>(relaxed = true),
    supplies = mockk<FarmSupplyController>(relaxed = true),
    scene = mockk<FarmContractSceneController>(relaxed = true),
    harvest = mockk<FarmHarvestController>(relaxed = true),
    hud = mockk<FarmHudController>(relaxed = true),
    auxiliary = mockk<WorksiteModuleRegistry>(relaxed = true),
    transitions = mockk(relaxed = true),
    shiftStartPending = { false },
    persistAsync = { CompletableFuture.completedFuture(Unit) },
    clock = { 1_000L },
)

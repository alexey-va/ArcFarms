package ru.ruscrafting.farms.paper.farm.incident

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.entity.Mob
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockSpreadEvent
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmProcessingStage
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmBlockRegistry
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class FarmIncidentLifecycleMockBukkitIntegrationTest : FunSpec({
    test("processing cargo is easy to acquire and a physical lap turns the millstone") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            val runtime = fixture.runtime(
                FarmShiftState(
                    phase = FarmPhase.INCIDENT,
                    sequence = 40,
                    placementSequence = 12,
                    orderId = "bakery_supply",
                    incidentType = FarmIncidentType.PROCESSING,
                    incidentCrop = "WHEAT",
                ),
            )
            val processing = fixture.processing()
            val worker = fixture.paper.addPlayer("MillWalker")

            processing.initialize(runtime) shouldBe true
            repeat(12) { processing.ensure(runtime) }
            fixture.processingInteraction(processing, runtime, FarmProcessingSceneRole.RAW_INTERACTION, 0).let { interaction ->
                interaction.interactionWidth.shouldBeGreaterThanOrEqual(2.4f)
            }
            fixture.processingInteraction(processing, runtime, FarmProcessingSceneRole.MACHINE_INTERACTION)
                .interactionWidth.shouldBeGreaterThanOrEqual(3.0f)

            fixture.clickProcessing(
                processing,
                runtime,
                worker,
                FarmProcessingSceneRole.RAW_INTERACTION,
                0,
                horizontalOffset = 3.0,
            )
            processing.carrierCount(runtime.settings.id) shouldBe 1
            fixture.clickProcessing(
                processing,
                runtime,
                worker,
                FarmProcessingSceneRole.MACHINE_INTERACTION,
                horizontalOffset = 3.2,
            )
            requireNotNull(runtime.state.processing).inputLoaded shouldBe 1
            processing.carrierCount(runtime.settings.id) shouldBe 0

            val layout = ru.ruscrafting.farms.domain.FarmProcessingLayout.create(fixture.processingPoint)
            fixture.processingTextDisplays(FarmProcessingSceneRole.LABEL.name) shouldHaveSize 2
            fixture.processingTextDisplay(101).let { outputLabel ->
                outputLabel.location.x shouldBe layout.outputPallet.x
                outputLabel.location.y shouldBe layout.outputPallet.y + 1.25
                outputLabel.location.z shouldBe layout.outputPallet.z
            }
            repeat(fixture.zone.processing.inputPackages - 1) { index ->
                val packagePoint = ru.ruscrafting.farms.domain.FarmProcessingLayout.packagePosition(layout.inputRacks, 0)
                worker.teleport(fixture.location(packagePoint))
                processing.update(listOf(runtime), index * 10L + 1L)
                worker.teleport(fixture.location(layout.inputDrop))
                processing.update(listOf(runtime), index * 10L + 2L)
                processing.ensure(runtime)
            }
            runtime.state.processing?.stage shouldBe FarmProcessingStage.OPERATING

            repeat(8) { processing.ensure(runtime) }
            val crankTrack = fixture.processingTextDisplays("CRANK_TRACK")
            crankTrack.size shouldBe 24
            crankTrack.forEach { marker ->
                marker.transformation.scale.x.shouldBeGreaterThanOrEqual(1.5f)
            }

            val radius = (fixture.zone.processing.crankInnerRadius + fixture.zone.processing.crankOuterRadius) / 2.0
            repeat(49) { step ->
                val angle = 2.0 * PI * step / 48.0
                worker.teleport(
                    fixture.location(fixture.processingPoint).clone().add(radius * cos(angle), 0.0, radius * sin(angle)),
                )
                processing.update(listOf(runtime), 100L + step)
            }
            requireNotNull(runtime.state.processing).cyclesCompleted shouldBeGreaterThan 0
            verify(atLeast = 40) {
                fixture.port.spawnGuidanceDust(worker, any(), any(), any())
            }
            // MockBukkit does not emulate the client leash flags, but it does prove
            // that the owned anchor mob is created and participates in cleanup.
            fixture.world.entities.filterIsInstance<Mob>().single(processing::owns)
            verify(atLeast = 1) {
                fixture.port.showScreenTitle(
                    worker,
                    MessageKey.FARM_PROCESSING_OPERATING_TITLE,
                    any(),
                    "processing_hint",
                )
            }
        } }
    }

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
            processing.ensure(runtime)
            fixture.processingDisplays(FarmProcessingSceneRole.OUTPUT_PALLET).single().isGlowing shouldBe false
            fixture.processingDisplays(FarmProcessingSceneRole.RAW_PACKAGE)
                .all { display -> display.itemStack.type == Material.WHEAT } shouldBe true
            repeat(fixture.zone.processing.inputPackages) { index ->
                val worker = workers[index % workers.size]
                fixture.clickProcessing(processing, runtime, worker, FarmProcessingSceneRole.RAW_INTERACTION, index)
                val loadedBefore = requireNotNull(runtime.state.processing).inputLoaded
                if (index % 2 == 0) {
                    fixture.deliverProcessingCargo(processing, runtime, worker, raw = true, tick = index * 5L)
                } else {
                    fixture.clickProcessing(
                        processing,
                        runtime,
                        worker,
                        FarmProcessingSceneRole.MACHINE_INTERACTION,
                    )
                }
                requireNotNull(runtime.state.processing).inputLoaded shouldBe loadedBefore + 1
            }
            runtime.state.processing?.stage shouldBe FarmProcessingStage.OPERATING

            processing.ensure(runtime)
            fixture.processingDisplays(FarmProcessingSceneRole.MACHINE).size shouldBe 1
            fixture.processingDisplays(FarmProcessingSceneRole.WHEEL).size shouldBe 0
            fixture.processingDisplays(FarmProcessingSceneRole.INPUT_RACK).size shouldBe 0
            val machine = fixture.processingDisplays(FarmProcessingSceneRole.MACHINE).single()
            walkMillstone(fixture, processing, runtime, workers[0], 100L)
            machine.isGlowing shouldBe true
            verify(atLeast = 40) {
                fixture.port.spawnGuidanceDust(workers[0], any(), any(), any())
            }

            walkMillstone(fixture, processing, runtime, workers[1], 200L)
            runtime.state.processing?.cyclesCompleted shouldBe 2

            processing.cleanup("simulated_restart")
            runtime = fixture.persistAndReload(runtime)
            processing = fixture.processing()
            processing.ensure(runtime)
            runtime.state.processing?.cyclesCompleted shouldBe 2

            repeat(fixture.zone.processing.machineCycles - 2) { index ->
                walkMillstone(fixture, processing, runtime, workers[index % workers.size], 300L + index * 100L)
            }
            runtime.state.processing?.stage shouldBe FarmProcessingStage.PACKING
            processing.ensure(runtime)
            fixture.processingDisplays(FarmProcessingSceneRole.OUTPUT_PALLET).single().isGlowing shouldBe true
            fixture.processingInteraction(processing, runtime, FarmProcessingSceneRole.OUTPUT_INTERACTION).let { interaction ->
                interaction.interactionWidth.shouldBeGreaterThanOrEqual(3.0f)
                interaction.interactionHeight.shouldBeGreaterThanOrEqual(2.2f)
            }
            workers.forEachIndexed { index, worker ->
                worker.teleport(fixture.location(fixture.processingPoint).clone().add(0.0, 0.0, 8.0 + index))
            }

            repeat(fixture.zone.processing.outputPackages) { index ->
                val worker = workers[(index + 1) % workers.size]
                fixture.clickProcessing(processing, runtime, worker, FarmProcessingSceneRole.PRODUCT_INTERACTION, index)
                fixture.clickProcessing(processing, runtime, worker, FarmProcessingSceneRole.OUTPUT_INTERACTION)
                worker.teleport(fixture.location(fixture.processingPoint).clone().add(0.0, 0.0, 8.0 + index))
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

private fun walkMillstone(
    fixture: FarmIncidentScenarioFixture,
    processing: ru.ruscrafting.farms.paper.farm.incident.processing.FarmProcessingIncident,
    runtime: ru.ruscrafting.farms.paper.FarmRuntime,
    player: org.mockbukkit.mockbukkit.entity.PlayerMock,
    firstTick: Long,
) {
    val center = fixture.location(fixture.processingPoint)
    val radius = (fixture.zone.processing.crankInnerRadius + fixture.zone.processing.crankOuterRadius) / 2.0
    repeat(49) { step ->
        val angle = 2.0 * PI * step / 48.0
        player.teleport(center.clone().add(radius * cos(angle), 0.0, radius * sin(angle)))
        processing.update(listOf(runtime), firstTick + step)
    }
}

private fun fireSafetyRouter(
    fixture: FarmIncidentScenarioFixture,
    runtime: ru.ruscrafting.farms.paper.FarmRuntime,
    fire: ru.ruscrafting.farms.paper.farm.incident.fire.FarmBarnFireIncident,
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
    special = mockk<FarmSpecialIncidentController>(relaxed = true),
    processing = mockk(relaxed = true),
    barnFire = fire,
    frost = mockk(relaxed = true),
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

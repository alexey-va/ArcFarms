package ru.ruscrafting.farms.paper.farm.harvest

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
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.PendingFixedFarmCrop
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.farm.FarmEventRouter
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.incident.farmEventRouter
import ru.ruscrafting.farms.paper.farm.incident.drought.FarmDroughtIncident
import ru.ruscrafting.farms.paper.farm.placement.FarmPlacementService
import ru.ruscrafting.farms.paper.farm.recovery.FarmFixedCropRecoveryController
import ru.ruscrafting.farms.paper.farm.recovery.FarmIncidentRecoveryController
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import java.util.concurrent.CompletableFuture

class FarmSmallCropFirstHitMockBukkitIntegrationTest : FunSpec({
    listOf(Material.PUMPKIN, Material.MELON).forEach { crop ->
        test("the first left click consumes a small ${crop.name.lowercase()} exactly once") {
            requiredMockBukkitScenario {
                FarmIncidentScenarioFixture.open().use { fixture ->
                    val runtime = fixture.runtime(
                        FarmShiftState(
                            phase = FarmPhase.HARVESTING,
                            sequence = 91L,
                            orderId = "harvest_festival",
                        ),
                    )
                    val player = fixture.paper.addPlayer("SmallCropHarvester")
                    val block = fixture.world.getBlockAt(10, 65, 10).also { it.type = crop }
                    val pluginSettings = mockk<ArcFarmsConfig>(relaxed = true) {
                        every { particles } returns true
                        every { sounds } returns false
                    }
                    val journalRecords = linkedMapOf<String, PendingFixedFarmCrop>()
                    val prepare = CompletableFuture<Unit>()
                    val journal = mockk<FixedFarmCropJournal>(relaxed = true)
                    every { journal.contains(any()) } answers { journalRecords.containsKey(firstArg()) }
                    every { journal.prepare(any()) } answers {
                        val record = firstArg<PendingFixedFarmCrop>()
                        // The journal reservation is observable while the fruit is
                        // still present; world removal must happen only afterwards.
                        block.type shouldBe crop
                        journalRecords[record.positionKey] = record
                        prepare
                    }

                    val supervisor = RuntimeTaskSupervisor(TestTaskScheduler()).apply {
                        activate()
                    }
                    every { fixture.port.isOperational() } returns true
                    every { fixture.port.lifecycleToken() } returns supervisor.token()
                    every { fixture.port.runSync(any(), any()) } answers {
                        secondArg<() -> Unit>().invoke()
                        true
                    }
                    val ledger = FarmBlockLedger(fixture.plugin)
                    val fixedCrops = FarmFixedCropRecoveryController(
                        journal = journal,
                        ledger = ledger,
                        locale = fixture.locale,
                        debug = ArcFarmsDebug({ false }) {},
                        access = fixture.port,
                        port = fixture.port,
                        state = fixture.port,
                        tasks = fixture.port,
                        runtimes = { listOf(runtime) },
                        clock = { 1_000L },
                    )
                    var transitionCount = 0
                    val harvest = FarmHarvestController(
                        settings = { pluginSettings },
                        locale = fixture.locale,
                        debug = ArcFarmsDebug({ false }) {},
                        access = fixture.port,
                        audience = fixture.port,
                        tasks = fixture.port,
                        ledger = ledger,
                        fixedCrops = fixedCrops,
                        incidentRecovery = mockk<FarmIncidentRecoveryController>(relaxed = true) {
                            every { pending(runtime) } returns false
                        },
                        drought = mockk<FarmDroughtIncident>(relaxed = true),
                        placement = mockk<FarmPlacementService>(relaxed = true),
                        transitions = FarmTransitionSink { target, result, _ ->
                            transitionCount++
                            if (result.accepted) target.state = result.state
                        },
                        shiftStarter = mockk(relaxed = true),
                        taskHints = mockk(relaxed = true),
                        runtimes = { listOf(runtime) },
                        clock = { 1_000L },
                    )
                    val special = mockk<ru.ruscrafting.farms.paper.farm.incident.special.FarmSpecialIncidentController>(relaxed = true)
                    every { special.handleGiantCropHit(any(), any(), any()) } returns false
                    val router: FarmEventRouter = farmEventRouter(
                        fixture = fixture,
                        runtime = runtime,
                        special = special,
                        harvest = harvest,
                    )

                    val first = leftClick(player, block)
                    router.onInteract(first) shouldBe true
                    first.isCancelled shouldBe true
                    block.type shouldBe crop
                    journalRecords.size shouldBe 1
                    runtime.state.progress[crop.name] shouldBe null
                    transitionCount shouldBe 0

                    val repeatedWhilePending = leftClick(player, block)
                    router.onInteract(repeatedWhilePending) shouldBe true
                    repeatedWhilePending.isCancelled shouldBe true
                    verify(exactly = 1) { journal.prepare(any()) }
                    runtime.state.progress[crop.name] shouldBe null
                    transitionCount shouldBe 0

                    prepare.complete(Unit)
                    block.type shouldBe Material.AIR
                    runtime.state.progress[crop.name] shouldBe 1
                    transitionCount shouldBe 1
                    fixture.world.spawnedParticles.map { it.particle }.toSet() shouldBe setOf(
                        org.bukkit.Particle.BLOCK,
                        org.bukkit.Particle.DUST_COLOR_TRANSITION,
                        org.bukkit.Particle.COMPOSTER,
                        org.bukkit.Particle.POOF,
                    )

                    val repeatedAfterRemoval = leftClick(player, block)
                    router.onInteract(repeatedAfterRemoval)
                    runtime.state.progress[crop.name] shouldBe 1
                    transitionCount shouldBe 1
                    verify(exactly = 1) { journal.prepare(any()) }
                }
            }
        }
    }
})

private fun leftClick(
    player: org.bukkit.entity.Player,
    block: org.bukkit.block.Block,
): PlayerInteractEvent = PlayerInteractEvent(
    player,
    Action.LEFT_CLICK_BLOCK,
    ItemStack(Material.AIR),
    block,
    BlockFace.UP,
    EquipmentSlot.HAND,
)

package ru.ruscrafting.farms.paper.farm.field

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.fixtures.FarmAsyncHarness
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.FarmCareController
import ru.ruscrafting.farms.paper.farm.shift.FarmShiftCoordinator
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class FarmSeederCompletionAsyncTest : FunSpec({
    test("the ninety-percent seeder result waits for physical crop repair and survives a retired repair without double credit") {
        FarmAsyncHarness("SeederCompletion").use { h ->
            val world = h.paper.server.addSimpleWorld("field")
            world.getChunkAt(0, 0).load()
            val ledger = FarmBlockLedger(h.plugin)
            val patch = (0 until 100).map { FarmPlotPosition(world.name, it / 10, 64, it % 10) }
            val soils = patch.mapIndexed { index, position -> world.getBlockAt(position.x, position.y, position.z).also {
                it.type = Material.FARMLAND
                it.getRelative(BlockFace.UP).type = if (index % 2 == 0) Material.WHEAT else Material.CARROTS
            } }
            ledger.captureActiveCrops(soils, "farm")
            val expectedCrops = soils.map { it.getRelative(BlockFace.UP).blockData.asString }
            soils.drop(90).forEach { it.type = Material.DIRT; it.getRelative(BlockFace.UP).type = Material.AIR }
            val settings = mockk<FarmZoneSettings>(relaxed = true) {
                every { id } returns "farm"
                every { crops } returns setOf("WHEAT", "CARROTS")
            }
            val worker = h.paper.addPlayer("SeederWorker")
            val runtime = FarmRuntime(settings, CuboidActivityRegion(world, "farm", CuboidBounds(0, 60, 0, 15, 70, 15)),
                emptyMap(), emptyList(), mockk(relaxed = true), FarmShiftState(
                    phase = FarmPhase.CARE, sequence = 4, orderId = "farm_order", careType = FarmCareType.SEEDER, seederStage = FarmSeederStage.PLANTING,
                    preparationPatch = patch, preparationCrop = "WHEAT", preparationRequired = 90,
                    preparationReleased = true, mechanizedPreparation = true, tilledPlots = patch.toSet(),
                    preparationProgress = 100, plantedPlots = patch.take(89).toSet(), plantingProgress = 89,
                    careTargets = listOf(FarmCareTarget(0, FarmCareRole.SEEDER_HORSE, FarmPointPosition(world.name, .5, 65.0, .5))),
                ))
            lateinit var coordinator: FarmShiftCoordinator
            val field = FarmFieldController({ mockk(relaxed = true) }, ArcFarmsDebug({ false }) {},
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), h.tasks, ledger,
                mockk(relaxed = true), mockk(relaxed = true), FarmTransitionSink { current, result, actor ->
                    coordinator.apply(current, result, actor)
                }, { CompletableFuture.completedFuture(Unit) })
            var nextEventStarts = 0
            val care = mockk<FarmCareController>(relaxed = true) {
                every { clear(runtime, "care_resolved") } returns true
                every { initializeAsync(runtime, any(), null) } answers {
                    soils.map { it.getRelative(BlockFace.UP).blockData.asString } shouldBe expectedCrops
                    nextEventStarts++
                    CompletableFuture.completedFuture(true)
                }
            }
            val stats = mockk<WorksiteStatsPort>(relaxed = true)
            coordinator = FarmShiftCoordinator(
                settings = { mockk(relaxed = true) }, locale = mockk(relaxed = true), debug = ArcFarmsDebug({ false }) {},
                port = mockk(relaxed = true), state = mockk(relaxed = true), tasks = h.tasks, stats = stats,
                network = mockk(relaxed = true), carePlans = mockk(relaxed = true), care = care, field = field,
                drought = mockk(relaxed = true), pests = mockk(relaxed = true), birds = mockk(relaxed = true),
                foodDelivery = mockk(relaxed = true), actionIncidents = mockk(relaxed = true), special = mockk(relaxed = true),
                processing = mockk(relaxed = true), barnFire = mockk(relaxed = true), frost = mockk(relaxed = true),
                tornado = mockk(relaxed = true), greenhouse = mockk(relaxed = true), delivery = mockk(relaxed = true),
                scene = mockk(relaxed = true), supplies = mockk(relaxed = true), rewards = mockk(relaxed = true),
                enterprise = mockk(relaxed = true), hud = mockk(relaxed = true), points = mockk(relaxed = true),
            )

            coordinator.apply(runtime, FarmShiftEngine.workSeeder(runtime.state, setOf(patch[89]), worker.uniqueId), worker)

            runtime.state.phase shouldBe FarmPhase.CARE
            runtime.state.plantedPlots shouldBe patch.toSet()
            runtime.state.careTargets.single().role shouldBe FarmCareRole.SEEDER_HORSE
            assertPersistable(runtime.state)
            nextEventStarts shouldBe 0
            field.resumeSeederAfterRecovery(runtime) shouldBe false
            h.gate = CompletableFuture()
            val retired = field.finishAutomaticQuotaAsync(runtime, patch.size)
            field.beforeReload()
            retired.isCompletedExceptionally shouldBe true
            h.gate!!.complete(Unit)
            h.gate = null
            field.resumeSeederAfterRecovery(runtime) shouldBe false

            val retry = field.finishAutomaticQuotaAsync(runtime, patch.size)
            h.until { retry.isDone }
            retry.join() shouldBe 10
            nextEventStarts shouldBe 0
            field.resumeSeederAfterRecovery(runtime) shouldBe true
            field.resumeSeederAfterRecovery(runtime) shouldBe false
            nextEventStarts shouldBe 1
            runtime.state.phase shouldBe FarmPhase.HARVESTING
            runtime.state.careType shouldBe null
            runtime.state.contributors[worker.uniqueId] shouldBe 1
            verify(exactly = 1) { stats.recordContribution(worker.uniqueId, ActivityKind.FARM, 1) }
            assertPersistable(runtime.state)
        }
    }
})

private fun assertPersistable(farm: FarmShiftState) {
    val root = Files.createTempDirectory("arcfarms-seeder-completion-test")
    try {
        val expected = ArcFarmsState(farms = mapOf("farm" to farm))
        ArcFarmsStateRepository(root).use { it.saveBlocking(expected) }
        ArcFarmsStateRepository(root).use { it.load() shouldBe expected }
    } finally {
        root.toFile().deleteRecursively()
    }
}

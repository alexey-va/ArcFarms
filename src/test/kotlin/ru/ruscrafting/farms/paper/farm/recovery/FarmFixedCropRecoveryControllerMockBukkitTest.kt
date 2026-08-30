package ru.ruscrafting.farms.paper.farm.recovery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.PendingFixedFarmCrop
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.CuboidActivityRegion
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.persistence.FixedFarmCropJournal
import java.util.concurrent.CompletableFuture

class FarmFixedCropRecoveryControllerMockBukkitTest : FunSpec({
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

    test("fixed crop remains intact until its recovery journal is durable") {
        val plugin = paper.createSimplePlugin("FixedCropRecoveryTest")
        val player = paper.addPlayer("Worker")
        val block = world.getBlockAt(5, 64, 5).apply { type = Material.MELON }
        val runtime = runtime(world)
        val journalWrite = CompletableFuture<Unit>()
        val journal = mockk<FixedFarmCropJournal>(relaxed = true) {
            every { contains(any()) } returns false
            every { prepare(any()) } returns journalWrite
        }
        val supervisor = RuntimeTaskSupervisor(TestTaskScheduler()).apply(RuntimeTaskSupervisor::activate)
        val token = supervisor.token()
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { isOperational() } returns true
            every { lifecycleToken() } returns token
            every { runSync(token, any()) } answers {
                secondArg<() -> Unit>().invoke()
                true
            }
        }
        var committedCrop: String? = null
        val controller = FarmFixedCropRecoveryController(
            journal = journal,
            ledger = FarmBlockLedger(plugin),
            locale = mockk<ArcFarmsLocale>(relaxed = true),
            debug = ArcFarmsDebug({ false }) {},
            access = port,
            port = port,
            state = port,
            tasks = port,
            runtimes = { listOf(runtime) },
            clock = { 1_000L },
        )

        controller.prepareHarvest(runtime, player, block, 1_000L) { _, _, crop -> committedCrop = crop } shouldBe true

        block.type shouldBe Material.MELON
        committedCrop shouldBe null

        journalWrite.complete(Unit)

        block.type shouldBe Material.AIR
        committedCrop shouldBe Material.MELON.name
    }

    test("failed recovery journal write keeps the crop and clears its pending marker") {
        val plugin = paper.createSimplePlugin("FixedCropRecoveryFailureTest")
        val player = paper.addPlayer("Worker")
        val block = world.getBlockAt(5, 64, 5).apply { type = Material.MELON }
        val runtime = runtime(world)
        val journalWrite = CompletableFuture<Unit>()
        val journal = mockk<FixedFarmCropJournal>(relaxed = true) {
            every { contains(any()) } returns false
            every { prepare(any()) } returns journalWrite
        }
        val supervisor = RuntimeTaskSupervisor(TestTaskScheduler()).apply(RuntimeTaskSupervisor::activate)
        val token = supervisor.token()
        val port = mockk<WorksiteRuntimePort>(relaxed = true) {
            every { isOperational() } returns true
            every { lifecycleToken() } returns token
            every { runSync(token, any()) } answers {
                secondArg<() -> Unit>().invoke()
                true
            }
        }
        val ledger = FarmBlockLedger(plugin)
        var committedCrop: String? = null
        val controller = FarmFixedCropRecoveryController(
            journal = journal,
            ledger = ledger,
            locale = mockk<ArcFarmsLocale>(relaxed = true),
            debug = ArcFarmsDebug({ false }) {},
            access = port,
            port = port,
            state = port,
            tasks = port,
            runtimes = { listOf(runtime) },
            clock = { 1_000L },
        )

        controller.prepareHarvest(runtime, player, block, 1_000L) { _, _, crop -> committedCrop = crop } shouldBe true
        journalWrite.completeExceptionally(IllegalStateException("disk unavailable"))

        block.type shouldBe Material.MELON
        ledger.fixedCropRecord(block)?.restoreAt shouldBe null
        committedCrop shouldBe null
    }
}) {
    companion object {
        private fun runtime(world: WorldMock): FarmRuntime = FarmRuntime(
            settings = mockk<FarmZoneSettings> {
                every { id } returns "communal_farm"
                every { crops } returns setOf(Material.MELON.name)
                every { fixedCropRespawnSeconds } returns 60
            },
            region = CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 31, 128, 31)),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(relaxed = true),
            state = FarmShiftState(phase = FarmPhase.HARVESTING, sequence = 3L),
        )
    }
}

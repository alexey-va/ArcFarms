package ru.ruscrafting.farms.paper.farm.care

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.ruscrafting.farms.config.CuboidBounds
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.domain.*
import ru.ruscrafting.farms.paper.*
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowController
import ru.ruscrafting.farms.paper.fixtures.FarmAsyncHarness
import java.util.concurrent.CompletableFuture

class FarmCareStartResultTest : FunSpec({
    test("care startup keeps placement preparation and superseded state outcomes distinct") {
        FarmAsyncHarness("CareStartResultTest").use { h ->
            val world = h.paper.server.addSimpleWorld("sp11")
            val runtime = FarmRuntime(mockk<FarmZoneSettings> { every { id } returns "communal_farm" },
                CuboidActivityRegion(world, "farm", CuboidBounds(0, 0, 0, 15, 128, 15)),
                emptyMap(), emptyList(), FarmRules(listOf(50), 1, 1_000),
                FarmShiftState(phase = FarmPhase.HARVESTING, sequence = 5, placementSequence = 8))
            val plan = FarmCarePlan(FarmCareType.MOLES,
                listOf(FarmCareTarget(0, FarmCareRole.MOLE_MOUND, FarmPointPosition(world.name, .5, 65.0, .5))), 1)
            val selection = mockk<FarmCarePlanService>()
            val moles = mockk<FarmMoleBurrowController>(relaxed = true)
            val controller = FarmCareController(h.plugin, { mockk(relaxed = true) }, mockk(relaxed = true),
                ArcFarmsDebug({ false }) {}, mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), selection,
                mockk(relaxed = true), moles, FarmTransitionSink { active, result, _ -> active.state = result.state },
                { listOf(runtime) }, { 0L }, tasks = h.tasks)
            val source = runtime.state
            fun <T> await(future: CompletableFuture<T>): T {
                h.until { future.isDone }
                return future.join()
            }

            every { selection.selectAsync(runtime, FarmCareType.MOLES, null) } returns CompletableFuture.completedFuture(null)
            await(controller.initializeDetailedAsync(runtime, null, FarmCareType.MOLES)) shouldBe FarmCareStartResult.NO_PLACEMENT
            await(controller.initializeAsync(runtime, null, FarmCareType.MOLES)) shouldBe false
            verify(exactly = 0) { moles.prepareAsync(any(), any(), any()) }

            every { selection.selectAsync(runtime, FarmCareType.MOLES, null) } returns CompletableFuture.completedFuture(plan)
            every { moles.prepareAsync(runtime, plan.targets, any()) } returns CompletableFuture.completedFuture(false)
            await(controller.initializeDetailedAsync(runtime, null, FarmCareType.MOLES)) shouldBe FarmCareStartResult.PREPARATION_FAILED
            runtime.state shouldBe source

            val planning = CompletableFuture<FarmCarePlan?>()
            every { selection.selectAsync(runtime, FarmCareType.MOLES, null) } returns planning
            val staleSelection = controller.initializeDetailedAsync(runtime, null, FarmCareType.MOLES)
            runtime.state = source.copy(sequence = source.sequence + 1)
            planning.complete(plan)
            await(staleSelection) shouldBe FarmCareStartResult.STATE_CHANGED
            verify(exactly = 1) { moles.prepareAsync(any(), any(), any()) }

            runtime.state = source
            val cancelledPlanning = CompletableFuture<FarmCarePlan?>()
            every { selection.selectAsync(runtime, FarmCareType.MOLES, null) } returns cancelledPlanning
            val changedDuringCapture = controller.initializeDetailedAsync(runtime, null, FarmCareType.MOLES)
            runtime.state = source.copy(sequence = source.sequence + 1)
            cancelledPlanning.completeExceptionally(IllegalStateException("Care planning source changed"))
            await(changedDuringCapture) shouldBe FarmCareStartResult.STATE_CHANGED

            runtime.state = source
            every { selection.selectAsync(runtime, FarmCareType.MOLES, null) } returns
                CompletableFuture.failedFuture(IllegalStateException("Planner failure with an unchanged source"))
            val unexpected = controller.initializeDetailedAsync(runtime, null, FarmCareType.MOLES)
            h.until { unexpected.isDone }
            unexpected.isCompletedExceptionally shouldBe true

            runtime.state = source
            every { selection.selectAsync(runtime, FarmCareType.MOLES, null) } returns CompletableFuture.completedFuture(plan)
            val preparing = CompletableFuture<Boolean>()
            var preparationStarted = false
            every { moles.prepareAsync(runtime, plan.targets, any()) } answers { preparationStarted = true; preparing }
            val stalePreparation = controller.initializeDetailedAsync(runtime, null, FarmCareType.MOLES)
            h.until { preparationStarted }
            runtime.state = source.copy(sequence = source.sequence + 1)
            preparing.complete(true)
            await(stalePreparation) shouldBe FarmCareStartResult.STATE_CHANGED
            verify(exactly = 1) { moles.discardPrepared(match { it.state.sequence == source.sequence }) }
            verify(exactly = 0) { moles.discardPrepared(match { it.state.sequence != source.sequence }) }
            runtime.state.sequence shouldBe source.sequence + 1

            runtime.state = source
            every { moles.prepareAsync(runtime, plan.targets, any()) } returns CompletableFuture.completedFuture(true)
            await(controller.initializeAsync(runtime, null, FarmCareType.MOLES)) shouldBe true
            runtime.state.phase shouldBe FarmPhase.CARE
            runtime.state.careType shouldBe FarmCareType.MOLES
        }
    }
})

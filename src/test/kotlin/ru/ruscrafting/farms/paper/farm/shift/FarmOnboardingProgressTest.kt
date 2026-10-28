package ru.ruscrafting.farms.paper.farm.shift

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.farm.enterprise.FarmEnterpriseCompletion
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.ActivityStatsIndex
import ru.ruscrafting.farms.domain.ArcFarmsState
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.FarmTerminalDelivery
import ru.ruscrafting.farms.domain.PlayerActivityStats
import ru.ruscrafting.farms.persistence.ArcFarmsStateRepository
import ru.ruscrafting.onboarding.api.OptionalOnboardingProgressService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.nio.file.Files

class FarmOnboardingProgressTest : FunSpec({
    test("optional provider absence never changes a completed farm shift") {
        MockBukkitTestRuntime.open().use {
            val tasks = mockk<WorksiteTaskPort>()
            FarmOnboardingProgress.record(setOf(UUID.randomUUID()), "spawn:farm:communal_farm:1", tasks)
            verify(exactly = 0) { tasks.lifecycleToken() }
        }
    }

    test("each actual contributor receives the same completed event and failures retry without replaying gameplay") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("FarmOnboardingTest")
            val service = mockk<OptionalOnboardingProgressService>()
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val eventId = "spawn:farm:communal_farm:7"
            every { service.recordFarmShift(first, eventId) } returns CompletableFuture.completedFuture(null)
            every { service.recordFarmShift(second, eventId) } returnsMany listOf(
                CompletableFuture.failedFuture(IllegalStateException("Redis temporarily unavailable")),
                CompletableFuture.completedFuture(null))
            paper.server.servicesManager.register(OptionalOnboardingProgressService::class.java, service, plugin, ServicePriority.Normal)
            val token = mockk<RuntimeTaskSupervisor.Token>()
            val tasks = mockk<WorksiteTaskPort>()
            val retries = mutableListOf<() -> Unit>()
            every { tasks.lifecycleToken() } returns token
            every { tasks.runLater(token, any(), any()) } answers { retries += thirdArg<() -> Unit>(); true }

            FarmOnboardingProgress.record(setOf(first, second), eventId, tasks)
            retries.size shouldBe 1
            verify(exactly = 1) { service.recordFarmShift(first, eventId) }
            verify(exactly = 1) { service.recordFarmShift(second, eventId) }
            retries.single().invoke()
            verify(exactly = 1) { service.recordFarmShift(first, eventId) }
            verify(exactly = 2) { service.recordFarmShift(second, eventId) }
        }
    }

    test("saved commercial receipt replays after a dropped callback and restart without inferring old aggregate completions") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ARC")
            val player = paper.server.addPlayer()
            val eventId = "spawn:farm:communal_farm:12"
            val index = ActivityStatsIndex(mapOf(player.uniqueId to PlayerActivityStats(completedShifts = mapOf(ActivityKind.FARM to 9))))
            val root = Files.createTempDirectory("farm-intro-recovery-")
            ArcFarmsStateRepository(root).use { repository -> repository.saveBlocking(ArcFarmsState(stats = index.snapshot())) }
            ArcFarmsStateRepository(root).use { repository ->
                repository.load().stats.getValue(player.uniqueId).farmIntroductionEventId shouldBe null
            }
            index.recordFarmIntroduction(setOf(player.uniqueId), eventId)
            // This is the completion commit. Its main-thread callback is deliberately never run.
            ArcFarmsStateRepository(root).use { repository -> repository.saveBlocking(ArcFarmsState(stats = index.snapshot())) }
            val reloaded = ArcFarmsStateRepository(root).use { it.load() }
            val service = mockk<OptionalOnboardingProgressService> {
                every { recordFarmShift(player.uniqueId, eventId) } returns CompletableFuture.completedFuture(null)
            }
            paper.server.servicesManager.register(OptionalOnboardingProgressService::class.java, service, plugin, ServicePriority.Normal)
            val tasks = mockk<WorksiteTaskPort> { every { lifecycleToken() } returns mockk() }
            val receipt = reloaded.stats.getValue(player.uniqueId).farmIntroductionEventId!!
            replayFarmOnboardingCompletions(mapOf(player.uniqueId to receipt), tasks)
            verify(exactly = 1) { service.recordFarmShift(player.uniqueId, eventId) }
            index.recordFarmIntroduction(setOf(player.uniqueId), "spawn:farm:communal_farm:13")
            index.player(player.uniqueId).farmIntroductionEventId shouldBe eventId
        }
    }

    test("admin forced terminal completion never records a commercial introduction receipt") {
        FarmIncidentScenarioFixture.open().use { fixture ->
            val playerId = UUID.randomUUID()
            val index = ActivityStatsIndex()
            val stats = mockk<WorksiteStatsPort>(relaxed = true) {
                every { recordFarmIntroduction(any(), any()) } answers {
                    index.recordFarmIntroduction(firstArg(), secondArg())
                }
            }
            val state = mockk<WorksiteStatePort>(relaxed = true) {
                every { persistAsync() } returns CompletableFuture.completedFuture(Unit)
            }
            val coordinator = FarmShiftCoordinator(
                settings = { fixture.settings }, locale = fixture.locale, debug = ArcFarmsDebug({ false }) {},
                port = mockk(relaxed = true), state = state, tasks = mockk(relaxed = true), stats = stats,
                network = mockk(relaxed = true), carePlans = mockk(relaxed = true), care = mockk(relaxed = true),
                field = mockk(relaxed = true), drought = mockk(relaxed = true), pests = mockk(relaxed = true),
                birds = mockk(relaxed = true), foodDelivery = mockk(relaxed = true), actionIncidents = mockk(relaxed = true),
                special = mockk(relaxed = true), processing = mockk(relaxed = true), barnFire = mockk(relaxed = true),
                frost = mockk(relaxed = true), tornado = mockk(relaxed = true), greenhouse = mockk(relaxed = true),
                delivery = mockk(relaxed = true), scene = mockk(relaxed = true), supplies = mockk(relaxed = true),
                rewards = mockk(relaxed = true), enterprise = mockk(relaxed = true) {
                    every { orderCompleted(any(), any(), any(), any()) } returns FarmEnterpriseCompletion(false)
                }, hud = mockk(relaxed = true), points = mockk(relaxed = true),
            )
            val initial = FarmShiftState(phase = FarmPhase.INCIDENT, sequence = 12,
                incidentType = FarmIncidentType.FOOD_DELIVERY, contributors = mapOf(playerId to 4))
            val runtime = fixture.runtime(initial)
            coordinator.apply(runtime, FarmShiftEngine.completeFoodDeliveryAsAdmin(initial, runtime.rules, 1000L), null)
            index.player(playerId).farmIntroductionEventId shouldBe null
            verify(exactly = 0) { stats.recordFarmIntroduction(any(), any()) }
            runtime.state = initial
            coordinator.apply(runtime, FarmTerminalDelivery.complete(initial, runtime.rules, 1000L, contribution = 1), null)
            index.player(playerId).farmIntroductionEventId shouldBe "${fixture.settings.serverId}:farm:${runtime.settings.id}:12"
            verify(exactly = 1) { stats.recordFarmIntroduction(setOf(playerId), any()) }
        }
    }
})

package ru.ruscrafting.farms.paper.farm.shift

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.ShiftStartPersistenceSettings
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmRules
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockRegistry
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.admin.FarmWorldAdminService
import ru.ruscrafting.farms.paper.farm.care.FarmCarePlanService
import ru.ruscrafting.farms.paper.farm.enterprise.FarmEnterprisePort
import ru.ruscrafting.farms.paper.farm.field.FarmFieldController
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.random.RandomGenerator

class FarmShiftStartServiceTest : FunSpec({
    test("does not apply the field transition before the shift snapshot is durable") {
        val persistence = CompletableFuture<Unit>()
        val fixture = fixture(persistence)

        fixture.service.start(fixture.runtime, fixture.player, 1_000L, fixture.order).shouldBeTrue()

        fixture.runtime.state.phase shouldBe FarmPhase.PREPARATION
        fixture.service.isPending("farm").shouldBeTrue()
        fixture.lifecycle shouldBe listOf("reserve", "persist")
        verify(exactly = 1) { fixture.enterprise.orderStarted("farm", "wheat", 1, 1_000L) }
        verify(exactly = 0) { fixture.transitions.apply(any(), any(), any()) }
        verify(exactly = 0) { fixture.registry.addBeds(any(), any()) }

        persistence.complete(Unit)

        fixture.service.isPending("farm") shouldBe false
        verify(exactly = 1) { fixture.transitions.apply(fixture.runtime, any(), fixture.player) }
        verify(exactly = 1) { fixture.registry.addBeds("farm", any()) }
    }

    test("keeps a staged shift and reservation while an ambiguous persistence outcome retries") {
        val firstAttempt = CompletableFuture<Unit>()
        val retryAttempt = CompletableFuture<Unit>()
        val fixture = fixture(firstAttempt, retryAttempt)

        fixture.service.start(fixture.runtime, fixture.player, 1_000L, fixture.order).shouldBeTrue()
        firstAttempt.completeExceptionally(IllegalStateException("directory fsync failed after move"))

        fixture.runtime.state.phase shouldBe FarmPhase.PREPARATION
        fixture.service.isPending("farm").shouldBeTrue()
        fixture.lifecycle shouldBe listOf("reserve", "persist")
        fixture.retryDelays shouldBe listOf(20L)
        verify(exactly = 0) { fixture.enterprise.orderCancelled(any(), any()) }
        verify(exactly = 0) { fixture.transitions.apply(any(), any(), any()) }
        verify(exactly = 0) { fixture.registry.addBeds(any(), any()) }

        fixture.runNextRetry()
        fixture.lifecycle shouldBe listOf("reserve", "persist", "persist")
        retryAttempt.complete(Unit)

        fixture.service.isPending("farm") shouldBe false
        verify(exactly = 1) { fixture.transitions.apply(fixture.runtime, any(), fixture.player) }
        verify(exactly = 1) { fixture.registry.addBeds("farm", any()) }
    }

    test("durably saves newer runtime state and registers beds without a stale start transition") {
        val stagedAttempt = CompletableFuture<Unit>()
        val currentStateAttempt = CompletableFuture<Unit>()
        val fixture = fixture(stagedAttempt, currentStateAttempt)

        fixture.service.start(fixture.runtime, fixture.player, 1_000L, fixture.order).shouldBeTrue()
        val edited = fixture.runtime.state.copy(preparationRequired = fixture.runtime.state.preparationRequired + 1)
        fixture.runtime.state = edited
        stagedAttempt.complete(Unit)

        fixture.runtime.state shouldBe edited
        fixture.service.isPending("farm").shouldBeTrue()
        fixture.lifecycle shouldBe listOf("reserve", "persist", "persist")
        verify(exactly = 0) { fixture.enterprise.orderCancelled(any(), any()) }
        verify(exactly = 0) { fixture.transitions.apply(any(), any(), any()) }
        verify(exactly = 0) { fixture.registry.addBeds(any(), any()) }

        currentStateAttempt.complete(Unit)

        fixture.service.isPending("farm") shouldBe false
        fixture.runtime.state shouldBe edited
        verify(exactly = 0) { fixture.transitions.apply(any(), any(), any()) }
        verify(exactly = 1) { fixture.registry.addBeds("farm", any()) }
    }

    test("reads the bounded retry policy again after a live configuration change") {
        val firstAttempt = CompletableFuture<Unit>()
        val secondAttempt = CompletableFuture<Unit>()
        val thirdAttempt = CompletableFuture<Unit>()
        var policy = ShiftStartPersistenceSettings(1, 4, 5)
        val fixture = fixture(firstAttempt, secondAttempt, thirdAttempt, retrySettings = { policy })

        fixture.service.start(fixture.runtime, fixture.player, 1_000L, fixture.order).shouldBeTrue()
        firstAttempt.completeExceptionally(IllegalStateException("first"))
        fixture.retryDelays shouldBe listOf(20L)

        policy = ShiftStartPersistenceSettings(5, 5, 5)
        fixture.runNextRetry()
        secondAttempt.completeExceptionally(IllegalStateException("second"))

        fixture.retryDelays shouldBe listOf(20L, 100L)
        fixture.service.isPending("farm").shouldBeTrue()
    }
})

private data class ShiftStartFixture(
    val service: FarmShiftStartService,
    val runtime: FarmRuntime,
    val player: Player,
    val order: FarmOrder,
    val registry: FarmBlockRegistry,
    val enterprise: FarmEnterprisePort,
    val transitions: FarmTransitionSink,
    val lifecycle: List<String>,
    val retryDelays: List<Long>,
    private val retryTasks: MutableList<() -> Unit>,
) {
    fun runNextRetry() = retryTasks.removeAt(0).invoke()
}

private fun fixture(
    vararg persistenceAttempts: CompletableFuture<Unit>,
    retrySettings: () -> ShiftStartPersistenceSettings = { ShiftStartPersistenceSettings(1, 4, 5) },
): ShiftStartFixture {
    val retryTasks = mutableListOf<() -> Unit>()
    val retryDelays = mutableListOf<Long>()
    val port = mockk<WorksiteRuntimePort>(relaxed = true) {
        every { allowInteraction(any(), any()) } returns true
        every { lifecycleToken() } returns mockk<RuntimeTaskSupervisor.Token>()
        every { runSync(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        every { runLater(any<RuntimeTaskSupervisor.Token>(), any(), any()) } answers {
            retryDelays += secondArg<Long>()
            retryTasks += thirdArg<() -> Unit>()
            true
        }
    }
    val orderCycle = mockk<FarmOrderCycleController> { every { isPaused(any()) } returns false }
    val worldAdmin = mockk<FarmWorldAdminService> { every { anyEditing() } returns false }
    val registry = mockk<FarmBlockRegistry>(relaxed = true) { every { isReindexing(any()) } returns false }
    val patch = listOf(FarmPlotPosition("farm", 1, 64, 1), FarmPlotPosition("farm", 2, 64, 1))
    val field = mockk<FarmFieldController> { every { selectPatch(any(), any(), any(), any()) } returns patch }
    val carePlans = mockk<FarmCarePlanService> { every { isSeederSequence(any(), any()) } returns false }
    val lifecycle = mutableListOf<String>()
    val attempts = ArrayDeque(persistenceAttempts.toList())
    val enterprise = mockk<FarmEnterprisePort>(relaxed = true) {
        every { orderStarted(any(), any(), any(), any()) } answers {
            lifecycle += "reserve"
            true
        }
        every { orderCancelled(any(), any()) } answers {
            lifecycle += "cancel"
            true
        }
    }
    val transitions = mockk<FarmTransitionSink>(relaxed = true)
    val settings = mockk<FarmZoneSettings>(relaxed = true) {
        every { id } returns "farm"
        every { preparationPatchSize } returns patch.size
        every { seederPatchSize } returns patch.size
        every { preparationSearchRadius } returns 64
        every { fieldCompletionPercent } returns 90
    }
    val order = FarmOrder("wheat", mapOf("WHEAT" to 100))
    val runtime = FarmRuntime(
        settings = settings,
        region = mockk<ActivityRegion>(),
        orders = mapOf(order.id to order),
        orderList = listOf(order),
        rules = mockk<FarmRules>(),
        state = FarmShiftState(),
    )
    val player = mockk<Player>(relaxed = true) {
        every { uniqueId } returns UUID.fromString("00000000-0000-0000-0000-000000000001")
        every { name } returns "Farmer"
        every { location } returns mockk<Location>()
        every { isOnline } returns true
    }
    val service = FarmShiftStartService(
        debug = ArcFarmsDebug({ false }) {},
        access = port,
        audience = port,
        state = port,
        tasks = port,
        orderCycle = orderCycle,
        worldAdmin = worldAdmin,
        registry = registry,
        field = field,
        carePlans = carePlans,
        enterprise = enterprise,
        transitions = transitions,
        persistAsync = {
            lifecycle += "persist"
            attempts.removeFirstOrNull() ?: error("Unexpected persistence attempt")
        },
        retrySettings = retrySettings,
        random = mockk<RandomGenerator>(relaxed = true),
    )
    return ShiftStartFixture(
        service,
        runtime,
        player,
        order,
        registry,
        enterprise,
        transitions,
        lifecycle,
        retryDelays,
        retryTasks,
    )
}

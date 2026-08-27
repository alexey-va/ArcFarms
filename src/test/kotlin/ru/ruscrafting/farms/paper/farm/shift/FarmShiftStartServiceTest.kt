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
        verify(exactly = 0) { fixture.transitions.apply(any(), any(), any()) }
        verify(exactly = 0) { fixture.registry.addBeds(any(), any()) }

        persistence.complete(Unit)

        fixture.service.isPending("farm") shouldBe false
        verify(exactly = 1) { fixture.transitions.apply(fixture.runtime, any(), fixture.player) }
        verify(exactly = 1) { fixture.registry.addBeds("farm", any()) }
    }

    test("rolls a staged shift back when persistence fails") {
        val persistence = CompletableFuture<Unit>()
        val fixture = fixture(persistence)

        fixture.service.start(fixture.runtime, fixture.player, 1_000L, fixture.order).shouldBeTrue()
        persistence.completeExceptionally(IllegalStateException("disk unavailable"))

        fixture.runtime.state.phase shouldBe FarmPhase.IDLE
        fixture.service.isPending("farm") shouldBe false
        verify(exactly = 0) { fixture.transitions.apply(any(), any(), any()) }
        verify(exactly = 0) { fixture.registry.addBeds(any(), any()) }
    }
})

private data class ShiftStartFixture(
    val service: FarmShiftStartService,
    val runtime: FarmRuntime,
    val player: Player,
    val order: FarmOrder,
    val registry: FarmBlockRegistry,
    val transitions: FarmTransitionSink,
)

private fun fixture(persistence: CompletableFuture<Unit>): ShiftStartFixture {
    val port = mockk<WorksiteRuntimePort>(relaxed = true) {
        every { allowInteraction(any(), any()) } returns true
        every { lifecycleToken() } returns mockk<RuntimeTaskSupervisor.Token>()
        every { runSync(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
    }
    val orderCycle = mockk<FarmOrderCycleController> { every { isPaused(any()) } returns false }
    val worldAdmin = mockk<FarmWorldAdminService> { every { anyEditing() } returns false }
    val registry = mockk<FarmBlockRegistry>(relaxed = true) { every { isReindexing(any()) } returns false }
    val patch = listOf(FarmPlotPosition("farm", 1, 64, 1), FarmPlotPosition("farm", 2, 64, 1))
    val field = mockk<FarmFieldController> { every { selectPatch(any(), any(), any(), any()) } returns patch }
    val carePlans = mockk<FarmCarePlanService> { every { isSeederSequence(any(), any()) } returns false }
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
        port = port,
        orderCycle = orderCycle,
        worldAdmin = worldAdmin,
        registry = registry,
        field = field,
        carePlans = carePlans,
        transitions = transitions,
        persistAsync = { persistence },
        random = mockk<RandomGenerator>(relaxed = true),
    )
    return ShiftStartFixture(service, runtime, player, order, registry, transitions)
}

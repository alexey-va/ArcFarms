package ru.ruscrafting.farms.paper.farm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameRule
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import ru.ruscrafting.farms.domain.FarmIncidentType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.farm.incident.route.FarmFoodDeliveryIncident
import ru.ruscrafting.farms.paper.farm.shift.FarmShiftStartService
import ru.ruscrafting.farms.paper.fixtures.FarmIncidentScenarioFixture
import ru.ruscrafting.farms.paper.fixtures.requiredMockBukkitScenario

class FarmModuleNightRecoveryTest : FunSpec({
    listOf(false, true).forEach { pendingStart ->
        val guard = if (pendingStart) "pending shift start" else "idle farm without an audience"
        test("tick retries a failed delivery clock restore before $guard") {
            requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
                fixture.world.fullTime = 54_000L
                fixture.world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true)
                val runtime = fixture.runtime(FarmShiftState(
                    phase = FarmPhase.INCIDENT, sequence = 903, orderId = "bakery_supply",
                    incidentType = FarmIncidentType.FOOD_DELIVERY,
                ))
                val route = listOf(
                    FarmPointPosition(fixture.world.name, 8.5, 65.0, 32.5, -90f, 0f),
                    FarmPointPosition(fixture.world.name, 12.5, 65.0, 32.5, -90f, 0f),
                )
                val delivery = fixture.foodDelivery(runtime, route)
                delivery.ensure(runtime, 1_000L)
                val key = NamespacedKey(fixture.plugin, "farm_food_delivery_night_v1")
                fixture.world.persistentDataContainer.set(key, PersistentDataType.LONG_ARRAY, longArrayOf(6_000L))
                val idle = FarmShiftState(sequence = 903)
                runtime.state = idle
                shouldThrow<IllegalStateException> { delivery.ensure(runtime, 1_001L) }
                fixture.world.entities.filter(delivery::owns).shouldHaveSize(0)
                fixture.world.time shouldBe runtime.settings.routeDelivery.playerTime
                fixture.world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe false

                val module = fixture.nightRecoveryModule(runtime, delivery, pendingStart)
                every { fixture.port.players(any()) } returns emptyList()
                fixture.world.persistentDataContainer.set(key, PersistentDataType.LONG_ARRAY, longArrayOf(54_000L, 1L))
                module.tick(1_002L)

                fixture.world.fullTime shouldBe 54_000L
                fixture.world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe true
                fixture.world.persistentDataContainer.has(key, PersistentDataType.LONG_ARRAY) shouldBe false
                fixture.world.entities.filter(delivery::owns).shouldHaveSize(0)
                runtime.state shouldBe idle
            } }
        }
    }

    test("the pre-guard retry does not initialize or acquire night for an active pending incident") {
        requiredMockBukkitScenario { FarmIncidentScenarioFixture.open().use { fixture ->
            fixture.world.fullTime = 54_000L
            fixture.world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true)
            val runtime = fixture.runtime(FarmShiftState(
                phase = FarmPhase.INCIDENT, sequence = 904, orderId = "bakery_supply",
                incidentType = FarmIncidentType.FOOD_DELIVERY,
            ))
            val delivery = fixture.foodDelivery(runtime, listOf(
                FarmPointPosition(fixture.world.name, 8.5, 65.0, 32.5, -90f, 0f),
                FarmPointPosition(fixture.world.name, 12.5, 65.0, 32.5, -90f, 0f),
            ))
            fixture.nightRecoveryModule(runtime, delivery, pendingStart = true).tick(1_000L)

            fixture.world.fullTime shouldBe 54_000L
            fixture.world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) shouldBe true
            fixture.world.persistentDataContainer.has(
                NamespacedKey(fixture.plugin, "farm_food_delivery_night_v1"), PersistentDataType.LONG_ARRAY,
            ) shouldBe false
            fixture.world.entities.filter(delivery::owns).shouldHaveSize(0)
            runtime.state.specialIncident shouldBe null
            fixture.transitions.shouldHaveSize(0)
        } }
    }
})

private fun FarmIncidentScenarioFixture.nightRecoveryModule(
    runtime: FarmRuntime,
    foodDelivery: FarmFoodDeliveryIncident,
    pendingStart: Boolean,
): FarmModule {
    every { port.guarded(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
    val shiftStart = mockk<FarmShiftStartService>(relaxed = true)
    every { shiftStart.isPending(runtime.settings.id) } returns pendingStart
    return FarmModule(
        settings = { settings },
        debug = ArcFarmsDebug({ false }) {},
        access = port,
        audience = port,
        state = port,
        tasks = port,
        registry = FarmRuntimeRegistry().also { it.replace(listOf(runtime)) },
        regionGateway = mockk(relaxed = true),
        blockRegistry = mockk(relaxed = true),
        orderCycle = mockk(relaxed = true),
        worldAdmin = mockk(relaxed = true),
        shiftStart = shiftStart,
        transitions = mockk(relaxed = true),
        field = mockk(relaxed = true),
        fixedCrops = mockk(relaxed = true),
        incidentRecovery = mockk(relaxed = true),
        carePlans = mockk(relaxed = true),
        care = mockk(relaxed = true),
        moles = mockk(relaxed = true),
        drought = mockk(relaxed = true),
        pests = mockk(relaxed = true),
        birds = mockk(relaxed = true),
        foodDelivery = foodDelivery,
        actionIncidents = mockk(relaxed = true),
        perks = mockk(relaxed = true),
        special = mockk(relaxed = true),
        processing = mockk(relaxed = true),
        barnFire = mockk(relaxed = true),
        frost = mockk(relaxed = true),
        tornado = mockk(relaxed = true),
        greenhouse = mockk(relaxed = true),
        delivery = mockk(relaxed = true),
        scene = mockk(relaxed = true),
        supplies = mockk(relaxed = true),
        points = mockk(relaxed = true),
        pointService = mockk(relaxed = true),
        placement = mockk(relaxed = true),
        hud = mockk(relaxed = true),
        guidance = mockk(relaxed = true),
        events = mockk(relaxed = true),
    )
}

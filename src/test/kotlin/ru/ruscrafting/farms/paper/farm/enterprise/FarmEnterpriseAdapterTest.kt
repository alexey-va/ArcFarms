package ru.ruscrafting.farms.paper.farm.enterprise

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.WorksiteEnterpriseMode
import ru.ruscrafting.farms.config.WorksiteEnterpriseSettings
import ru.ruscrafting.farms.config.WorksiteBusinessWeekSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.enterprise.ActiveWorksiteEnterpriseOrder
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import java.util.UUID
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

class FarmEnterpriseAdapterTest : FunSpec({
    val playerId = UUID(0, 9)

    test("farm adapter translates start and eligible completion into the shared kernel") {
        val adapter = adapter(settings())

        adapter.orderStarted("communal_farm", "bakery_supply", 12, 1_800_000_000_000) shouldBe true
        adapter.orderCompleted("communal_farm", 12, mapOf(playerId to 4), commercialEligible = true) shouldBe true

        adapter.snapshot().weeks.values.single().apply {
            completedOrders shouldBe 1
            grossRevenueCents shouldBe 1_000_000L
            uniqueContributors shouldBe setOf(playerId)
        }
    }

    test("farm adapter excludes an administrative completion from company revenue") {
        val adapter = adapter(settings())
        adapter.orderStarted("communal_farm", "bakery_supply", 13, 1_800_000_000_000)

        adapter.orderCompleted("communal_farm", 13, mapOf(playerId to 40), commercialEligible = false) shouldBe true

        adapter.snapshot().weeks.values.single().completedOrders shouldBe 0
        adapter.snapshot().excludedCompletionsByCompany.getValue("farm:communal_farm") shouldBe 1L
    }

    test("farm adapter never creates a commercial reservation after an order was already shown") {
        val adapter = adapter(settings())

        adapter.reconcileOrders(
            listOf(
                ActiveWorksiteEnterpriseOrder(
                    worksiteId = "communal_farm",
                    orderId = "market_crates",
                    sequence = 21,
                    grossTariffCents = 1_200_000,
                    reservedAt = 1_800_000_000_000,
                    businessWeekStartEpochDay = 20_695,
                ),
            ),
        ) shouldBe false

        adapter.snapshot().reservations shouldBe emptyMap()
    }

    test("OFF mode never creates a reservation") {
        val adapter = adapter(settings().copy(mode = WorksiteEnterpriseMode.OFF))

        adapter.orderStarted("communal_farm", "bakery_supply", 1, 1_800_000_000_000) shouldBe false
        adapter.snapshot().reservations shouldBe emptyMap()
    }

    test("an accepted order is settled after mode changes to OFF") {
        var configured = settings()
        val adapter = FarmEnterpriseAdapter(
            settings = { configured },
            debug = ArcFarmsDebug({ false }) {},
            clock = { 1_800_000_000_000 },
        )
        adapter.orderStarted("communal_farm", "bakery_supply", 2, 1_800_000_000_000) shouldBe true
        configured = configured.copy(mode = WorksiteEnterpriseMode.OFF)

        adapter.orderCompleted("communal_farm", 2, mapOf(playerId to 1), commercialEligible = true) shouldBe true

        adapter.snapshot().weeks.values.single().completedOrders shouldBe 1
    }

    test("cancellation releases an accepted order after enterprise configuration changes") {
        var configured = settings()
        val adapter = FarmEnterpriseAdapter(
            settings = { configured },
            debug = ArcFarmsDebug({ false }) {},
            clock = { 1_800_000_000_000 },
        )
        adapter.orderStarted("communal_farm", "bakery_supply", 3, 1_800_000_000_000) shouldBe true
        configured = configured.copy(mode = WorksiteEnterpriseMode.OFF, worksiteId = "replacement_farm")

        adapter.orderCancelled("communal_farm", 3) shouldBe true
        adapter.orderCancelled("communal_farm", 3) shouldBe false

        adapter.snapshot().reservations shouldBe emptyMap()
        adapter.snapshot().releasedReservationsByCompany.getValue("farm:communal_farm") shouldBe 1L
    }

    test("reconciliation preserves an active reservation after configured worksite changes") {
        var configured = settings()
        val adapter = FarmEnterpriseAdapter(
            settings = { configured },
            debug = ArcFarmsDebug({ false }) {},
            clock = { 1_800_000_000_000 },
        )
        adapter.orderStarted("communal_farm", "bakery_supply", 4, 1_800_000_000_000) shouldBe true
        configured = configured.copy(worksiteId = "replacement_farm")
        val runtime = FarmRuntime(
            settings = mockk<FarmZoneSettings> { every { id } returns "communal_farm" },
            region = mockk(),
            orders = emptyMap(),
            orderList = emptyList(),
            rules = mockk(),
            state = FarmShiftState(
                phase = FarmPhase.HARVESTING,
                orderId = "bakery_supply",
                sequence = 4,
                startedAt = 1_800_000_000_000,
            ),
        )

        adapter.reconcileRuntimes(listOf(runtime)) shouldBe false

        adapter.snapshot().reservations.values.single().sequence shouldBe 4L
        adapter.snapshot().releasedReservationsByCompany shouldBe emptyMap()
    }
})

private fun adapter(settings: WorksiteEnterpriseSettings): FarmEnterpriseAdapter = FarmEnterpriseAdapter(
    settings = { settings },
    debug = ArcFarmsDebug({ false }) {},
    clock = { 1_800_000_000_000 },
)

private fun settings(): WorksiteEnterpriseSettings = WorksiteEnterpriseSettings(
    activity = ActivityKind.FARM,
    mode = WorksiteEnterpriseMode.SHADOW,
    companyId = "communal_farm",
    worksiteId = "communal_farm",
    licenseGrossEnvelopeCents = 200_000_000,
    defaultGrossTariffCents = 1_000_000,
    orderGrossTariffsCents = mapOf("market_crates" to 1_200_000),
    operatingCostPercent = 20,
    workerBonusPercent = 30,
    dividendPercent = 50,
    weeklyUpkeepCents = 2_500_000,
    retainedReportWeeks = 16,
    businessWeek = WorksiteBusinessWeekSettings(
        zoneId = ZoneId.of("Europe/Moscow"),
        startDay = DayOfWeek.SUNDAY,
        startTime = LocalTime.of(20, 0),
    ),
)

package ru.ruscrafting.farms.paper.enterprise

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.OfflinePlayer
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.WorksiteEnterpriseMode
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmContractRarity
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterprisePlan
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmEconomyGateway
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.io.path.writeText

class WorksiteEnterpriseEngagementIntegrationTest : FunSpec({
    test("first tranche is bounded, ordinary shifts still count, and next week carries unused capacity") {
        val fixture = EngagementFixture()
        val service = fixture.service
        service.playerView(OWNER)!!.availableThisWeekCents shouldBe 16_666_666
        (1L..16L).forEach { sequence ->
            service.farm.orderStarted(COMPANY, "bakery_supply", sequence, fixture.now)
            service.farm.orderCompleted(COMPANY, sequence, mapOf(OWNER to 3), true)
        }
        service.snapshot().settledGrossByCompany.getValue("farm:$COMPANY") shouldBe 16_000_000
        service.farm.orderStarted(COMPANY, "bakery_supply", 17, fixture.now)
        service.snapshot().reservations shouldBe emptyMap()
        service.farm.orderCompleted(COMPANY, 17, mapOf(OWNER to 3), true) shouldBe true
        service.snapshot().settledGrossByCompany.getValue("farm:$COMPANY") shouldBe 16_000_000
        service.playerView(OWNER)!!.completedOrders shouldBe 17
        service.playerView(OWNER)!!.projectStage shouldBe 1
        fixture.now += WEEK
        service.tick()
        service.playerView(OWNER)!!.availableThisWeekCents shouldBe 17_333_333
        service.farm.orderStarted(COMPANY, "bakery_supply", 18, fixture.now)
        service.snapshot().reservations.size shouldBe 1
    }

    test("admin and duplicate completion cannot advance projects, history survives reload") {
        val fixture = EngagementFixture()
        val service = fixture.service
        service.farm.orderCompleted(COMPANY, 1, mapOf(OWNER to 100), false)
        service.playerView(OWNER)!!.completedOrders shouldBe 0
        service.farm.orderCompleted(COMPANY, 2, mapOf(OWNER to 100), true)
        val snapshot = service.snapshot()
        service.replace(snapshot)
        service.farm.orderCompleted(COMPANY, 2, mapOf(OWNER to 100), true)
        service.playerView(OWNER)!!.completedOrders shouldBe 1
        service.playerView(OWNER)!!.contribution shouldBe 100
        service.farm.orderCompleted(COMPANY, 3, emptyMap(), true)
        service.playerView(OWNER)!!.completedOrders shouldBe 1
    }

    test("OFF without a company has no participation or worker credits") {
        val fixture = EngagementFixture(funded = false, mode = WorksiteEnterpriseMode.OFF)
        fixture.service.farm.orderStarted(COMPANY, "bakery_supply", 1, fixture.now) shouldBe false
        fixture.service.farm.orderCompleted(COMPANY, 1, mapOf(OWNER to 10), true) shouldBe false
        fixture.service.playerView(OWNER) shouldBe null
    }

    test("confirmed shareholder vote changes next week's complete contract pool only after durable write") {
        val fixture = EngagementFixture()
        val service = fixture.service
        val week = service.playerView(OWNER)!!.currentWeekStartEpochDay
        val orders = listOf(FarmOrder("small", mapOf("WHEAT" to 10)),
            FarmOrder("large", mapOf("WHEAT" to 100), rarity = FarmContractRarity.RARE))
        fixture.writeBarrier = CompletableFuture()
        val results = mutableListOf<Boolean>()
        service.vote(OWNER, WorksiteEnterprisePlan.CHALLENGE, week + 7, results::add)
        results shouldBe emptyList()
        service.farm.orderPool(COMPANY, orders) shouldBe orders
        fixture.now += WEEK
        service.tick()
        service.farm.orderPool(COMPANY, orders) shouldBe orders
        fixture.writeBarrier!!.complete(Unit)
        fixture.writeBarrier = null
        service.tick()
        results shouldBe listOf(true)
        service.farm.orderPool(COMPANY, orders) shouldBe listOf(orders.last())
    }

    test("failed ballot write suspends application and stale or unqualified votes are rejected") {
        val fixture = EngagementFixture()
        val service = fixture.service
        val week = service.playerView(OWNER)!!.currentWeekStartEpochDay
        val results = mutableListOf<Boolean>()
        service.vote(UUID(0, 99), WorksiteEnterprisePlan.STEADY, week + 7, results::add)
        service.vote(OWNER, WorksiteEnterprisePlan.STEADY, week, results::add)
        results shouldBe listOf(false, false)
        fixture.writeBarrier = CompletableFuture()
        service.vote(OWNER, WorksiteEnterprisePlan.CHALLENGE, week + 7, results::add)
        fixture.writeBarrier!!.completeExceptionally(IllegalStateException("disk unavailable"))
        fixture.now += WEEK
        service.tick()
        service.participationView(OWNER)!!.targetPlan shouldBe WorksiteEnterprisePlan.TEAM
        service.playerView(OWNER)!!.canVote shouldBe false
        results shouldBe listOf(false, false, false)
    }
})

private class EngagementFixture(funded: Boolean = true, mode: WorksiteEnterpriseMode = WorksiteEnterpriseMode.LIVE) {
    var now = 1_800_000_000_000L
    var writeBarrier: CompletableFuture<Unit>? = null
    private val config = engagementConfig(mode)
    val service = WorksiteEnterpriseService({ config }, ArcFarmsDebug({ false }) {}, { now },
        economy = object : FarmEconomyGateway {
            override val available = true
            override fun deposit(player: OfflinePlayer, amount: Double) = true
            override fun withdraw(player: OfflinePlayer, amount: Double) = true
        }, persist = { writeBarrier ?: CompletableFuture.completedFuture(Unit) })
    init {
        service.replace(null)
        service.reconcileFarms(emptyList())
        if (funded) repeat(5) { index ->
            val id = UUID(0, index.toLong() + 1)
            service.buyShares(mockk { every { uniqueId } returns id }, 20) {}
        }
    }
}

private fun engagementConfig(mode: WorksiteEnterpriseMode): ArcFarmsConfig {
    val root = Files.createTempDirectory("arcfarms-enterprise-engagement-")
    val source = requireNotNull(WorksiteEnterpriseEngagementIntegrationTest::class.java.classLoader.getResourceAsStream("config.yml"))
        .bufferedReader().use { it.readText() }
    root.resolve("config.yml").writeText(source.replaceFirst("mode: \"OFF\"", "mode: ${mode.name}"))
    return ArcFarmsConfig.inspect(root)
}
private const val COMPANY = "communal_farm"
private const val WEEK = 7 * 86_400_000L
private val OWNER = UUID(0, 1)

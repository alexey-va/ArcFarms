package ru.ruscrafting.farms.paper.enterprise

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.OfflinePlayer
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseSnapshot
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseTerms
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseWeek
import ru.ruscrafting.farms.domain.enterprise.WorksiteEnterpriseMoneyOperationState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmEconomyGateway
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.io.path.writeText

class WorksiteEnterpriseServiceIntegrationTest : FunSpec({
    test("a purchase is withdrawn only after its durable intent and completes after its final write") {
        val settings = liveConfig()
        val economy = RecordingEconomy()
        val availableWrites = ArrayDeque<CompletableFuture<Unit>>()
        val submittedWrites = mutableListOf<CompletableFuture<Unit>>()
        val dispatches = ArrayDeque<() -> Unit>()
        val persistedStates = mutableListOf<WorksiteEnterpriseMoneyOperationState>()
        lateinit var service: WorksiteEnterpriseService
        service = WorksiteEnterpriseService(
            settings = { settings },
            debug = ArcFarmsDebug({ false }) {},
            clock = { NOW },
            economy = economy,
            persist = {
                persistedStates += service.snapshot().financing!!.operations.values.single().state
                availableWrites.removeFirst().also(submittedWrites::add)
            },
            tasks = QueuedEnterpriseMoneyTasks(dispatches),
        )
        service.replace(null)
        service.reconcileFarms(emptyList())
        availableWrites += CompletableFuture()
        availableWrites += CompletableFuture()
        val completions = mutableListOf<EnterpriseInvestmentActionResult>()

        service.buyShares(player(), 5, completions::add) shouldBe EnterpriseInvestmentActionResult.STARTED
        persistedStates shouldContainExactly listOf(WorksiteEnterpriseMoneyOperationState.PREPARED)
        economy.withdrawals shouldBe 0

        submittedWrites[0].complete(Unit)
        dispatches.removeFirst().invoke()
        economy.withdrawals shouldBe 1
        persistedStates shouldContainExactly listOf(
            WorksiteEnterpriseMoneyOperationState.PREPARED,
            WorksiteEnterpriseMoneyOperationState.APPLIED,
        )
        completions shouldBe emptyList()

        submittedWrites[1].complete(Unit)
        dispatches.removeFirst().invoke()
        completions shouldContainExactly listOf(EnterpriseInvestmentActionResult.SUCCESS)
        service.ownershipView(ActivityKind.FARM, PLAYER_ID)?.ownedShares shouldBe 5
    }

    test("opening the first live funding round cannot monetize prior shadow observations") {
        val settings = liveConfig()
        val shadowWeek = WorksiteEnterpriseWeek(
            activity = ActivityKind.FARM,
            companyId = "communal_farm",
            weekStartEpochDay = 100,
            completedOrders = 1,
            grossRevenueCents = 1_000_000,
            operatingBurnCents = 200_000,
            workerBonusCents = 240_000,
            retainedProfitCents = 560_000,
            uniqueContributors = setOf(PLAYER_ID),
            projectedWorkerCreditsCents = mapOf(PLAYER_ID to 240_000),
            terms = WorksiteEnterpriseTerms(20, 30, 50, 100_000),
        )
        val service = WorksiteEnterpriseService(
            settings = { settings },
            debug = ArcFarmsDebug({ false }) {},
            clock = { NOW },
        )
        service.replace(
            WorksiteEnterpriseSnapshot(
                settledGrossByCompany = mapOf("farm:communal_farm" to 1_000_000),
                weeks = mapOf("farm:communal_farm:100" to shadowWeek),
            ),
        )

        service.reconcileFarms(emptyList()) shouldBe true

        service.snapshot().weeks shouldBe emptyMap()
        service.snapshot().settledGrossByCompany shouldBe emptyMap()
        service.ownershipView(ActivityKind.FARM, PLAYER_ID)?.phase?.name shouldBe "FUNDING"
    }

    test("a restart quarantines a prepared purchase and never calls the economy again") {
        val settings = liveConfig()
        val firstEconomy = RecordingEconomy()
        val unresolvedWrite = CompletableFuture<Unit>()
        val first = WorksiteEnterpriseService(
            settings = { settings },
            debug = ArcFarmsDebug({ false }) {},
            clock = { NOW },
            economy = firstEconomy,
            persist = { unresolvedWrite },
            tasks = QueuedEnterpriseMoneyTasks(ArrayDeque()),
        )
        first.replace(null)
        first.reconcileFarms(emptyList())
        first.buyShares(player(), 5) {}
        val crashSnapshot = first.snapshot()

        val recoveredEconomy = RecordingEconomy()
        val recovered = WorksiteEnterpriseService(
            settings = { settings },
            debug = ArcFarmsDebug({ false }) {},
            clock = { NOW },
            economy = recoveredEconomy,
        )
        recovered.replace(crashSnapshot) shouldBe true

        recovered.ownershipView(ActivityKind.FARM, PLAYER_ID)?.pendingManualReviewCount shouldBe 1
        recovered.buyShares(player(), 1) {} shouldBe EnterpriseInvestmentActionResult.ALREADY_PENDING
        recoveredEconomy.withdrawals shouldBe 0
        recovered.snapshot().financing!!.operations.values.single().state shouldBe
            WorksiteEnterpriseMoneyOperationState.MANUAL_REVIEW
    }

    test("a stale reload token drops the provider continuation and recovery quarantines the intent") {
        val settings = liveConfig()
        val economy = RecordingEconomy()
        val initialWrite = CompletableFuture<Unit>()
        val tasks = GenerationEnterpriseMoneyTasks()
        val service = WorksiteEnterpriseService(
            settings = { settings },
            debug = ArcFarmsDebug({ false }) {},
            clock = { NOW },
            economy = economy,
            persist = { initialWrite },
            tasks = tasks,
        )
        service.replace(null)
        service.reconcileFarms(emptyList())

        service.buyShares(player(), 1) {} shouldBe EnterpriseInvestmentActionResult.STARTED
        tasks.invalidate()
        initialWrite.complete(Unit)

        economy.withdrawals shouldBe 0
        service.snapshot().financing!!.operations.values.single().state shouldBe
            WorksiteEnterpriseMoneyOperationState.PREPARED
        service.replace(service.snapshot()) shouldBe true
        service.snapshot().financing!!.operations.values.single().state shouldBe
            WorksiteEnterpriseMoneyOperationState.MANUAL_REVIEW
    }

    test("a synchronous initial persistence failure rejects the intent before touching Vault") {
        val settings = liveConfig()
        val economy = RecordingEconomy()
        var writes = 0
        val completions = mutableListOf<EnterpriseInvestmentActionResult>()
        val service = WorksiteEnterpriseService(
            settings = { settings },
            debug = ArcFarmsDebug({ false }) {},
            clock = { NOW },
            economy = economy,
            persist = {
                writes++
                if (writes == 1) error("disk unavailable") else CompletableFuture.completedFuture(Unit)
            },
        )
        service.replace(null)
        service.reconcileFarms(emptyList())

        service.buyShares(player(), 1, completions::add) shouldBe EnterpriseInvestmentActionResult.STARTED

        economy.withdrawals shouldBe 0
        completions shouldContainExactly listOf(EnterpriseInvestmentActionResult.STATE_ERROR)
        service.snapshot().financing!!.operations.values.single().state shouldBe
            WorksiteEnterpriseMoneyOperationState.REJECTED
    }

    test("a synchronous final persistence failure never retries Vault and reports manual review") {
        val settings = liveConfig()
        val economy = RecordingEconomy()
        var writes = 0
        val completions = mutableListOf<EnterpriseInvestmentActionResult>()
        val service = WorksiteEnterpriseService(
            settings = { settings },
            debug = ArcFarmsDebug({ false }) {},
            clock = { NOW },
            economy = economy,
            persist = {
                writes++
                if (writes == 1) CompletableFuture.completedFuture(Unit) else error("disk unavailable")
            },
        )
        service.replace(null)
        service.reconcileFarms(emptyList())

        service.buyShares(player(), 1, completions::add) shouldBe EnterpriseInvestmentActionResult.STARTED

        economy.withdrawals shouldBe 1
        completions shouldContainExactly listOf(EnterpriseInvestmentActionResult.MANUAL_REVIEW)
        service.snapshot().financing!!.operations.values.single().state shouldBe
            WorksiteEnterpriseMoneyOperationState.APPLIED
    }
})

private class RecordingEconomy : FarmEconomyGateway {
    override val available = true
    var withdrawals = 0

    override fun deposit(player: OfflinePlayer, amount: Double): Boolean = true

    override fun withdraw(player: OfflinePlayer, amount: Double): Boolean {
        withdrawals++
        return true
    }
}

private class QueuedEnterpriseMoneyTasks(
    private val queue: ArrayDeque<() -> Unit>,
) : EnterpriseMoneyTasks {
    private object Token : EnterpriseMoneyTaskToken

    override fun token(): EnterpriseMoneyTaskToken = Token

    override fun run(token: EnterpriseMoneyTaskToken, action: () -> Unit) {
        queue += action
    }
}

private class GenerationEnterpriseMoneyTasks : EnterpriseMoneyTasks {
    private data class Token(val generation: Int) : EnterpriseMoneyTaskToken
    private var generation = 0

    override fun token(): EnterpriseMoneyTaskToken = Token(generation)

    override fun run(token: EnterpriseMoneyTaskToken, action: () -> Unit) {
        if ((token as Token).generation == generation) action()
    }

    fun invalidate() {
        generation++
    }
}

private fun player(): OfflinePlayer = mockk {
    every { uniqueId } returns PLAYER_ID
}

private fun liveConfig(): ArcFarmsConfig {
    val root = Files.createTempDirectory("arcfarms-enterprise-live-")
    val resource = requireNotNull(WorksiteEnterpriseServiceIntegrationTest::class.java.classLoader.getResource("config.yml"))
    val config = root.resolve("config.yml")
    config.writeText(Files.readString(java.nio.file.Path.of(resource.toURI())).replaceFirst("mode: OFF", "mode: LIVE"))
    return ArcFarmsConfig.inspect(root)
}

private const val NOW = 1_800_000_000_000L
private val PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011")

package ru.ruscrafting.farms.paper.farm.reward

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.config.FarmZoneSettings
import ru.ruscrafting.farms.config.FarmMoneyRewardSettings
import ru.ruscrafting.farms.config.defaultWorksiteRewards
import ru.ruscrafting.farms.domain.FarmRewardItem
import ru.ruscrafting.farms.domain.PendingFarmReward
import ru.ruscrafting.farms.domain.FarmRewardMoneyDelivery
import ru.ruscrafting.farms.domain.FarmRewardMoneyState
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmEconomyGateway
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import java.util.concurrent.CompletableFuture

class FarmRewardServiceMockBukkitTest : FunSpec({
    lateinit var player: PlayerMock
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: Plugin

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.createSimplePlugin("FarmRewardTest")
        player = paper.addPlayer("Worker")
    }

    afterEach {
        paper.close()
    }

    test("failed durable claim does not deliver or lose the pending reward") {
        val fixture = rewardFixture(player, plugin) {
            CompletableFuture.failedFuture(IllegalStateException("disk unavailable"))
        }

        fixture.service.deliverPending(player)
        fixture.flushCallbacks()
        fixture.service.prepareForLifecycleBoundary()

        player.totalExperience shouldBe 0
        player.inventory.contains(Material.BREAD) shouldBe false
        fixture.service.snapshot().pending shouldHaveSize 1
        fixture.service.snapshot().claimed shouldBe emptyMap()
    }

    test("successful durable claim delivers exactly once") {
        var saves = 0
        val fixture = rewardFixture(player, plugin) {
            saves++
            CompletableFuture.completedFuture(Unit)
        }

        fixture.service.deliverPending(player)
        fixture.flushCallbacks()
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()

        saves shouldBe 1
        player.totalExperience shouldBe 12
        player.inventory.all(Material.BREAD).values.sumOf { it.amount } shouldBe 3
        fixture.service.snapshot().pending shouldBe emptyList()
        fixture.service.snapshot().claimed["communal_farm:${player.uniqueId}"] shouldBe 7L
    }

    test("reward side effects wait for asynchronous durability confirmation") {
        val gate = CompletableFuture<Unit>()
        val fixture = rewardFixture(player, plugin) { gate }

        fixture.service.deliverPending(player)
        fixture.flushCallbacks()

        player.totalExperience shouldBe 0
        player.inventory.contains(Material.BREAD) shouldBe false

        gate.complete(Unit)
        fixture.flushCallbacks()

        player.totalExperience shouldBe 12
        player.inventory.all(Material.BREAD).values.sumOf { it.amount } shouldBe 3
    }

    test("one deposit includes the frozen worker bonus and is never repeated") {
        val fixture = rewardFixture(player, plugin, moneyCents = 290_000, workerBonusCents = 240_000) {
            CompletableFuture.completedFuture(Unit)
        }
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()

        verify(exactly = 1) { fixture.economy.deposit(player, 2_900.0) }
        fixture.service.snapshot().moneyDeliveries.values.single().apply {
            state shouldBe FarmRewardMoneyState.APPLIED
            reward.workerBonusCents shouldBe 240_000L
        }
    }

    test("a declined deposit remains rejected and visible without automatic retry") {
        val fixture = rewardFixture(player, plugin, moneyCents = 290_000, workerBonusCents = 240_000) {
            CompletableFuture.completedFuture(Unit)
        }
        every { fixture.economy.deposit(any(), any()) } returns false
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()

        fixture.service.snapshot().moneyDeliveries.values.single().state shouldBe FarmRewardMoneyState.REJECTED
        verify(exactly = 1) { fixture.economy.deposit(any(), any()) }
        verify(exactly = 1) { fixture.port.sendChat(player, MessageKey.FARM_REWARD_MONEY_REJECTED, any()) }
        verify(exactly = 0) { fixture.locale.render(MessageKey.FARM_REWARD_MONEY, any(), any()) }
    }

    test("an unknown provider outcome retains the full intent for manual review") {
        val fixture = rewardFixture(player, plugin, moneyCents = 290_000, workerBonusCents = 240_000) {
            CompletableFuture.completedFuture(Unit)
        }
        every { fixture.economy.deposit(any(), any()) } throws IllegalStateException("provider disconnected")
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()
        val saved = fixture.service.snapshot()
        fixture.service.replace(saved.pending, saved.claimed, saved.moneyDeliveries)
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()

        fixture.service.snapshot().moneyDeliveries.values.single().apply {
            state shouldBe FarmRewardMoneyState.MANUAL_REVIEW
            reward.moneyCents shouldBe 290_000L
            reward.workerBonusCents shouldBe 240_000L
        }
        verify(exactly = 1) { fixture.economy.deposit(any(), any()) }
        verify(atLeast = 1) { fixture.port.sendChat(player, MessageKey.FARM_REWARD_MONEY_UNCONFIRMED, any()) }
    }

    test("a recovered prepared deposit requires review and cannot replay") {
        val fixture = rewardFixture(player, plugin, moneyCents = 290_000) { CompletableFuture.completedFuture(Unit) }
        val reward = fixture.service.snapshot().pending.single()
        fixture.service.replace(emptyList(), mapOf(reward.claimKey to reward.sequence),
            mapOf(reward.id to FarmRewardMoneyDelivery(reward, FarmRewardMoneyState.PREPARED)))

        fixture.service.deliverPending(player)
        fixture.flushCallbacks()

        fixture.service.snapshot().moneyDeliveries.getValue(reward.id).state shouldBe FarmRewardMoneyState.MANUAL_REVIEW
        verify(exactly = 0) { fixture.economy.deposit(any(), any()) }
        player.totalExperience shouldBe 0
    }

    test("a failed receipt save retries only persistence and shutdown keeps applied grants claimed") {
        var saves = 0
        val fixture = rewardFixture(player, plugin, moneyCents = 290_000) {
            saves++
            if (saves == 2) CompletableFuture.failedFuture(IllegalStateException("receipt disk unavailable"))
            else CompletableFuture.completedFuture(Unit)
        }
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()

        saves shouldBe 2
        fixture.service.snapshot().moneyDeliveries.values.single().state shouldBe FarmRewardMoneyState.APPLIED
        fixture.scheduler.tick(40L)
        fixture.flushCallbacks()
        saves shouldBe 3
        fixture.service.prepareForLifecycleBoundary()
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()

        verify(exactly = 1) { fixture.economy.deposit(any(), any()) }
        fixture.service.snapshot().pending shouldBe emptyList()
        fixture.service.snapshot().claimed["communal_farm:${player.uniqueId}"] shouldBe 7L
        fixture.service.snapshot().moneyDeliveries.values.single().state shouldBe FarmRewardMoneyState.APPLIED
    }

    test("shutdown during an unpersisted receipt never restores an already paid reward") {
        var saves = 0
        val receiptGate = CompletableFuture<Unit>()
        val fixture = rewardFixture(player, plugin, moneyCents = 290_000) {
            if (++saves == 1) CompletableFuture.completedFuture(Unit) else receiptGate
        }
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()
        fixture.service.prepareForLifecycleBoundary()
        receiptGate.complete(Unit)
        fixture.flushCallbacks()
        val saved = fixture.service.snapshot()
        fixture.service.replace(saved.pending, saved.claimed, saved.moneyDeliveries)
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()

        saved.pending shouldBe emptyList()
        saved.moneyDeliveries.values.single().state shouldBe FarmRewardMoneyState.APPLIED
        verify(exactly = 1) { fixture.economy.deposit(any(), any()) }
    }

    test("disconnect while the claim is being saved defers every unsent component until reconnect") {
        val claimGate = CompletableFuture<Unit>()
        var saves = 0
        val fixture = rewardFixture(player, plugin, moneyCents = 290_000) {
            if (++saves == 1) claimGate else CompletableFuture.completedFuture(Unit)
        }
        fixture.service.deliverPending(player)
        player.disconnect()
        claimGate.complete(Unit)
        fixture.flushCallbacks()

        fixture.service.snapshot().pending shouldHaveSize 1
        fixture.service.snapshot().claimed shouldBe emptyMap()
        fixture.service.snapshot().moneyDeliveries shouldBe emptyMap()
        player.totalExperience shouldBe 0
        verify(exactly = 0) { fixture.economy.deposit(any(), any()) }

        player.reconnect()
        fixture.service.deliverPending(player)
        fixture.flushCallbacks()
        player.totalExperience shouldBe 12
        verify(exactly = 1) { fixture.economy.deposit(any(), any()) }
    }

    test("completion queued behind a claim is present in the same global state snapshot") {
        val gate = CompletableFuture<Unit>()
        val fixture = rewardFixture(player, plugin) { gate }
        fixture.service.deliverPending(player)
        val queued = PendingFarmReward("communal_farm:8:${player.uniqueId}", "communal_farm", 8, player.uniqueId, 10,
            moneyCents = 290_000, workerBonusCents = 240_000)
        fixture.service.enqueueRewards(listOf(queued))

        fixture.service.snapshot().pending shouldBe listOf(queued)
        fixture.service.prepareForLifecycleBoundary()
        fixture.service.snapshot().pending.map { it.sequence } shouldBe listOf(7L, 8L)
    }

    test("worker share is added after ordinary multipliers and remains frozen in the queued grant") {
        val gate = CompletableFuture<Unit>()
        val fixture = rewardFixture(player, plugin, multiplier = 200) { gate }
        fixture.service.replace(emptyList(), emptyMap())
        val configuredRewards = defaultWorksiteRewards(0).copy(money = FarmMoneyRewardSettings(100, 100))
        val runtime = FarmRuntime(
            settings = mockk<FarmZoneSettings> {
                every { id } returns "communal_farm"
                every { rewards } returns configuredRewards
            },
            region = mockk(), orders = emptyMap(), orderList = emptyList(), rules = mockk(),
            state = FarmShiftState(sequence = 8),
        )
        fixture.service.queueCompletion(runtime, mapOf(player.uniqueId to 10), mapOf(player.uniqueId to 1_001L))
        fixture.service.queueCompletion(runtime, mapOf(player.uniqueId to 10), mapOf(player.uniqueId to 2_000L))

        fixture.service.snapshot().pending.single().apply {
            moneyCents shouldBe 1_251L // ordinary 100 * 125% difficulty * 200% player, plus exact 1001
            workerBonusCents shouldBe 1_001L
        }
    }
})

private data class RewardFixture(
    val service: FarmRewardService,
    val scheduler: TestTaskScheduler,
    val economy: FarmEconomyGateway,
    val port: WorksiteRuntimePort,
    val locale: ArcFarmsLocale,
)

private fun rewardFixture(
    player: PlayerMock,
    plugin: Plugin,
    moneyCents: Long = 0,
    workerBonusCents: Long = 0,
    multiplier: Int = 100,
    persist: () -> CompletableFuture<Unit>,
): RewardFixture {
    val scheduler = TestTaskScheduler()
    val supervisor = RuntimeTaskSupervisor(scheduler).apply(RuntimeTaskSupervisor::activate)
    val locale = mockk<ArcFarmsLocale>(relaxed = true) {
        every { text(any()) } answers { Component.text(firstArg<Any?>()?.toString().orEmpty()) }
        every { render(any(), any(), any()) } answers { Component.text(firstArg<MessageKey>().path) }
        every { renderPath(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
    }
    val economy = mockk<FarmEconomyGateway> {
        every { available } returns true
        every { deposit(any(), any()) } returns true
    }
    val port = mockk<WorksiteRuntimePort>(relaxed = true)
    val service = FarmRewardService(
        plugin = plugin,
        locale = locale,
        economy = economy,
        debug = ArcFarmsDebug({ false }) {},
        port = port,
        supervisor = supervisor,
        persistAsync = persist,
        operational = { true },
        playerMultiplier = { _, _ -> multiplier },
    )
    service.replace(
        pending = listOf(
            PendingFarmReward(
                id = "communal_farm:7:${player.uniqueId}",
                zoneId = "communal_farm",
                sequence = 7,
                playerId = player.uniqueId,
                contribution = 100,
                experience = 12,
                items = listOf(FarmRewardItem(Material.BREAD.name, 3)),
                fixedItemUnits = 3,
                moneyCents = moneyCents,
                workerBonusCents = workerBonusCents,
            ),
        ),
        claimed = emptyMap(),
    )
    return RewardFixture(service, scheduler, economy, port, locale)
}

// Complete the bounded chain of main-thread persistence callbacks queued by one grant.
private fun RewardFixture.flushCallbacks() = repeat(4) { scheduler.executeImmediate() }

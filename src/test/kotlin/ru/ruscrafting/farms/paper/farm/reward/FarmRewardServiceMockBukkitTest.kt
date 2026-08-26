package ru.ruscrafting.farms.paper.farm.reward

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmRewardItem
import ru.ruscrafting.farms.domain.PendingFarmReward
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmEconomyGateway
import ru.ruscrafting.farms.paper.RuntimeTaskSupervisor
import ru.ruscrafting.farms.paper.WorksiteRuntimePort

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
        val fixture = rewardFixture(player, plugin, persist = { error("disk unavailable") })

        fixture.service.deliverPending(player)

        player.totalExperience shouldBe 0
        player.inventory.contains(Material.BREAD) shouldBe false
        fixture.service.snapshot().pending shouldHaveSize 1
        fixture.service.snapshot().claimed shouldBe emptyMap()
    }

    test("successful durable claim delivers exactly once") {
        var saves = 0
        val fixture = rewardFixture(player, plugin, persist = { saves++ })

        fixture.service.deliverPending(player)
        fixture.service.deliverPending(player)

        saves shouldBe 1
        player.totalExperience shouldBe 12
        player.inventory.all(Material.BREAD).values.sumOf { it.amount } shouldBe 3
        fixture.service.snapshot().pending shouldBe emptyList()
        fixture.service.snapshot().claimed["communal_farm:${player.uniqueId}"] shouldBe 7L
    }
})

private data class RewardFixture(val service: FarmRewardService)

private fun rewardFixture(player: PlayerMock, plugin: Plugin, persist: () -> Unit): RewardFixture {
    val supervisor = RuntimeTaskSupervisor(TestTaskScheduler()).apply(RuntimeTaskSupervisor::activate)
    val locale = mockk<ArcFarmsLocale>(relaxed = true) {
        every { text(any()) } answers { Component.text(firstArg<Any?>()?.toString().orEmpty()) }
        every { render(any(), any(), any()) } answers { Component.text(firstArg<MessageKey>().path) }
        every { renderPath(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
    }
    val economy = mockk<FarmEconomyGateway> {
        every { available } returns true
        every { deposit(any(), any()) } returns true
    }
    val service = FarmRewardService(
        plugin = plugin,
        locale = locale,
        economy = economy,
        debug = ArcFarmsDebug({ false }) {},
        port = mockk<WorksiteRuntimePort>(relaxed = true),
        supervisor = supervisor,
        persistBlocking = persist,
        operational = { true },
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
            ),
        ),
        claimed = emptyMap(),
    )
    return RewardFixture(service)
}

package ru.ruscrafting.farms.paper.farm.perk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.farms.domain.FarmFoodPurchase
import ru.ruscrafting.farms.domain.FarmPlayerPerks
import java.util.UUID

class FarmFoodShopTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("delivery waits for durable claim and cannot run twice") {
        val player = paper.server.addPlayer()
        val wallet = DelayedFoodWallet()
        val shop = FarmFoodShop(mockk(relaxed = true), mockk(relaxed = true), wallet)
        shop.deliver(player)
        shop.deliver(player)
        player.inventory.contains(Material.BREAD) shouldBe false
        wallet.value.foodPurchase!!.claimed shouldBe true
        wallet.complete()
        player.inventory.all(Material.BREAD).values.sumOf { it.amount } shouldBe 16
        wallet.complete()
        shop.deliver(player)
        player.inventory.all(Material.BREAD).values.sumOf { it.amount } shouldBe 16
        wallet.value.foodPurchase shouldBe null
    }

    test("inventory filled during claim keeps the purchase pending without drops") {
        val player = paper.server.addPlayer()
        val wallet = DelayedFoodWallet()
        val shop = FarmFoodShop(mockk(relaxed = true), mockk(relaxed = true), wallet)
        shop.deliver(player)
        repeat(36) { player.inventory.setItem(it, ItemStack(Material.STONE, 64)) }
        wallet.complete()
        wallet.complete()
        wallet.value.foodPurchase!!.claimed shouldBe false
        shop.deliver(player)
        wallet.callback shouldBe null
        player.inventory.contains(Material.BREAD) shouldBe false
        player.inventory.setItem(0, ItemStack(Material.BREAD, 48))
        shop.deliver(player)
        wallet.complete()
        wallet.complete()
        player.inventory.getItem(0)!!.amount shouldBe 64
    }

    test("restored claimed purchase is never replayed") {
        val player = paper.server.addPlayer()
        val wallet = DelayedFoodWallet()
        wallet.value = wallet.value.copy(foodPurchase = wallet.value.foodPurchase!!.copy(claimed = true))
        FarmFoodShop(mockk(relaxed = true), mockk(relaxed = true), wallet).deliver(player)
        wallet.callback shouldBe null
        player.inventory.contains(Material.BREAD) shouldBe false
    }
})

private class DelayedFoodWallet : FarmFoodWallet {
    var value = FarmPlayerPerks(100, 20, foodPurchase = FarmFoodPurchase(UUID.randomUUID().toString(), "BREAD", 16, 20, 100))
    var callback: (() -> Unit)? = null
    override fun read(playerId: UUID) = value
    override fun available(playerId: UUID) = 100L - value.spentPoints
    override fun busy(playerId: UUID) = callback != null
    override fun update(playerId: UUID, next: FarmPlayerPerks, committed: () -> Unit) {
        check(callback == null)
        value = next
        callback = committed
    }
    fun complete() {
        val committed = checkNotNull(callback)
        callback = null
        committed()
    }
}

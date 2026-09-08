package ru.ruscrafting.farms.paper.farm.perk

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmFoodOfferSettings
import ru.ruscrafting.farms.domain.FarmFoodPurchase
import ru.ruscrafting.farms.domain.FarmPlayerPerks
import ru.ruscrafting.farms.domain.buyFood
import ru.ruscrafting.farms.paper.ArcFarmsMenuPlatform
import ru.ruscrafting.farms.paper.FarmMenuCategory
import ru.ruscrafting.farms.paper.FarmMenuEntry
import java.util.UUID

internal interface FarmFoodWallet {
    fun read(playerId: UUID): FarmPlayerPerks
    fun available(playerId: UUID): Long
    fun busy(playerId: UUID): Boolean
    fun update(playerId: UUID, next: FarmPlayerPerks, committed: () -> Unit)
}

/** Food uses the same weekly wallet as perks, with durable purchase and claim boundaries. */
internal class FarmFoodShop(
    private val locale: ArcFarmsLocale,
    private val menus: ArcFarmsMenuPlatform,
    private val wallet: FarmFoodWallet,
) {
    fun entries(player: Player, offers: List<FarmFoodOfferSettings>, canAccess: () -> Boolean): List<FarmMenuEntry> =
        offers.map { offer ->
            val purchase = wallet.read(player.uniqueId).foodPurchase
            val hasSpace = fits(player, ItemStack(Material.valueOf(offer.material), offer.amount))
            val values = mapOf("amount" to locale.text(offer.amount), "price" to locale.text(offer.price))
            FarmMenuEntry(
                category = FarmMenuCategory.FOOD,
                details = listOf(
                    locale.renderPath("shop-table.quantity", player) to locale.text(offer.amount),
                    locale.renderPath("shop-table.price", player) to locale.renderPath("food.price", player, values),
                    locale.renderPath("shop-table.delivery", player) to locale.renderPath("food.description", player),
                    locale.renderPath("shop-table.state", player) to locale.renderPath(when {
                        purchase?.claimed == true -> "food.review"
                        purchase != null -> "food.waiting"
                        wallet.available(player.uniqueId) < offer.price -> "food.not-enough"
                        !hasSpace -> "food.full"
                        else -> "shop-table.available"
                    }, player),
                ),
                item = menus.item("food-${offer.id}", locale.renderPath("food.${offer.id}", player, values), buildList {
                    add(locale.renderPath("food.description", player))
                    add(locale.renderPath("food.price", player, values))
                    add(Component.empty())
                    add(locale.renderPath(when {
                        purchase?.claimed == true -> "food.review"
                        purchase != null -> "food.waiting"
                        wallet.available(player.uniqueId) < offer.price -> "food.not-enough"
                        !hasSpace -> "food.full"
                        else -> "food.buy"
                    }, player))
                }),
                enabled = purchase == null && hasSpace && !wallet.busy(player.uniqueId) && wallet.available(player.uniqueId) >= offer.price,
                acceptedClicks = setOf(ClickType.LEFT),
                onClick = {
                    if (canAccess()) buy(player, offer)
                },
            )
        }

    private fun buy(player: Player, offer: FarmFoodOfferSettings) {
        if (!player.isOnline || player.isDead || wallet.busy(player.uniqueId)) return
        val item = ItemStack(Material.valueOf(offer.material), offer.amount)
        if (!fits(player, item)) {
            message(player, "food.full")
            return
        }
        val before = wallet.read(player.uniqueId)
        val purchase = FarmFoodPurchase(UUID.randomUUID().toString(), offer.material, offer.amount, offer.price, before.weekStartEpochDay)
        val next = before.buyFood(purchase, offer.price, before.spentPoints + wallet.available(player.uniqueId)) ?: return
        wallet.update(player.uniqueId, next) {
            message(player, "food.purchased")
            deliver(player)
            refresh(player)
        }
    }

    fun deliver(player: Player) {
        if (!player.isOnline || player.isDead || wallet.busy(player.uniqueId)) return
        val before = wallet.read(player.uniqueId)
        val purchase = before.foodPurchase?.takeUnless { it.claimed } ?: return
        val item = ItemStack(Material.valueOf(purchase.material), purchase.amount)
        if (!fits(player, item)) return
        wallet.update(player.uniqueId, before.copy(foodPurchase = purchase.copy(claimed = true))) {
            // Inventory and connection can change while persistence is in flight.
            if (!player.isOnline || player.isDead || !fits(player, item)) {
                wallet.update(player.uniqueId, wallet.read(player.uniqueId).copy(foodPurchase = purchase)) { refresh(player) }
                return@update
            }
            // Main-thread capacity check and insertion are one uninterrupted operation.
            val leftovers = player.inventory.addItem(item)
            if (leftovers.isNotEmpty()) {
                // Never retry an ambiguous partial side effect automatically.
                message(player, "food.review")
                return@update
            }
            wallet.update(player.uniqueId, wallet.read(player.uniqueId).copy(foodPurchase = null)) {
                message(player, "food.delivered")
                refresh(player)
            }
        }
    }

    private fun fits(player: Player, item: ItemStack): Boolean {
        val capacity = player.inventory.storageContents.sumOf { slot ->
            when {
                slot == null || slot.type.isAir -> minOf(item.maxStackSize, player.inventory.maxStackSize)
                slot.isSimilar(item) -> (minOf(slot.maxStackSize, player.inventory.maxStackSize) - slot.amount).coerceAtLeast(0)
                else -> 0
            }
        }
        return capacity >= item.amount
    }

    private fun message(player: Player, path: String) {
        if (player.isOnline) player.sendMessage(locale.renderPath(path, player))
    }

    private fun refresh(player: Player) {
        menus.session(player)?.takeIf { it.menuId == ArcFarmsMenuPlatform.FARM_PERKS }?.requestRefresh()
    }
}

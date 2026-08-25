package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey

internal enum class FarmMarketDecision { ACCEPT, DECLINE }

internal data class FarmMarketClick(
    val zoneId: String,
    val sequence: Long,
    val decision: FarmMarketDecision,
)

internal class FarmMarketMenu(
    private val locale: ArcFarmsLocale,
    private val settings: () -> ArcFarmsConfig,
) {
    private class Holder(val zoneId: String, val sequence: Long) : InventoryHolder {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    fun open(player: Player, zoneId: String, sequence: Long, crop: Material, required: Int, bonusPercent: Int) {
        val holder = Holder(zoneId, sequence)
        val inventory = player.server.createInventory(holder, 27, locale.render(MessageKey.FARM_MARKET_MENU_TITLE, player))
        holder.backing = inventory
        inventory.setItem(
            13,
            item(
                crop,
                locale.render(
                    MessageKey.FARM_MARKET_MENU_ORDER,
                    player,
                    mapOf("crop" to MaterialRules.cropComponent(crop), "amount" to locale.text(required)),
                ),
                listOf(locale.render(MessageKey.FARM_MARKET_MENU_BONUS, player, mapOf("bonus" to locale.text(bonusPercent)))),
            ),
        )
        inventory.setItem(11, item(Material.EMERALD, locale.render(MessageKey.FARM_MARKET_MENU_ACCEPT, player), emptyList()))
        inventory.setItem(15, item(Material.BARRIER, locale.render(MessageKey.FARM_MARKET_MENU_DECLINE, player), emptyList()))
        backgroundItem()?.let { background ->
            repeat(inventory.size) { slot -> if (inventory.getItem(slot) == null) inventory.setItem(slot, background) }
        }
        player.openInventory(inventory)
    }

    fun handleClick(event: InventoryClickEvent): FarmMarketClick? {
        val holder = event.view.topInventory.holder as? Holder ?: return null
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return null
        val decision = when (event.rawSlot) {
            11 -> FarmMarketDecision.ACCEPT
            15 -> FarmMarketDecision.DECLINE
            else -> return null
        }
        return FarmMarketClick(holder.zoneId, holder.sequence, decision)
    }

    fun handleDrag(event: InventoryDragEvent): Boolean {
        if (event.view.topInventory.holder !is Holder) return false
        event.isCancelled = true
        return true
    }

    @Suppress("DEPRECATION")
    private fun backgroundItem(): ItemStack? {
        val background = settings().menuBackground
        if (!background.enabled) return null
        return ItemStack(MaterialRules.material(background.material)).apply {
            editMeta { meta ->
                if (background.customModelData > 0) meta.setCustomModelData(background.customModelData)
                meta.setHideTooltip(true)
            }
        }
    }

    private fun item(material: Material, name: Component, lore: List<Component>): ItemStack = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
    }
}

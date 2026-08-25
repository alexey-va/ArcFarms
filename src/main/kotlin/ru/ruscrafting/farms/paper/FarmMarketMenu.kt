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

internal enum class FarmMarketMode { PENDING, ACTIVE }

internal enum class FarmMarketDecision { ACCEPT, DECLINE, CLOSE }

internal data class FarmMarketClick(
    val zoneId: String,
    val sequence: Long,
    val mode: FarmMarketMode,
    val decision: FarmMarketDecision,
)

internal class FarmMarketMenu(
    private val locale: ArcFarmsLocale,
    private val settings: () -> ArcFarmsConfig,
) {
    private class Holder(val zoneId: String, val sequence: Long, val mode: FarmMarketMode) : InventoryHolder {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    fun openPending(
        player: Player,
        zoneId: String,
        sequence: Long,
        crop: Material,
        required: Int,
        bonusPercent: Int,
        time: String,
    ) {
        open(player, zoneId, sequence, crop, 0, required, bonusPercent, time, FarmMarketMode.PENDING)
    }

    fun openActive(
        player: Player,
        zoneId: String,
        sequence: Long,
        crop: Material,
        progress: Int,
        required: Int,
        bonusPercent: Int,
        time: String,
    ) {
        open(player, zoneId, sequence, crop, progress, required, bonusPercent, time, FarmMarketMode.ACTIVE)
    }

    private fun open(
        player: Player,
        zoneId: String,
        sequence: Long,
        crop: Material,
        progress: Int,
        required: Int,
        bonusPercent: Int,
        time: String,
        mode: FarmMarketMode,
    ) {
        val holder = Holder(zoneId, sequence, mode)
        val inventory = player.server.createInventory(holder, 27, locale.render(MessageKey.FARM_MARKET_MENU_TITLE, player))
        holder.backing = inventory
        inventory.setItem(
            13,
            item(
                MaterialRules.harvestItemForCrop(crop),
                locale.render(
                    MessageKey.FARM_MARKET_MENU_ORDER,
                    player,
                    mapOf(
                        "crop" to locale.renderPath("crop.${crop.name.lowercase()}", player),
                        "amount" to locale.text(required),
                    ),
                ),
                buildList {
                    if (mode == FarmMarketMode.ACTIVE) {
                        add(locale.render(
                            MessageKey.FARM_MARKET_MENU_PROGRESS,
                            player,
                            mapOf("done" to locale.text(progress), "total" to locale.text(required)),
                        ))
                    }
                    add(locale.render(MessageKey.FARM_MARKET_MENU_BONUS, player, mapOf("bonus" to locale.text(bonusPercent))))
                    add(locale.render(
                        if (mode == FarmMarketMode.ACTIVE) {
                            MessageKey.FARM_MARKET_MENU_TIME_REMAINING
                        } else {
                            MessageKey.FARM_MARKET_MENU_TIME_LIMIT
                        },
                        player,
                        mapOf("time" to locale.text(time)),
                    ))
                },
            ),
        )
        if (mode == FarmMarketMode.PENDING) {
            inventory.setItem(11, item(Material.EMERALD, locale.render(MessageKey.FARM_MARKET_MENU_ACCEPT, player), emptyList()))
            inventory.setItem(15, item(Material.BARRIER, locale.render(MessageKey.FARM_MARKET_MENU_DECLINE, player), emptyList()))
        } else {
            inventory.setItem(22, item(Material.BARRIER, locale.render(MessageKey.FARM_MARKET_MENU_CLOSE, player), emptyList()))
        }
        backgroundItem()?.let { background ->
            repeat(inventory.size) { slot -> if (inventory.getItem(slot) == null) inventory.setItem(slot, background) }
        }
        player.openInventory(inventory)
    }

    fun handleClick(event: InventoryClickEvent): FarmMarketClick? {
        val holder = event.view.topInventory.holder as? Holder ?: return null
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return null
        val decision = decisionFor(holder.mode, event.rawSlot) ?: return null
        return FarmMarketClick(holder.zoneId, holder.sequence, holder.mode, decision)
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

    internal companion object {
        fun decisionFor(mode: FarmMarketMode, slot: Int): FarmMarketDecision? = when (mode) {
            FarmMarketMode.PENDING -> when (slot) {
                11 -> FarmMarketDecision.ACCEPT
                15 -> FarmMarketDecision.DECLINE
                else -> null
            }
            FarmMarketMode.ACTIVE -> if (slot == 22) FarmMarketDecision.CLOSE else null
        }
    }
}

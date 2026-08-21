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
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind

class ArcFarmsMenu(
    private val service: ArcFarmsService,
    private val locale: ArcFarmsLocale,
    private val reload: () -> Result<Unit>,
) {
    private class Holder : InventoryHolder {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    fun open(player: Player) {
        val holder = Holder()
        val inventory = player.server.createInventory(holder, 27, locale.render(MessageKey.MENU_TITLE, player))
        holder.backing = inventory
        inventory.setItem(10, activityItem(player, ActivityKind.FARM, Material.WHEAT, MessageKey.MENU_FARM_NAME, MessageKey.MENU_FARM_LORE))
        inventory.setItem(12, activityItem(player, ActivityKind.LUMBER, Material.IRON_AXE, MessageKey.MENU_LUMBER_NAME, MessageKey.MENU_LUMBER_LORE))
        inventory.setItem(14, activityItem(player, ActivityKind.MINE, Material.MINECART, MessageKey.MENU_MINE_NAME, MessageKey.MENU_MINE_LORE))
        val stats = service.playerStats(player.uniqueId)
        inventory.setItem(
            16,
            item(
                Material.BOOK,
                locale.render(MessageKey.MENU_STATS_NAME, player),
                listOf(
                    locale.render(
                        MessageKey.MENU_STATS_LORE,
                        player,
                        mapOf(
                            "farm" to locale.text(stats.contributions[ActivityKind.FARM] ?: 0),
                            "lumber" to locale.text(stats.contributions[ActivityKind.LUMBER] ?: 0),
                            "mine" to locale.text(stats.contributions[ActivityKind.MINE] ?: 0),
                        ),
                    ),
                ),
            ),
        )
        if (player.hasPermission("arcfarms.admin")) {
            inventory.setItem(
                22,
                item(
                    Material.REDSTONE_TORCH,
                    locale.render(MessageKey.MENU_ADMIN_NAME, player),
                    listOf(locale.render(MessageKey.MENU_ADMIN_LORE, player)),
                ),
            )
        }
        player.openInventory(inventory)
    }

    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder !is Holder) return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        when (event.rawSlot) {
            10 -> navigate(player, ActivityKind.FARM)
            12 -> navigate(player, ActivityKind.LUMBER)
            14 -> navigate(player, ActivityKind.MINE)
            22 -> if (player.hasPermission("arcfarms.admin")) {
                reload().fold(
                    onSuccess = {
                        player.closeInventory()
                        player.sendMessage(locale.render(MessageKey.RELOAD_OK, player))
                    },
                    onFailure = { failure ->
                        player.sendMessage(
                            locale.render(
                                MessageKey.RELOAD_FAILED,
                                player,
                                mapOf("reason" to locale.text(failure.message ?: "unknown")),
                            ),
                        )
                    },
                )
            }
        }
    }

    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    private fun activityItem(
        player: Player,
        kind: ActivityKind,
        material: Material,
        name: MessageKey,
        lore: MessageKey,
    ): ItemStack {
        val lines = mutableListOf(locale.render(lore, player))
        if (service.isAvailable(kind) && service.canAccess(player, kind)) lines += locale.render(MessageKey.MENU_CLICK, player)
        return item(material, locale.render(name, player), lines)
    }

    private fun navigate(player: Player, kind: ActivityKind) {
        if (!service.isAvailable(kind)) {
            player.sendMessage(locale.render(MessageKey.ZONE_UNAVAILABLE, player))
            return
        }
        if (!service.canAccess(player, kind)) {
            player.sendMessage(locale.render(MessageKey.ZONE_LOCKED, player))
            return
        }
        player.closeInventory()
        player.performCommand(service.navigation(kind))
    }

    private fun item(material: Material, name: Component, lore: List<Component>): ItemStack =
        ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(name.decoration(TextDecoration.ITALIC, false))
                meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
            }
        }
}

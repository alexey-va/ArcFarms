package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementId
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuEntry
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import java.util.WeakHashMap

internal enum class FarmMarketMode { PENDING, ACTIVE }

internal enum class FarmMarketDecision { ACCEPT, DECLINE }

internal data class FarmMarketClick(
    val zoneId: String,
    val sequence: Long,
    val mode: FarmMarketMode,
    val decision: FarmMarketDecision,
)

/** Urgent-market screen. Inventory Framework owns click isolation; this class owns domain decisions. */
internal class FarmMarketMenu(
    private val locale: ArcFarmsLocale,
    private val menus: ArcFarmsMenuPlatform,
    private val refreshView: (Player, String, Long) -> Unit,
) {
    private val decisions = WeakHashMap<InventoryClickEvent, FarmMarketClick>()

    fun openPending(
        player: Player,
        zoneId: String,
        sequence: Long,
        crop: Material,
        required: Int,
        bonusPercent: Int,
        time: String,
    ) = open(player, zoneId, sequence, crop, 0, required, bonusPercent, time, FarmMarketMode.PENDING)

    fun openActive(
        player: Player,
        zoneId: String,
        sequence: Long,
        crop: Material,
        progress: Int,
        required: Int,
        bonusPercent: Int,
        time: String,
    ) = open(player, zoneId, sequence, crop, progress, required, bonusPercent, time, FarmMarketMode.ACTIVE)

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
        menus.open(player, MENU, { refreshView(player, zoneId, sequence) }) {
            val elements = linkedMapOf<MenuElementId, PaperMenuEntry>()
            elements[ORDER] = PaperMenuEntry(
                item = item(
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
                            if (mode == FarmMarketMode.ACTIVE) MessageKey.FARM_MARKET_MENU_TIME_REMAINING
                            else MessageKey.FARM_MARKET_MENU_TIME_LIMIT,
                            player,
                            mapOf("time" to locale.text(time)),
                        ))
                    },
                ),
                enabled = false,
            )
            if (mode == FarmMarketMode.PENDING) {
                val lore = listOf(locale.render(MessageKey.FARM_MARKET_MENU_CHOOSE, player))
                elements[ACCEPT] = decisionEntry(
                    ACCEPT, player, locale.render(MessageKey.FARM_MARKET_MENU_ACCEPT, player), lore,
                    FarmMarketClick(zoneId, sequence, mode, FarmMarketDecision.ACCEPT),
                )
                elements[DECLINE] = decisionEntry(
                    DECLINE, player, locale.render(MessageKey.FARM_MARKET_MENU_DECLINE, player), lore,
                    FarmMarketClick(zoneId, sequence, mode, FarmMarketDecision.DECLINE),
                )
            }
            PaperMenuContent(
                title = locale.render(MessageKey.FARM_MARKET_MENU_TITLE, player),
                background = menus.background(MENU),
                elements = elements,
            )
        }
    }

    fun handleClick(event: InventoryClickEvent): FarmMarketClick? {
        if (!menus.owns(event, setOf(MENU))) return null
        return decisions.remove(event)
    }

    fun handleDrag(event: InventoryDragEvent): Boolean = menus.owns(event, setOf(MENU))

    private fun decisionEntry(
        element: MenuElementId,
        player: Player,
        name: Component,
        lore: List<Component>,
        decision: FarmMarketClick,
    ) = PaperMenuEntry(
        item = menus.item(MENU, element, name, lore),
        acceptedClicks = setOf(ClickType.LEFT),
        onClick = { context -> decisions[context.event] = decision },
    )

    private fun item(material: Material, name: Component, lore: List<Component>): ItemStack {
        val configured = menus.item(MENU, ORDER, name, lore)
        return ItemStack.of(material, configured.amount).also { item -> item.itemMeta = configured.itemMeta }
    }

    private companion object {
        val MENU = ArcFarmsMenuPlatform.MARKET
        val ORDER = MenuElementId.of("order")
        val ACCEPT = MenuElementId.of("accept")
        val DECLINE = MenuElementId.of("decline")
    }
}

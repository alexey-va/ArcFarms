package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import ru.arc.menu.MenuElementKind
import ru.arc.paper.menu.*

/** The Help center's restrained palette and two-column native dialog composition. */
internal object FarmDialogScreens {
    fun screen(session: FarmMenuSession, config: PaperMenuConfiguration, text: (Player, String) -> Component, closeOnEscape: Boolean = false): PaperDialogScreen {
        val content = session.content()
        val layout = config.catalog.require(session.menuId)
        data class Row(val id: String, val slot: Int, val entry: FarmMenuEntry, val actionable: Boolean)
        val rows = buildList {
            content.elements.forEach { (id, entry) ->
                val spec = layout.elements.getValue(id)
                add(Row(id.value, spec.slots.first().index, entry, spec.kind == MenuElementKind.BUTTON && entry.enabled))
            }
            content.regions.forEach { (id, entries) ->
                val slots = layout.regions.getValue(id).slots
                require(entries.size <= slots.size) { "Dialog region exceeds its configured capacity" }
                entries.forEachIndexed { index, entry -> add(Row("${id.value}_$index", slots[index].index, entry, entry.enabled)) }
            }
        }
        val information = rows.filter {
            !it.actionable && it.id in setOf("stats", "workday", "report", "workers", "policy", "license")
        }
        val revision = session.revision
        fun button(id: String, name: Component, tooltip: Component = Component.empty(), action: () -> Unit) = PaperDialogButton(
            id = PaperDialogActionId.of(id.replace('-', '_')), label = recolor(name, ACTION),
            tooltip = tooltip, width = 230, onClick = {
                if (session.platform.session(session.player) === session && session.revision == revision && !session.pending) {
                    session.revision++
                    val consumedRevision = session.revision
                    action()
                    // Core consumes the whole dialog registration. A rejected/no-op action
                    // must publish fresh buttons too, otherwise even Back stops responding.
                    if (session.platform.session(session.player) === session &&
                        session.revision == consumedRevision && !session.pending) session.platform.refresh(session)
                }
            },
        )
        val detail = session.detailSlot?.let { slot -> rows.firstOrNull { it.slot == slot } }
        if (detail != null || session.showInformation) {
            return PaperDialogScreen(
                id = "farms.${session.menuId.value}.detail",
                title = recolor(detail?.let { name(it.entry.item) } ?: text(session.player, "details"), TITLE),
                body = if (detail != null) listOf(PaperDialogBody(join(lore(detail.entry.item)), 468)) else
                    information.map { row -> PaperDialogBody(join(listOf(recolor(name(row.entry.item), TITLE)) + lore(row.entry.item)), 468) },
                buttons = buildList {
                    if (session.menuId == ArcFarmsMenuPlatform.FARM_PERKS && detail?.actionable == true) {
                        add(button("buy_perk", text(session.player, "buy-perk")) {
                            if (ClickType.LEFT in detail.entry.acceptedClicks) {
                                detail.entry.onClick.handle(FarmMenuClickContext(session.player, session, detail.slot))
                            }
                        })
                    }
                },
                exitButton = button("detail_back", text(session.player, if (closeOnEscape) "close" else "back")) { session.close() }
                    .copy(width = 200, label = recolor(text(session.player, if (closeOnEscape) "close" else "back"), MUTED)), columns = 1,
            )
        }
        val body = mutableListOf(PaperDialogBody(text(session.player, "intro.${session.menuId.value}"), 468))
        val buttons = mutableListOf<PaperDialogButton>()
        var back: PaperDialogButton? = null
        rows.forEach { row ->
            val item = session.feedback[row.slot] ?: row.entry.item
            val lines = lore(item)
            val title = name(item)
            val tooltip = join(lines)
            if (row.actionable && row.id != "back" && session.menuId in setOf(
                    ArcFarmsMenuPlatform.MAIN, ArcFarmsMenuPlatform.ENTERPRISE_FARM,
                    ArcFarmsMenuPlatform.ENTERPRISE_PARTICIPATION,
                )) {
                lines.firstOrNull { plain.serialize(it).isNotBlank() }?.let {
                    body += PaperDialogBody(join(listOf(recolor(title, TITLE), it)), 468)
                }
            }
            val dispatch = {
                if (ClickType.LEFT in row.entry.acceptedClicks && row.entry.enabled) {
                    row.entry.onClick.handle(FarmMenuClickContext(session.player, session, row.slot))
                }
            }
            when {
                row in information -> Unit
                row.id == "back" -> back = button("back", title, tooltip, dispatch)
                    .copy(width = 200, label = recolor(title, MUTED))
                row.id == "confirm" -> {
                    // Price, license loss and voting terms stay visible before the action.
                    body += PaperDialogBody(join(listOf(recolor(title, TITLE)) + lines), 468)
                    if (row.actionable) buttons += button(row.id, title, tooltip, dispatch)
                }
                row.id in setOf("header", "summary", "balance", "order", "status", "holding", "account") ->
                    body.add(PaperDialogBody(join(listOf(recolor(title, TITLE)) + lines), 468))
                row.actionable -> buttons += button(row.id, title, tooltip) {
                    if (session.menuId == ArcFarmsMenuPlatform.FARM_PERKS) {
                        session.detailSlot = row.slot; session.platform.refresh(session)
                    } else dispatch()
                }
                else -> buttons += button("info_${row.id}", recolor(title, MUTED), tooltip) {
                    session.showInformation = false; session.detailSlot = row.slot; session.platform.refresh(session)
                }.copy(label = recolor(title, MUTED))
            }
        }
        if (information.isNotEmpty()) buttons += button("details", text(session.player, "details")) {
            session.detailSlot = null; session.showInformation = true; session.platform.refresh(session)
        }
        if (back == null) {
            back = button("close", text(session.player, if (closeOnEscape) "close" else "back")) { session.close() }
                .copy(width = 200, label = recolor(text(session.player, if (closeOnEscape) "close" else "back"), MUTED))
            if (session.menuId == ArcFarmsMenuPlatform.MAIN) {
                buttons += button("help", text(session.player, "help")) {
                    session.platform.transition(session.player, session) {
                        if (!session.platform.usesDialogs) session.close()
                        session.player.performCommand("arc help")
                    }
                }
            }
        }
        return PaperDialogScreen(
            id = "farms.${session.menuId.value}",
            title = recolor(content.title, TITLE), body = body,
            buttons = buttons, exitButton = back, columns = if (buttons.size == 1) 1 else 2,
        )
    }

    private fun name(item: ItemStack) = item.itemMeta.displayName() ?: Component.text(item.type.name)
    internal fun lore(item: ItemStack): List<Component> = item.itemMeta.lore().orEmpty()
        .filterNot { plain.serialize(it).contains("[▶]") }
        .dropLastWhile { plain.serialize(it).isBlank() }
        .map { restyle(it).decoration(TextDecoration.ITALIC, false) }
    private fun join(lines: List<Component>) = Component.join(JoinConfiguration.newlines(), lines)
    private fun recolor(value: Component, color: TextColor): Component = value.color(color)
        .decoration(TextDecoration.ITALIC, false).children(value.children().map { recolor(it, color) })
    private fun restyle(value: Component): Component = value.color(when (value.color()?.value()) {
        0x707a76, 0xb8c8c0 -> MUTED
        0xf2fff7 -> BODY
        else -> value.color()
    }).children(value.children().map(::restyle))
    private val plain = PlainTextComponentSerializer.plainText()
    private val TITLE = TextColor.color(0xf4bd6a)
    private val ACTION = TextColor.color(0xd7b486)
    private val MUTED = TextColor.color(0xaaa49a)
    private val BODY = TextColor.color(0xe8dfd2)
}

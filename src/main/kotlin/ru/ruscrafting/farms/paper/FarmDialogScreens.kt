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
        val rows = buildList {
            content.elements.forEach { (id, entry) ->
                val spec = layout.elements.getValue(id)
                add(DialogRow(id.value, spec.slots.first().index, entry, spec.kind == MenuElementKind.BUTTON && entry.enabled))
            }
            content.regions.forEach { (id, entries) ->
                val slots = layout.regions.getValue(id).slots
                require(entries.size <= slots.size) { "Dialog region exceeds its configured capacity" }
                entries.forEachIndexed { index, entry -> add(DialogRow("${id.value}_$index", slots[index].index, entry, entry.enabled)) }
            }
        }
        val information = rows.filter {
            !it.actionable && it.id in setOf("stats", "workday", "report", "workers", "policy", "license")
        }
        val revision = session.revision
        fun button(
            id: String,
            name: Component,
            tooltip: Component = Component.empty(),
            style: LabelStyle = LabelStyle(ORDINARY),
            action: () -> Unit,
        ) = PaperDialogButton(
            id = PaperDialogActionId.of(id.replace('-', '_')), label = label(name, style),
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
        fun style(row: DialogRow): LabelStyle {
            val informational = row.id in setOf("stats", "workday", "report", "workers", "policy", "license")
            if (row.entry.selected && row.entry.category != FarmMenuCategory.DEFAULT) return LabelStyle(SELECTED, marker = "✔", chevron = true)
            if (!row.entry.enabled && !informational) return LabelStyle(MUTED, marker = "[Недоступно]")
            return when (session.menuId) {
                ArcFarmsMenuPlatform.MAIN -> when (row.id) {
                    "farm", "lumber", "mine" -> LabelStyle(TELEPORT, chevron = true)
                    "companies" -> LabelStyle(TRADE, chevron = true)
                    else -> LabelStyle(ORDINARY)
                }
                ArcFarmsMenuPlatform.ENTERPRISE_OVERVIEW -> when (row.id) {
                    "farm", "lumber", "mine" -> LabelStyle(DETAIL, chevron = true)
                    else -> LabelStyle(ORDINARY)
                }
                ArcFarmsMenuPlatform.ENTERPRISE_FARM -> when (row.id) {
                    "shares" -> LabelStyle(DETAIL, chevron = true)
                    "market" -> LabelStyle(TRADE, chevron = true)
                    else -> LabelStyle(ORDINARY)
                }
                ArcFarmsMenuPlatform.ENTERPRISE_SHARES -> when {
                    row.id.startsWith("buy-options_") -> LabelStyle(TRADE, chevron = true)
                    row.id == "withdraw" -> LabelStyle(SAVE)
                    else -> LabelStyle(ORDINARY)
                }
                ArcFarmsMenuPlatform.ENTERPRISE_CONFIRM -> when (row.id) {
                    "confirm" -> LabelStyle(SAVE)
                    else -> LabelStyle(ORDINARY)
                }
                ArcFarmsMenuPlatform.ENTERPRISE_PARTICIPATION -> when (row.id) {
                    "steady", "team", "challenge" -> if (row.entry.selected) {
                        LabelStyle(SELECTED, marker = "✔")
                    } else {
                        LabelStyle(AVAILABLE, marker = "○")
                    }
                    "confirm" -> LabelStyle(SAVE)
                    else -> LabelStyle(ORDINARY)
                }
                ArcFarmsMenuPlatform.FARM_PERKS -> if (row.id.startsWith("offers_")) {
                    LabelStyle(when (row.entry.category) {
                        FarmMenuCategory.FOOD -> TRADE
                        FarmMenuCategory.PREMIUM_PERK -> PROGRESSION
                        else -> DETAIL
                    }, chevron = true)
                } else LabelStyle(ORDINARY)
                ArcFarmsMenuPlatform.MARKET -> when (row.id) {
                    "accept" -> LabelStyle(SAVE)
                    "decline" -> LabelStyle(DELETE)
                    else -> LabelStyle(ORDINARY)
                }
                else -> LabelStyle(ORDINARY)
            }
        }
        fun headingColor(row: DialogRow): TextColor = if (session.menuId == ArcFarmsMenuPlatform.MAIN) {
            when (row.id) {
                "farm", "lumber", "mine" -> TELEPORT
                "companies" -> TRADE
                else -> TITLE
            }
        } else TITLE
        val detail = session.detailSlot?.let { slot -> rows.firstOrNull { it.slot == slot } }
        if (detail != null || session.showInformation) {
            val detailTitleColor = detail?.let { style(it).color } ?: DETAIL
            return PaperDialogScreen(
                id = "farms.${session.menuId.value}.detail",
                title = recolor(detail?.let { name(it.entry.item) } ?: text(session.player, "details"), detailTitleColor),
                body = if (detail != null) {
                    if (detail.entry.details.isNotEmpty()) listOf(FarmDialogTables.body(detail.entry.details, FarmDialogTables.Frame.LEGENDARY))
                    else listOf(PaperDialogBody(join(lore(detail.entry.item)), 468))
                } else listOf(FarmDialogTables.body(information.map { row -> name(row.entry.item) to join(lore(row.entry.item)) })),
                buttons = buildList {
                    if (session.menuId == ArcFarmsMenuPlatform.FARM_PERKS && detail?.actionable == true) {
                        add(button("buy_perk", text(session.player, if (detail.entry.category == FarmMenuCategory.FOOD) "buy-food" else "buy-perk"), style = LabelStyle(SAVE)) {
                            if (ClickType.LEFT in detail.entry.acceptedClicks) {
                                detail.entry.onClick.handle(FarmMenuClickContext(session.player, session, detail.slot))
                            }
                        })
                    }
                },
                exitButton = button("detail_back", text(session.player, if (closeOnEscape) "close" else "back"), style = LabelStyle(MUTED)) { session.close() }
                    .copy(width = 200, label = label(text(session.player, if (closeOnEscape) "close" else "back"), LabelStyle(MUTED))), columns = 1,
            )
        }
        val body = mutableListOf(PaperDialogBody(restyle(text(session.player, "intro.${session.menuId.value}")), 468))
        if (content.summary.isNotEmpty()) body += FarmDialogTables.body(content.summary,
            if (session.menuId == ArcFarmsMenuPlatform.FARM_PERKS) FarmDialogTables.Frame.LEGENDARY else FarmDialogTables.Frame.EPIC)
        val catalog = rows.mapNotNull { row ->
            row.entry.catalogValue?.let { recolor(name(row.entry.item), BODY) to it.color(TRADE).decoration(TextDecoration.ITALIC, false) }
        }
        if (catalog.isNotEmpty()) body += FarmDialogTables.body(catalog, FarmDialogTables.Frame.LEGENDARY)
        val buttons = mutableListOf<PaperDialogButton>()
        var back: PaperDialogButton? = null
        rows.forEach { row ->
            val item = session.feedback[row.slot] ?: row.entry.item
            val lines = lore(item)
            val title = name(item)
            val tooltip = join(lines)
            if (content.summary.isEmpty() && row.actionable && row.id != "back" && session.menuId in setOf(
                    ArcFarmsMenuPlatform.MAIN, ArcFarmsMenuPlatform.ENTERPRISE_FARM,
                    ArcFarmsMenuPlatform.ENTERPRISE_PARTICIPATION,
                )) {
                    lines.firstOrNull { plain.serialize(it).isNotBlank() }?.let {
                    body += PaperDialogBody(join(listOf(recolor(title, headingColor(row)), it)), 468)
                }
            }
            val dispatch = {
                if (ClickType.LEFT in row.entry.acceptedClicks && row.entry.enabled) {
                    row.entry.onClick.handle(FarmMenuClickContext(session.player, session, row.slot))
                }
            }
            when {
                row in information -> Unit
                row.id == "back" -> back = button("back", title, tooltip, LabelStyle(MUTED), dispatch)
                    .copy(
                        width = 200,
                        label = label(if (closeOnEscape) text(session.player, "close") else title, LabelStyle(MUTED)),
                        tooltip = if (closeOnEscape) Component.empty() else tooltip,
                    )
                row.id == "confirm" -> {
                    // Price, license loss and voting terms stay visible before the action.
                    body += PaperDialogBody(join(listOf(recolor(title, TITLE)) + lines), 468)
                    if (row.actionable) buttons += button(row.id, title, tooltip, style(row), dispatch)
                }
                row.id == "balance" && content.summary.isNotEmpty() -> Unit
                row.id in setOf("header", "summary", "balance", "order", "status", "holding", "account") ->
                    body.add(PaperDialogBody(join(listOf(recolor(title, TITLE)) + lines), 468))
                row.actionable -> buttons += button(row.id, title, tooltip, style(row)) {
                    if (session.menuId == ArcFarmsMenuPlatform.FARM_PERKS) {
                        session.detailSlot = row.slot; session.platform.refresh(session)
                    } else dispatch()
                }
                else -> buttons += button("info_${row.id}", title, tooltip, style(row)) {
                    session.showInformation = false; session.detailSlot = row.slot; session.platform.refresh(session)
                }
            }
        }
        if (information.isNotEmpty() && content.summary.isEmpty()) buttons += button("details", text(session.player, "details"), style = LabelStyle(DETAIL, chevron = true)) {
            session.detailSlot = null; session.showInformation = true; session.platform.refresh(session)
        }
        if (back == null) {
            back = button("close", text(session.player, if (closeOnEscape) "close" else "back"), style = LabelStyle(MUTED)) { session.close() }
                .copy(width = 200, label = label(text(session.player, if (closeOnEscape) "close" else "back"), LabelStyle(MUTED)))
        }
        return PaperDialogScreen(
            id = "farms.${session.menuId.value}",
            title = recolor(content.title, if (session.menuId == ArcFarmsMenuPlatform.FARM_PERKS) TRADE else TITLE), body = body,
            buttons = buttons, exitButton = back, columns = if (buttons.size == 1) 1 else 2,
        )
    }

    private fun name(item: ItemStack) = item.itemMeta.displayName() ?: Component.text(item.type.name)
    internal fun lore(item: ItemStack): List<Component> = item.itemMeta.lore().orEmpty()
        .filterNot { plain.serialize(it).contains("[▶]") }
        .dropLastWhile { plain.serialize(it).isBlank() }
        .map { restyle(it).decoration(TextDecoration.ITALIC, false) }
    private fun join(lines: List<Component>) = Component.join(JoinConfiguration.newlines(), lines)
    private data class LabelStyle(
        val color: TextColor,
        val marker: String? = null,
        val chevron: Boolean = false,
    )

    private fun label(value: Component, style: LabelStyle): Component {
        val styled = recolor(value, style.color)
        val plainValue = plain.serialize(value).trimStart()
        val alreadyMarked = plainValue.startsWith("✔") || plainValue.startsWith("○")
        val prefix = style.marker?.takeUnless { alreadyMarked }?.let { Component.text("$it ").color(style.color) }
        val suffix = if (style.chevron && !plain.serialize(value).trimEnd().endsWith("›")) Component.text(" ›").color(style.color) else null
        return (prefix?.append(styled) ?: styled).let { suffix?.let(it::append) ?: it }
    }

    private fun recolor(value: Component, color: TextColor): Component = value.color(color)
        .decoration(TextDecoration.ITALIC, false).children(value.children().map { recolor(it, color) })
    private fun restyle(value: Component): Component = value.color(when (value.color()?.value()) {
        0x707a76, 0x969696, 0xb8c8c0 -> MUTED
        0xe6fff3, 0xf2fff7 -> BODY
        else -> value.color()
    }).children(value.children().map(::restyle))
    private data class DialogRow(val id: String, val slot: Int, val entry: FarmMenuEntry, val actionable: Boolean)
    private val plain = PlainTextComponentSerializer.plainText()
    private val TITLE = TextColor.color(0xffb277)
    private val TELEPORT = TextColor.color(0x92bed8)
    private val TRADE = TextColor.color(0xf4d87a)
    private val PROGRESSION = TextColor.color(0xc4abff)
    private val DETAIL = TextColor.color(0xc4a7e7)
    private val AVAILABLE = TextColor.color(0xffffff)
    private val SELECTED = TextColor.color(0x9bd48d)
    private val SAVE = TextColor.color(0x9bd48d)
    private val DELETE = TextColor.color(0xff6b61)
    private val MUTED = TextColor.color(0xaaa49a)
    private val BODY = TextColor.color(0xe8dfd2)
    private val ORDINARY = TextColor.color(0xd7b486)
}

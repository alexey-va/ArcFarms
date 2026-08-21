package ru.ruscrafting.farms.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

enum class MessageKey(val path: String) {
    PREFIX("prefix"),
    HELP("help"),
    NO_PERMISSION("no-permission"),
    PLAYER_ONLY("player-only"),
    BAD_ACTIVITY("bad-activity"),
    RELOAD_OK("reload-ok"),
    RELOAD_FAILED("reload-failed"),
    GENERIC_ERROR("generic-error"),
    ZONE_UNAVAILABLE("zone-unavailable"),
    ZONE_LOCKED("zone-locked"),
    COOLDOWN("cooldown"),
    MENU_TITLE("menu.title"),
    MENU_FARM_NAME("menu.farm.name"),
    MENU_FARM_LORE("menu.farm.lore"),
    MENU_LUMBER_NAME("menu.lumber.name"),
    MENU_LUMBER_LORE("menu.lumber.lore"),
    MENU_MINE_NAME("menu.mine.name"),
    MENU_MINE_LORE("menu.mine.lore"),
    MENU_STATS_NAME("menu.stats.name"),
    MENU_STATS_LORE("menu.stats.lore"),
    MENU_ADMIN_NAME("menu.admin.name"),
    MENU_ADMIN_LORE("menu.admin.lore"),
    MENU_CLICK("menu.click"),
    STATUS_HEADER("status.header"),
    STATUS_ENTRY("status.entry"),
    STATUS_EMPTY("status.empty"),
    TOP_HEADER("top.header"),
    TOP_ENTRY("top.entry"),
    TOP_EMPTY("top.empty"),
    FARM_STARTED("farm.started"),
    FARM_WRONG_TARGET("farm.wrong-target"),
    FARM_INCIDENT_STARTED("farm.incident-started"),
    FARM_INCIDENT_PROGRESS("farm.incident-progress"),
    FARM_INCIDENT_RESOLVED("farm.incident-resolved"),
    FARM_GOLDEN_STARTED("farm.golden-started"),
    FARM_GOLDEN_ENDED("farm.golden-ended"),
    FARM_COMPLETED("farm.completed"),
    FARM_ACTIONBAR("farm.actionbar"),
    FARM_INCIDENT_ACTIONBAR("farm.incident-actionbar"),
    LUMBER_STARTED("lumber.started"),
    LUMBER_WRONG_SPECIES("lumber.wrong-species"),
    LUMBER_PROCESSING("lumber.processing"),
    LUMBER_STATION_REQUIRED("lumber.station-required"),
    LUMBER_COMPLETED("lumber.completed"),
    LUMBER_ACTIONBAR_FELLING("lumber.actionbar-felling"),
    LUMBER_ACTIONBAR_PROCESSING("lumber.actionbar-processing"),
    MINE_STARTED("mine.started"),
    MINE_PICKAXE_REQUIRED("mine.pickaxe-required"),
    MINE_REGENERATING("mine.regenerating"),
    MINE_HAZARD_STARTED("mine.hazard-started"),
    MINE_HAZARD_HELP("mine.hazard-help"),
    MINE_HAZARD_PROGRESS("mine.hazard-progress"),
    MINE_HAZARD_RESOLVED("mine.hazard-resolved"),
    MINE_EXTRACTION_STARTED("mine.extraction-started"),
    MINE_EXTRACTION_REQUIRED("mine.extraction-required"),
    MINE_COMPLETED("mine.completed"),
    MINE_ACTIONBAR("mine.actionbar"),
    MINE_JOURNAL_FAILED("mine.journal-failed"),
    SHIFT_WINNER("shift.winner"),
}

class ArcFarmsLocale(
    dataRoot: Path,
    private val settings: () -> ArcFarmsConfig,
) {
    private val russian: Config = ConfigManager.of(dataRoot, "lang/ru.yml")
    private val english: Config = ConfigManager.of(dataRoot, "lang/en.yml")
    private val mini = MiniMessage.miniMessage()

    fun render(
        key: MessageKey,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component = renderPath(key.path, audience, values)

    fun renderPath(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component {
        val config = select(audience)
        val fallback = if (settings().defaultLocale == "en") english else russian
        val raw = config.stringOrNull(path)?.takeIf(String::isNotBlank)
            ?: fallback.stringOrNull(path)?.takeIf(String::isNotBlank)
            ?: path
        val prefixRaw = config.stringOrNull(MessageKey.PREFIX.path)?.takeIf(String::isNotBlank)
            ?: fallback.string(MessageKey.PREFIX.path, "<gray>[ArcFarms]</gray>")
        val builder = TagResolver.builder().resolver(Placeholder.component("prefix", mini.deserialize(prefixRaw)))
        values.forEach { (name, value) -> builder.resolver(Placeholder.component(name, value)) }
        return mini.deserialize(raw, builder.build())
    }

    fun text(value: Any?): Component = Component.text(value?.toString().orEmpty())

    private fun select(audience: CommandSender?): Config {
        if (!settings().useClientLocale || audience !is Player) return if (settings().defaultLocale == "en") english else russian
        return if (audience.locale().language.equals("ru", ignoreCase = true)) russian else english
    }

    companion object {
        fun requiredPaths(settings: ArcFarmsConfig): Set<String> = buildSet {
            addAll(MessageKey.entries.map(MessageKey::path))
            settings.farms.flatMapTo(this) { zone -> zone.orders.map { "order.farm.${it.id}" } }
            settings.mines.mapTo(this) { "route.mine.${it.id}" }
            enumValues<ru.ruscrafting.farms.domain.FarmPhase>().mapTo(this) { "phase.farm.${it.name.lowercase()}" }
            enumValues<ru.ruscrafting.farms.domain.LumberPhase>().mapTo(this) { "phase.lumber.${it.name.lowercase()}" }
            enumValues<ru.ruscrafting.farms.domain.MinePhase>().mapTo(this) { "phase.mine.${it.name.lowercase()}" }
        }

        fun validateFiles(dataRoot: Path, settings: ArcFarmsConfig) {
            val mini = MiniMessage.miniMessage()
            listOf("ru", "en").forEach { language ->
                val config = Config(dataRoot, "lang/$language.yml")
                requiredPaths(settings).forEach { path ->
                    val raw = config.stringOrNull(path)
                    require(!raw.isNullOrBlank()) { "Locale $language is missing $path" }
                    mini.deserialize(raw)
                }
            }
        }
    }
}

package ru.ruscrafting.farms.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmCareType
import java.nio.file.Path

enum class MessageKey(val path: String) {
    PREFIX("prefix"),
    HELP("help"),
    NO_PERMISSION("no-permission"),
    PLAYER_ONLY("player-only"),
    BAD_ACTIVITY("bad-activity"),
    RELOAD_OK("reload-ok"),
    RELOAD_FAILED("reload-failed"),
    ADMIN_EDIT_ENABLED("admin-edit.enabled"),
    ADMIN_EDIT_DISABLED("admin-edit.disabled"),
    ADMIN_EDIT_ACTIVE_SHIFT("admin-edit.active-shift"),
    ADMIN_EDIT_BLOCK_REMOVED("admin-edit.block-removed"),
    ADMIN_INSPECT_ENABLED("admin-inspect.enabled"),
    ADMIN_INSPECT_DISABLED("admin-inspect.disabled"),
    ADMIN_INSPECT_HEADER("admin-inspect.header"),
    ADMIN_INSPECT_BLOCK("admin-inspect.block"),
    ADMIN_INSPECT_FIXED("admin-inspect.fixed-crop"),
    ADMIN_INSPECT_PLOT("admin-inspect.plot"),
    ADMIN_INSPECT_TRACKING("admin-inspect.tracking"),
    ADMIN_INSPECT_NONE("admin-inspect.none"),
    ADMIN_UNMANAGE_WORLD_EDIT_REQUIRED("admin.unmanage-worldedit-required"),
    ADMIN_UNMANAGE_SELECTION_REQUIRED("admin.unmanage-selection-required"),
    ADMIN_UNMANAGE_WRONG_WORLD("admin.unmanage-wrong-world"),
    ADMIN_UNMANAGE_EMPTY("admin.unmanage-empty"),
    ADMIN_UNMANAGE_DONE("admin.unmanage-done"),
    ADMIN_HELP("admin.help"),
    ADMIN_HELP_EDIT("admin.command-help.edit"),
    ADMIN_HELP_INSPECT("admin.command-help.inspect"),
    ADMIN_HELP_POINTS("admin.command-help.points"),
    ADMIN_HELP_UNMANAGE("admin.command-help.unmanage"),
    ADMIN_HELP_NEXT("admin.command-help.next"),
    ADMIN_HELP_FINISH("admin.command-help.finish"),
    ADMIN_POINT_HELP_HEADER("admin.point-help-header"),
    ADMIN_POINT_HELP_ENTRY("admin.point-help-entry"),
    ADMIN_POINT_HELP_FOOTER("admin.point-help-footer"),
    ADMIN_STAGE_HELP_HEADER("admin.stage-help-header"),
    ADMIN_STAGE_HELP_ENTRY("admin.stage-help-entry"),
    ADMIN_EVENT_HELP_HEADER("admin.event-help-header"),
    ADMIN_EVENT_HELP_ENTRY("admin.event-help-entry"),
    ADMIN_ZONE_UNKNOWN("admin.zone-unknown"),
    ADMIN_POINT_OUTSIDE("admin.point-outside"),
    ADMIN_POINT_ON_BED("admin.point-on-bed"),
    ADMIN_POINT_SAVED("admin.point-saved"),
    ADMIN_POINT_CLEARED("admin.point-cleared"),
    ADMIN_POINT_NOT_OVERRIDDEN("admin.point-not-overridden"),
    ADMIN_POINTS_HEADER("admin.points-header"),
    ADMIN_POINTS_ENTRY("admin.points-entry"),
    ADMIN_STAGE_SET("admin.stage-set"),
    ADMIN_STAGE_UNKNOWN("admin.stage-unknown"),
    ADMIN_CARE_UNAVAILABLE("admin.care-unavailable"),
    ADMIN_DEBUG_HELP("admin.debug.help"),
    ADMIN_DEBUG_HEADER("admin.debug.header"),
    ADMIN_DEBUG_SHIFT("admin.debug.shift"),
    ADMIN_DEBUG_PATCH("admin.debug.patch"),
    ADMIN_DEBUG_INCIDENT("admin.debug.incident"),
    ADMIN_DEBUG_CARE("admin.debug.care"),
    ADMIN_DEBUG_DELIVERY("admin.debug.delivery"),
    ADMIN_DEBUG_CONTRACT_SET("admin.debug.contract-set"),
    ADMIN_DEBUG_CONTRACT_UNKNOWN("admin.debug.contract-unknown"),
    ADMIN_DEBUG_SUPPLY_GIVEN("admin.debug.supply-given"),
    ADMIN_DEBUG_INVENTORY_FULL("admin.debug.inventory-full"),
    ADMIN_DEBUG_HIGHLIGHTED("admin.debug.highlighted"),
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
    MENU_CLICK("menu.click"),
    MENU_REMOTE("menu.remote"),
    MENU_WORKDAY_NAME("menu.workday.name"),
    MENU_WORKDAY_LORE("menu.workday.lore"),
    MENU_WORKDAY_LOADING("menu.workday.loading"),
    MENU_WORKDAY_CLICK("menu.workday.click"),
    STATUS_HEADER("status.header"),
    STATUS_ENTRY("status.entry"),
    STATUS_EMPTY("status.empty"),
    STATUS_RELAY("status.relay"),
    STATUS_WORKDAY("status.workday"),
    STATUS_WORKDAY_LOADING("status.workday-loading"),
    TOP_HEADER("top.header"),
    TOP_ENTRY("top.entry"),
    TOP_EMPTY("top.empty"),
    TRAVEL_PREPARING("travel.preparing"),
    TRAVEL_FAILED("travel.failed"),
    TRAVEL_ARRIVED("travel.arrived"),
    FARM_ENTRY_TITLE("farm.entry-title"),
    FARM_ENTRY_SUBTITLE("farm.entry-subtitle"),
    FARM_ENTRY_IDLE("farm.entry-idle"),
    FARM_ENTRY_PREPARATION("farm.entry-preparation"),
    FARM_ENTRY_PLANTING("farm.entry-planting"),
    FARM_ENTRY_HARVESTING("farm.entry-harvesting"),
    FARM_ENTRY_PESTS("farm.entry-pests"),
    FARM_ENTRY_DROUGHT("farm.entry-drought"),
    FARM_ENTRY_DELIVERY("farm.entry-delivery"),
    FARM_STARTED("farm.started"),
    FARM_PATCH_UNAVAILABLE("farm.patch-unavailable"),
    FARM_PATCH_PROTECTED("farm.patch-protected"),
    FARM_PREPARATION_REQUIRED("farm.preparation-required"),
    FARM_PREPARATION_TOOL("farm.preparation-tool"),
    FARM_PREPARATION_PROGRESS("farm.preparation-progress"),
    FARM_PLANTING_REQUIRED("farm.planting-required"),
    FARM_PLANTING_TOOL("farm.planting-tool"),
    FARM_PLANTING_BLOCKED("farm.planting-blocked"),
    FARM_PLANTING_STARTED("farm.planting-started"),
    FARM_PLANTING_STARTED_SUBTITLE("farm.planting-started-subtitle"),
    FARM_PLANTING_PROGRESS("farm.planting-progress"),
    FARM_PREPARATION_COMPLETED("farm.preparation-completed"),
    FARM_PREPARATION_COMPLETED_SUBTITLE("farm.preparation-completed-subtitle"),
    FARM_CARE_STARTED("farm.care-started"),
    FARM_CARE_REQUIRED("farm.care-required"),
    FARM_CARE_PROGRESS("farm.care-progress"),
    FARM_CARE_RESOLVED("farm.care-resolved"),
    FARM_CARE_RESOLVED_SUBTITLE("farm.care-resolved-subtitle"),
    FARM_CARE_TOOL("farm.care-tool"),
    FARM_CARE_ORDER("farm.care-order"),
    FARM_CARE_POLLEN_REQUIRED("farm.care-pollen-required"),
    FARM_CARE_POLLEN_TAKEN("farm.care-pollen-taken"),
    FARM_CARE_ANIMAL_FOLLOWING("farm.care-animal-following"),
    FARM_CARE_ANIMAL_PEN("farm.care-animal-pen"),
    FARM_CARE_SEEDER_NAME("farm.care-seeder-name"),
    FARM_CARE_SEEDER_FOLLOWING("farm.care-seeder-following"),
    FARM_CARE_SEEDER_BLOCKED("farm.care-seeder-blocked"),
    FARM_CARE_SEEDER_PROGRESS("farm.care-seeder-progress"),
    FARM_CARE_SEEDER_RESOLVED("farm.care-seeder-resolved"),
    FARM_CARE_SEEDER_RESOLVED_SUBTITLE("farm.care-seeder-resolved-subtitle"),
    FARM_CARE_DISEASE_SPREAD("farm.care-disease-spread"),
    FARM_WRONG_TARGET("farm.wrong-target"),
    FARM_CROP_ALREADY_COMPLETE("farm.crop-already-complete"),
    FARM_FIXED_CROP_PENDING("farm.fixed-crop-pending"),
    FARM_CROP_COMPLETED("farm.crop-completed"),
    FARM_CROP_COMPLETED_SUBTITLE("farm.crop-completed-subtitle"),
    FARM_HARVEST_MILESTONE("farm.harvest-milestone"),
    FARM_CART_PROGRESS("farm.cart-progress"),
    FARM_CUSTOMER_REMINDER("farm.customer-reminder"),
    FARM_PESTS_REQUIRED("farm.pests-required"),
    FARM_PEST_NAME("farm.pest-name"),
    FARM_PEST_NEST_NAME("farm.pest-nest-name"),
    FARM_PEST_NEST_DAMAGED("farm.pest-nest-damaged"),
    FARM_PEST_NEST_DESTROYED("farm.pest-nest-destroyed"),
    FARM_INCIDENT_STARTED("farm.incident-started"),
    FARM_INCIDENT_STARTED_SUBTITLE("farm.incident-started-subtitle"),
    FARM_INCIDENT_PROGRESS("farm.incident-progress"),
    FARM_INCIDENT_RESOLVED("farm.incident-resolved"),
    FARM_INCIDENT_RESOLVED_SUBTITLE("farm.incident-resolved-subtitle"),
    FARM_DROUGHT_REQUIRED("farm.drought-required"),
    FARM_DROUGHT_STARTED("farm.drought-started"),
    FARM_DROUGHT_STARTED_SUBTITLE("farm.drought-started-subtitle"),
    FARM_DROUGHT_TOOL("farm.drought-tool"),
    FARM_DROUGHT_PROGRESS("farm.drought-progress"),
    FARM_COMPLETED("farm.completed"),
    FARM_COMPLETED_SUBTITLE("farm.completed-subtitle"),
    FARM_REWARD_RECEIVED("farm.reward-received"),
    FARM_REWARD_MISSED("farm.reward-missed"),
    FARM_REWARD_EXPERIENCE("farm.reward-experience"),
    FARM_REWARD_MONEY("farm.reward-money"),
    FARM_REWARD_BUNDLE("farm.reward-bundle"),
    FARM_REWARD_ITEMS("farm.reward-items"),
    FARM_REWARD_SPECIAL("farm.reward-special"),
    FARM_REWARD_OVERFLOW("farm.reward-overflow"),
    FARM_DELIVERY_REQUIRED("farm.delivery-required"),
    FARM_DELIVERY_STARTED("farm.delivery-started"),
    FARM_DELIVERY_STARTED_SUBTITLE("farm.delivery-started-subtitle"),
    FARM_DELIVERY_PICKUP("farm.delivery-pickup"),
    FARM_DELIVERY_PICKED_UP("farm.delivery-picked-up"),
    FARM_DELIVERY_PICKED_UP_SUBTITLE("farm.delivery-picked-up-subtitle"),
    FARM_DELIVERY_RETURNED("farm.delivery-returned"),
    FARM_CRATE_NAME("farm.crate-name"),
    FARM_SUPPLY_TOOL("farm.supply-tool"),
    FARM_SUPPLY_SEEDS("farm.supply-seeds"),
    FARM_SUPPLY_WATER("farm.supply-water"),
    FARM_COOLDOWN_BOSSBAR("farm.cooldown-bossbar"),
    FARM_PREPARATION_BOSSBAR("farm.preparation-bossbar"),
    FARM_PLANTING_BOSSBAR("farm.planting-bossbar"),
    FARM_CARE_BOSSBAR("farm.care-bossbar"),
    FARM_BOSSBAR("farm.bossbar"),
    FARM_INCIDENT_BOSSBAR("farm.incident-bossbar"),
    FARM_DROUGHT_BOSSBAR("farm.drought-bossbar"),
    FARM_DELIVERY_BOSSBAR("farm.delivery-bossbar"),
    FARM_DELIVERY_CARRYING_BOSSBAR("farm.delivery-carrying-bossbar"),
    FARM_SCOREBOARD_TITLE("scoreboard.title"),
    FARM_SCOREBOARD_SECTION_ORDER("scoreboard.section.order"),
    FARM_SCOREBOARD_SECTION_CURRENT("scoreboard.section.current"),
    FARM_SCOREBOARD_SECTION_CROPS("scoreboard.section.crops"),
    FARM_SCOREBOARD_ORDER("scoreboard.order"),
    FARM_SCOREBOARD_OBJECTIVE_LINE("scoreboard.objective-line"),
    FARM_SCOREBOARD_HINT_LINE("scoreboard.hint-line"),
    FARM_SCOREBOARD_PROGRESS("scoreboard.progress"),
    FARM_SCOREBOARD_CROP("scoreboard.crop"),
    FARM_SCOREBOARD_CART("scoreboard.cart"),
    FARM_SCOREBOARD_OBJECTIVE_IDLE("scoreboard.objective.idle"),
    FARM_SCOREBOARD_OBJECTIVE_PREPARATION("scoreboard.objective.preparation"),
    FARM_SCOREBOARD_OBJECTIVE_PLANTING("scoreboard.objective.planting"),
    FARM_SCOREBOARD_OBJECTIVE_CARE("scoreboard.objective.care"),
    FARM_SCOREBOARD_OBJECTIVE_HARVESTING("scoreboard.objective.harvesting"),
    FARM_SCOREBOARD_OBJECTIVE_PESTS("scoreboard.objective.pests"),
    FARM_SCOREBOARD_OBJECTIVE_DROUGHT("scoreboard.objective.drought"),
    FARM_SCOREBOARD_OBJECTIVE_DELIVERY("scoreboard.objective.delivery"),
    FARM_SCOREBOARD_OBJECTIVE_DELIVERY_CARRYING("scoreboard.objective.delivery-carrying"),
    FARM_SCOREBOARD_OBJECTIVE_COOLDOWN("scoreboard.objective.cooldown"),
    FARM_SCOREBOARD_HINT_IDLE("scoreboard.hint.idle"),
    FARM_SCOREBOARD_HINT_PREPARATION("scoreboard.hint.preparation"),
    FARM_SCOREBOARD_HINT_PLANTING("scoreboard.hint.planting"),
    FARM_SCOREBOARD_HINT_CARE_GENERIC("scoreboard.hint.care.generic"),
    FARM_SCOREBOARD_HINT_HARVESTING("scoreboard.hint.harvesting"),
    FARM_SCOREBOARD_HINT_PESTS("scoreboard.hint.pests"),
    FARM_SCOREBOARD_HINT_DROUGHT("scoreboard.hint.drought"),
    FARM_SCOREBOARD_HINT_DELIVERY("scoreboard.hint.delivery"),
    FARM_SCOREBOARD_HINT_DELIVERY_CARRYING("scoreboard.hint.delivery-carrying"),
    FARM_SCOREBOARD_HINT_COOLDOWN("scoreboard.hint.cooldown"),
    LUMBER_STARTED("lumber.started"),
    LUMBER_WRONG_SPECIES("lumber.wrong-species"),
    LUMBER_PROCESSING("lumber.processing"),
    LUMBER_PROCESSING_SUBTITLE("lumber.processing-subtitle"),
    LUMBER_STATION_REQUIRED("lumber.station-required"),
    LUMBER_COMPLETED("lumber.completed"),
    LUMBER_COMPLETED_SUBTITLE("lumber.completed-subtitle"),
    LUMBER_ACTIONBAR_FELLING("lumber.actionbar-felling"),
    LUMBER_ACTIONBAR_PROCESSING("lumber.actionbar-processing"),
    MINE_STARTED("mine.started"),
    MINE_PICKAXE_REQUIRED("mine.pickaxe-required"),
    MINE_REGENERATING("mine.regenerating"),
    MINE_HAZARD_STARTED("mine.hazard-started"),
    MINE_HAZARD_STARTED_SUBTITLE("mine.hazard-started-subtitle"),
    MINE_HAZARD_HELP("mine.hazard-help"),
    MINE_HAZARD_PROGRESS("mine.hazard-progress"),
    MINE_HAZARD_RESOLVED("mine.hazard-resolved"),
    MINE_HAZARD_RESOLVED_SUBTITLE("mine.hazard-resolved-subtitle"),
    MINE_EXTRACTION_STARTED("mine.extraction-started"),
    MINE_EXTRACTION_STARTED_SUBTITLE("mine.extraction-started-subtitle"),
    MINE_EXTRACTION_REQUIRED("mine.extraction-required"),
    MINE_COMPLETED("mine.completed"),
    MINE_COMPLETED_SUBTITLE("mine.completed-subtitle"),
    MINE_ACTIONBAR("mine.actionbar"),
    MINE_JOURNAL_FAILED("mine.journal-failed"),
    SHIFT_WINNER("shift.winner"),
    NETWORK_ACTOR_FALLBACK("network.actor-fallback"),
    NETWORK_FARM_INCIDENT("network.farm-incident"),
    NETWORK_FARM_RESCUED("network.farm-rescued"),
    NETWORK_LUMBER_PROCESSING("network.lumber-processing"),
    NETWORK_MINE_HAZARD("network.mine-hazard"),
    NETWORK_MINE_STABLE("network.mine-stable"),
    NETWORK_MINE_EXTRACTION("network.mine-extraction"),
    NETWORK_ACTIVITY_COMPLETED("network.activity-completed"),
    NETWORK_WORKDAY_STAMP("network.workday-stamp"),
    NETWORK_WORKDAY_COMPLETED("network.workday-completed"),
    NETWORK_WORKDAY_NEXT("network.workday-next"),
    NETWORK_CALL_LOCAL("network.call-local"),
    NETWORK_CALL_REMOTE("network.call-remote"),
    NETWORK_CALL_HOVER("network.call-hover"),
    NETWORK_SEAL_DONE("network.seal-done"),
    NETWORK_SEAL_PENDING("network.seal-pending"),
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
            ?: fallback.string(
                MessageKey.PREFIX.path,
                "<color:#92bed8>Смена</color> <color:#666666>•</color>",
            )
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
            settings.farms.flatMapTo(this) { zone ->
                zone.rewards.randomBundles.entries.map { "reward.bundle.${it.id}" }
            }
            settings.farms.flatMapTo(this) { zone ->
                zone.orders.flatMap { order ->
                    listOf(
                        "story.farm.${order.id}.start",
                        "story.farm.${order.id}.pests",
                        "story.farm.${order.id}.drought",
                        "story.farm.${order.id}.delivery",
                    )
                }
            }
            FarmPointKind.entries.mapTo(this) { "admin.point.${it.name.lowercase()}" }
            FarmPointKind.entries.mapTo(this) { "admin.point-description.${it.name.lowercase()}" }
            add("admin.point-source.override")
            add("admin.point-source.default")
            add("admin-inspect.restore.ready")
            add("admin-inspect.restore.pending")
            listOf("managed", "patch", "drought", "drought-damaged", "pest-nest", "pest-damaged")
                .mapTo(this) { "admin-inspect.tracking-kind.$it" }
            val adminStages = listOf(
                "preparation", "planting", "harvesting", "seeder", "weeds", "irrigation", "pollination", "covers", "scarecrows",
                "animals", "disease", "moles", "pests", "drought", "delivery", "complete", "reset",
            )
            adminStages.mapTo(this) { "admin.stage.$it" }
            adminStages.mapTo(this) { "admin.stage-description.$it" }
            listOf("pests", "drought").mapTo(this) { "admin.event-description.$it" }
            FarmCareType.entries.forEach { type ->
                add("care.${type.name.lowercase()}.name")
                add("care.${type.name.lowercase()}.instruction")
                add("care.${type.name.lowercase()}.entry")
                add("scoreboard.hint.care.${type.name.lowercase()}")
            }
            settings.farms.flatMapTo(this) { zone ->
                zone.orders.map { "customer.${it.customerType.name.lowercase()}.name" }
            }
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

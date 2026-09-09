package ru.ruscrafting.farms.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocalizedMiniMessage
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
    ADMIN_INSPECT_LEGEND("admin-inspect.legend"),
    ADMIN_BLOCKRESET_STARTED("admin.blockreset-started"),
    ADMIN_BLOCKRESET_STATUS("admin.blockreset-status"),
    ADMIN_BLOCKRESET_COMPLETED("admin.blockreset-completed"),
    ADMIN_BLOCKRESET_ACTIVE("admin.blockreset-active"),
    ADMIN_BLOCKRESET_PENDING("admin.blockreset-pending"),
    ADMIN_BLOCKRESET_TOO_LARGE("admin.blockreset-too-large"),
    ADMIN_BLOCKRESET_FAILED("admin.blockreset-failed"),
    ADMIN_BLOCKRESET_IDLE("admin.blockreset-idle"),
    ADMIN_UNMANAGE_WORLD_EDIT_REQUIRED("admin.unmanage-worldedit-required"),
    ADMIN_UNMANAGE_SELECTION_REQUIRED("admin.unmanage-selection-required"),
    ADMIN_UNMANAGE_WRONG_WORLD("admin.unmanage-wrong-world"),
    ADMIN_UNMANAGE_EMPTY("admin.unmanage-empty"),
    ADMIN_UNMANAGE_DONE("admin.unmanage-done"),
    ADMIN_HELP("admin.help"),
    ADMIN_HELP_EDIT("admin.command-help.edit"),
    ADMIN_HELP_INSPECT("admin.command-help.inspect"),
    ADMIN_HELP_BLOCKRESET("admin.command-help.blockreset"),
    ADMIN_HELP_BACKUP("admin.command-help.backup"),
    ADMIN_HELP_POINTS("admin.command-help.points"),
    ADMIN_HELP_UNMANAGE("admin.command-help.unmanage"),
    ADMIN_HELP_NEXT("admin.command-help.next"),
    ADMIN_HELP_FINISH("admin.command-help.finish"),
    ADMIN_HELP_ROUTE("admin.command-help.route"),
    ADMIN_HELP_SHORTCUT("admin.command-help.shortcut"),
    ADMIN_ORDER_CYCLE_STOPPED("admin.order-cycle-stopped"),
    ADMIN_ORDER_CYCLE_STARTED("admin.order-cycle-started"),
    ADMIN_BACKUP_SELECTION_REQUIRED("admin.backup-selection-required"),
    ADMIN_BACKUP_ACTIVE_SHIFT("admin.backup-active-shift"),
    ADMIN_BACKUP_STARTED("admin.backup-started"),
    ADMIN_BACKUP_BUSY("admin.backup-busy"),
    ADMIN_BACKUP_REJECTED("admin.backup-rejected"),
    ADMIN_BACKUP_STATUS("admin.backup-status"),
    ADMIN_BACKUP_IDLE("admin.backup-idle"),
    ADMIN_BACKUP_SAVED("admin.backup-saved"),
    ADMIN_BACKUP_SAFETY_SAVED("admin.backup-safety-saved"),
    ADMIN_BACKUP_RESTORED("admin.backup-restored"),
    ADMIN_BACKUP_REINDEXED("admin.backup-reindexed"),
    ADMIN_BACKUP_LIST_HEADER("admin.backup-list-header"),
    ADMIN_BACKUP_LIST_ENTRY("admin.backup-list-entry"),
    ADMIN_BACKUP_LIST_EMPTY("admin.backup-list-empty"),
    ADMIN_BACKUP_FAILED("admin.backup-failed"),
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
    ADMIN_POINT_RIVAL_WRONG_WORLD("admin.point-rival-wrong-world"),
    ADMIN_POINT_RIVAL_TOO_FAR("admin.point-rival-too-far"),
    ADMIN_POINT_PROCESSING_INVALID("admin.point-processing-invalid"),
    ADMIN_POINT_PROCESSING_SAVED("admin.point-processing-saved"),
    ADMIN_PROCESSING_POINT_REQUIRED("admin.processing-point-required"),
    ADMIN_POINT_SAVED("admin.point-saved"),
    ADMIN_POINT_CLEARED("admin.point-cleared"),
    ADMIN_POINT_NOT_OVERRIDDEN("admin.point-not-overridden"),
    ADMIN_POINTS_HEADER("admin.points-header"),
    ADMIN_POINTS_ENTRY("admin.points-entry"),
    ADMIN_INCIDENT_REJECTED("admin.incident-rejected"),
    ADMIN_GREENHOUSE_FAILED("admin.greenhouse-failed"),
    ADMIN_GREENHOUSE_REASON("admin.greenhouse-reason"),
    ADMIN_GREENHOUSE_PAUSED("admin.greenhouse-paused"),
    ADMIN_STAGE_SET("admin.stage-set"),
    ADMIN_STAGE_UNKNOWN("admin.stage-unknown"),
    ADMIN_ROUTE_STARTED("admin.route-started"),
    ADMIN_ROUTE_PROGRESS("admin.route-progress"),
    ADMIN_ROUTE_SAVED("admin.route-saved"),
    ADMIN_ROUTE_CANCELLED("admin.route-cancelled"),
    ADMIN_ROUTE_CLEARED("admin.route-cleared"),
    ADMIN_ROUTE_STATUS("admin.route-status"),
    ADMIN_ROUTE_MISSING("admin.route-missing"),
    ADMIN_ROUTE_NOT_RECORDING("admin.route-not-recording"),
    ADMIN_ROUTE_WRONG_WORLD("admin.route-wrong-world"),
    ADMIN_ROUTE_START_OUTSIDE("admin.route-start-outside"),
    ADMIN_ROUTE_TOO_SHORT("admin.route-too-short"),
    ADMIN_ROUTE_LIMIT("admin.route-limit"),
    ADMIN_ROUTE_INVALID("admin.route-invalid"),
    ADMIN_ROUTE_INVALID_NAME("admin.route-invalid-name"),
    ADMIN_CARE_UNAVAILABLE("admin.care-unavailable"),
    ADMIN_MOLES_UNAVAILABLE("admin.moles-unavailable"),
    ADMIN_INCIDENT_RECOVERY_PENDING("admin.incident-recovery-pending"),
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
    MENU_UNAVAILABLE("menu.unavailable"),
    MENU_LOCKED("menu.locked"),
    MENU_COMPANIES_NAME("menu.companies.name"),
    MENU_COMPANIES_LORE("menu.companies.lore"),
    MENU_COMPANIES_CLICK("menu.companies.click"),
    MENU_WORKDAY_NAME("menu.workday.name"),
    MENU_WORKDAY_LORE("menu.workday.lore"),
    MENU_WORKDAY_LOADING("menu.workday.loading"),
    MENU_WORKDAY_CLICK("menu.workday.click"),
    COMPANIES_TITLE("companies.title"),
    COMPANIES_BACK_NAME("companies.back.name"),
    COMPANIES_BACK_LORE("companies.back.lore"),
    COMPANIES_BACK_CLICK("companies.back.click"),
    COMPANIES_FARM_NAME("companies.farm.name"),
    COMPANIES_FARM_LORE("companies.farm.lore"),
    COMPANIES_LUMBER_NAME("companies.lumber.name"),
    COMPANIES_LUMBER_LORE("companies.lumber.lore"),
    COMPANIES_MINE_NAME("companies.mine.name"),
    COMPANIES_MINE_LORE("companies.mine.lore"),
    COMPANIES_ORDERS("companies.card.orders"),
    COMPANIES_WORKER_BONUS("companies.card.worker-bonus"),
    COMPANIES_AVAILABLE("companies.card.available"),
    COMPANIES_PROJECTED("companies.card.projected"),
    COMPANIES_SHADOW("companies.card.shadow"),
    COMPANIES_REMOTE("companies.card.remote"),
    COMPANIES_UNAVAILABLE("companies.card.unavailable"),
    COMPANIES_OPEN("companies.card.open"),
    COMPANIES_TRAVEL("companies.card.travel"),
    COMPANY_FARM_TITLE("companies.farm-detail.title"),
    COMPANY_FARM_HEADER_NAME("companies.farm-detail.header.name"),
    COMPANY_FARM_HEADER_LORE("companies.farm-detail.header.lore"),
    COMPANY_REPORT_NAME("companies.farm-detail.report.name"),
    COMPANY_REPORT_ORDERS("companies.farm-detail.report.orders"),
    COMPANY_REPORT_CONTRIBUTORS("companies.farm-detail.report.contributors"),
    COMPANY_REPORT_GROSS("companies.farm-detail.report.gross"),
    COMPANY_REPORT_RETAINED("companies.farm-detail.report.retained"),
    COMPANY_REPORT_PROJECTED("companies.farm-detail.report.projected"),
    COMPANY_REPORT_PERSONAL("companies.farm-detail.report.personal"),
    COMPANY_REPORT_SETTLEMENT("companies.farm-detail.report.settlement"),
    COMPANY_WORKERS_NAME("companies.farm-detail.workers.name"),
    COMPANY_WORKERS_BONUS("companies.farm-detail.workers.bonus"),
    COMPANY_WORKERS_ACCRUED("companies.farm-detail.workers.accrued"),
    COMPANY_WORKERS_PERSONAL_ACCRUED("companies.farm-detail.workers.personal-accrued"),
    COMPANY_WORKERS_PROJECTED("companies.farm-detail.workers.projected"),
    COMPANY_WORKERS_AVAILABLE("companies.farm-detail.workers.available"),
    COMPANY_WORKERS_SETTLEMENT("companies.farm-detail.workers.settlement"),
    COMPANY_WORKERS_CONTRIBUTION("companies.farm-detail.workers.contribution"),
    COMPANY_POLICY_NAME("companies.farm-detail.policy.name"),
    COMPANY_POLICY_OPERATING("companies.farm-detail.policy.operating"),
    COMPANY_POLICY_DIVIDEND("companies.farm-detail.policy.dividend"),
    COMPANY_POLICY_UPKEEP("companies.farm-detail.policy.upkeep"),
    COMPANY_POLICY_OPEN("companies.farm-detail.policy.open"),
    COMPANY_LICENSE_NAME("companies.farm-detail.license.name"),
    COMPANY_LICENSE_ENVELOPE("companies.farm-detail.license.envelope"),
    COMPANY_LICENSE_SETTLED("companies.farm-detail.license.settled"),
    COMPANY_LICENSE_RESERVED("companies.farm-detail.license.reserved"),
    COMPANY_LICENSE_AVAILABLE("companies.farm-detail.license.available"),
    COMPANY_LICENSE_WEEKS("companies.farm-detail.license.weeks"),
    COMPANY_LICENSE_OUTCOME("companies.farm-detail.license.outcome"),
    COMPANY_SHARES_NAME("companies.farm-detail.shares.name"),
    COMPANY_SHARES_LORE("companies.farm-detail.shares.lore"),
    COMPANY_MARKET_NAME("companies.farm-detail.market.name"),
    COMPANY_MARKET_LORE("companies.farm-detail.market.lore"),
    COMPANY_MARKET_STAGE("companies.farm-detail.market.stage"),
    COMPANY_MARKET_ORDERS("companies.farm-detail.market.orders"),
    COMPANY_MARKET_OPEN("companies.farm-detail.market.open"),
    COMPANY_ORDER_PREMIUM("companies.participation.order-premium"),
    COMPANY_ORDER_SHADOW("companies.participation.order-shadow"),
    COMPANY_ORDER_NO_PREMIUM("companies.participation.order-no-premium"),
    COMPANY_PERSONAL_RESULT("companies.participation.personal-result"),
    COMPANY_PERSONAL_SHADOW("companies.participation.personal-shadow"),
    COMPANY_SHARES_CONFIRM_LICENSE("companies.shares.confirm.license"),
    COMPANY_SHARES_CONFIRM_RISK("companies.shares.confirm.risk"),
    COMPANY_LIVE_FUNDING("companies.card.live-funding"),
    COMPANY_LIVE_ACTIVE("companies.card.live-active"),
    COMPANY_LIVE_CANCELLED("companies.card.live-cancelled"),
    COMPANY_LIVE_EXPIRED("companies.card.live-expired"),
    COMPANY_SHARES_LIVE_LORE("companies.farm-detail.shares.live-lore"),
    COMPANY_SHARES_OPEN("companies.farm-detail.shares.open"),
    COMPANY_SHARES_TITLE("companies.shares.title"),
    COMPANY_SHARES_STATUS_NAME("companies.shares.status.name"),
    COMPANY_SHARES_STATUS_PHASE("companies.shares.status.phase"),
    COMPANY_SHARES_STATUS_PROGRESS("companies.shares.status.progress"),
    COMPANY_SHARES_STATUS_PRICE("companies.shares.status.price"),
    COMPANY_SHARES_STATUS_DEADLINE("companies.shares.status.deadline"),
    COMPANY_SHARES_HOLDING_NAME("companies.shares.holding.name"),
    COMPANY_SHARES_HOLDING_AMOUNT("companies.shares.holding.amount"),
    COMPANY_SHARES_HOLDING_LIMIT("companies.shares.holding.limit"),
    COMPANY_SHARES_ACCOUNT_NAME("companies.shares.account.name"),
    COMPANY_SHARES_ACCOUNT_BALANCE("companies.shares.account.balance"),
    COMPANY_SHARES_ACCOUNT_AVAILABLE("companies.shares.account.available"),
    COMPANY_SHARES_ACCOUNT_REVIEW("companies.shares.account.review"),
    COMPANY_SHARES_BUY_NAME("companies.shares.buy.name"),
    COMPANY_SHARES_BUY_COST("companies.shares.buy.cost"),
    COMPANY_SHARES_BUY_EFFECT("companies.shares.buy.effect"),
    COMPANY_SHARES_BUY_CLICK("companies.shares.buy.click"),
    COMPANY_SHARES_BUY_UNAVAILABLE("companies.shares.buy.unavailable"),
    COMPANY_SHARES_WITHDRAW_NAME("companies.shares.withdraw.name"),
    COMPANY_SHARES_WITHDRAW_AMOUNT("companies.shares.withdraw.amount"),
    COMPANY_SHARES_WITHDRAW_CLICK("companies.shares.withdraw.click"),
    COMPANY_SHARES_WITHDRAW_EMPTY("companies.shares.withdraw.empty"),
    COMPANY_SHARES_CONFIRM_TITLE("companies.shares.confirm.title"),
    COMPANY_SHARES_CONFIRM_NAME("companies.shares.confirm.name"),
    COMPANY_SHARES_CONFIRM_COST("companies.shares.confirm.cost"),
    COMPANY_SHARES_CONFIRM_EFFECT("companies.shares.confirm.effect"),
    COMPANY_SHARES_CONFIRM_WARNING("companies.shares.confirm.warning"),
    COMPANY_SHARES_CONFIRM_CLICK("companies.shares.confirm.click"),
    COMPANY_SHARES_PHASE_FUNDING("companies.shares.phase.funding"),
    COMPANY_SHARES_PHASE_ACTIVE("companies.shares.phase.active"),
    COMPANY_SHARES_PHASE_CANCELLED("companies.shares.phase.cancelled"),
    COMPANY_SHARES_PHASE_EXPIRED("companies.shares.phase.expired"),
    COMPANY_INVESTMENT_STARTED("companies.investment.started"),
    COMPANY_INVESTMENT_BUY_SUCCESS("companies.investment.buy-success"),
    COMPANY_INVESTMENT_WITHDRAW_SUCCESS("companies.investment.withdraw-success"),
    COMPANY_INVESTMENT_PROVIDER_REJECTED("companies.investment.provider-rejected"),
    COMPANY_INVESTMENT_MANUAL_REVIEW("companies.investment.manual-review"),
    COMPANY_INVESTMENT_STATE_ERROR("companies.investment.state-error"),
    COMPANY_INVESTMENT_FUNDING_CLOSED("companies.investment.funding-closed"),
    COMPANY_INVESTMENT_OWNER_LIMIT("companies.investment.owner-limit"),
    COMPANY_INVESTMENT_SOLD_OUT("companies.investment.sold-out"),
    COMPANY_INVESTMENT_PENDING("companies.investment.pending"),
    COMPANY_INVESTMENT_NO_CREDIT("companies.investment.no-credit"),
    COMPANY_INVESTMENT_NOT_AVAILABLE("companies.investment.not-available"),
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
    FARM_ENTRY_BIRDS("farm.entry-birds"),
    FARM_ENTRY_ROUTE("farm.entry-route"),
    FARM_ENTRY_PROCESSING("farm.entry-processing"),
    FARM_ENTRY_BARN_FIRE("farm.entry-barn-fire"),
    FARM_ENTRY_FROST("farm.entry-frost"),
    FARM_ENTRY_BOAR_BREAKOUT("farm.entry-boar-breakout"),
    FARM_ENTRY_RIVAL_RAID("farm.entry-rival-raid"),
    FARM_ENTRY_TORNADO("farm.entry-tornado"),
    FARM_ENTRY_HELL_GREENHOUSE("farm.entry-hell-greenhouse"),
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
    FARM_MOLE_NAME("care.moles.mob-name"),
    FARM_MOLE_DISTANCE_FAR("care.moles.distance.far"),
    FARM_MOLE_DISTANCE_CLOSER("care.moles.distance.closer"),
    FARM_MOLE_DISTANCE_VERY_CLOSE("care.moles.distance.very-close"),
    FARM_MOLE_DISTANCE_BOSSBAR("care.moles.distance-bossbar"),
    FARM_CARE_RESOLVED("farm.care-resolved"),
    FARM_CARE_RESOLVED_SUBTITLE("farm.care-resolved-subtitle"),
    FARM_CARE_TOOL("farm.care-tool"),
    FARM_CARE_ORDER("farm.care-order"),
    FARM_CARE_POLLEN_REQUIRED("farm.care-pollen-required"),
    FARM_CARE_POLLEN_TAKEN("farm.care-pollen-taken"),
    FARM_CARE_ANIMAL_FOLLOWING("farm.care-animal-following"),
    FARM_CARE_ANIMAL_PEN("farm.care-animal-pen"),
    FARM_CARE_ANIMAL_ROD("farm.care-animal-rod"),
    FARM_CARE_ANIMAL_FISHING_ROD("farm.care-animal-fishing-rod"),
    FARM_CARE_SEEDER_NAME("farm.care-seeder-name"),
    FARM_CARE_SEEDER_FOLLOWING("farm.care-seeder-following"),
    FARM_CARE_SEEDER_BLOCKED("farm.care-seeder-blocked"),
    FARM_CARE_SEEDER_OCCUPIED("farm.care-seeder-occupied"),
    FARM_CARE_SEEDER_MOUNT_FAILED("farm.care-seeder-mount-failed"),
    FARM_CARE_SEEDER_OUTSIDE("farm.care-seeder-outside"),
    FARM_CARE_SEEDER_PROGRESS("farm.care-seeder-progress"),
    FARM_CARE_SEEDER_PLANTING_STARTED("farm.care-seeder-planting-started"),
    FARM_CARE_SEEDER_PLANTING_STARTED_SUBTITLE("farm.care-seeder-planting-started-subtitle"),
    FARM_CARE_SEEDER_RESOLVED("farm.care-seeder-resolved"),
    FARM_CARE_SEEDER_RESOLVED_SUBTITLE("farm.care-seeder-resolved-subtitle"),
    FARM_CARE_DISEASE_SPREAD("farm.care-disease-spread"),
    FARM_CARE_SCARECROW_PICKED_UP("farm.care-scarecrow-picked-up"),
    FARM_CARE_SCARECROW_PICKED_UP_SUBTITLE("farm.care-scarecrow-picked-up-subtitle"),
    FARM_CARE_SCARECROW_ALREADY_CARRYING("farm.care-scarecrow-already-carrying"),
    FARM_CARE_SCARECROW_ALL_ASSIGNED("farm.care-scarecrow-all-assigned"),
    FARM_CARE_SCARECROW_RETURNED("farm.care-scarecrow-returned"),
    FARM_MOLE_BUILDING("farm.mole-building"),
    FARM_MOLE_ENTERED("farm.mole-entered"),
    FARM_MOLE_ENTERED_SUBTITLE("farm.mole-entered-subtitle"),
    FARM_MOLE_LEFT("farm.mole-left"),
    FARM_MOLE_RECOVERED("farm.mole-recovered"),
    FARM_WRONG_TARGET("farm.wrong-target"),
    FARM_CROP_ALREADY_COMPLETE("farm.crop-already-complete"),
    FARM_FIXED_CROP_PENDING("farm.fixed-crop-pending"),
    FARM_FIELD_RESTORING("farm.field-restoring"),
    FARM_CROP_COMPLETED("farm.crop-completed"),
    FARM_CROP_COMPLETED_SUBTITLE("farm.crop-completed-subtitle"),
    FARM_HARVEST_MILESTONE("farm.harvest-milestone"),
    FARM_CART_PROGRESS("farm.cart-progress"),
    FARM_CUSTOMER_REMINDER("farm.customer-reminder"),
    FARM_CUSTOMER_HOLOGRAM("farm.customer-hologram"),
    FARM_CUSTOMER_EXPLANATION("farm.customer-explanation"),
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
    FARM_BIRDS_REQUIRED("farm.birds-required"),
    FARM_BIRDS_STARTED("farm.birds-started"),
    FARM_BIRDS_STARTED_SUBTITLE("farm.birds-started-subtitle"),
    FARM_BIRDS_PROGRESS("farm.birds-progress"),
    FARM_BIRD_NAME("farm.bird-name"),
    FARM_ROUTE_STARTED("farm.route-started"),
    FARM_ROUTE_STARTED_SUBTITLE("farm.route-started-subtitle"),
    FARM_ROUTE_PROGRESS("farm.route-progress"),
    FARM_ROUTE_OCCUPIED("farm.route-occupied"),
    FARM_ROUTE_MOUNTED("farm.route-mounted"),
    FARM_ROUTE_MOUNTED_SUBTITLE("farm.route-mounted-subtitle"),
    FARM_ROUTE_CORRIDOR("farm.route-corridor"),
    FARM_ROUTE_RETURNED("farm.route-returned"),
    FARM_ROUTE_STALLED("farm.route-stalled"),
    FARM_ROUTE_STALLED_SUBTITLE("farm.route-stalled-subtitle"),
    FARM_ROUTE_PASSENGER_RESCUED("farm.route-passenger-rescued"),
    FARM_ROUTE_PASSENGER_RESCUED_SUBTITLE("farm.route-passenger-rescued-subtitle"),
    FARM_ROUTE_ATTACK("farm.route-attack"),
    FARM_ROUTE_BROKEN("farm.route-broken"),
    FARM_ROUTE_REPAIRED("farm.route-repaired"),
    FARM_ROUTE_GUNNER_MOUNTED("farm.route-gunner-mounted"),
    FARM_ROUTE_GUNNER_OCCUPIED("farm.route-gunner-occupied"),
    FARM_ROUTE_GUNNER_NEEDS_DRIVER("farm.route-gunner-needs-driver"),
    FARM_ROUTE_GUNNER_INVENTORY_FULL("farm.route-gunner-inventory-full"),
    FARM_ROUTE_RIFLE_NAME("farm.route-rifle-name"),
    FARM_ROUTE_RIFLE_LORE("farm.route-rifle-lore"),
    FARM_ROUTE_PORTAL_LABEL("farm.route-portal-label"),
    FARM_ROUTE_PORTAL_CHARGING("farm.route-portal-charging"),
    FARM_ROUTE_PORTAL_JOINED("farm.route-portal-joined"),
    FARM_ROUTE_PORTAL_JOINED_SUBTITLE("farm.route-portal-joined-subtitle"),
    FARM_ROUTE_REQUIRED("farm.route-required"),
    FARM_ROUTE_BOSSBAR("farm.route-bossbar"),
    FARM_PROCESSING_STARTED("farm.processing.started"),
    FARM_PROCESSING_STARTED_SUBTITLE("farm.processing.started-subtitle"),
    FARM_PROCESSING_LOADING_TITLE("farm.processing.loading-title"),
    FARM_PROCESSING_LOADING_SUBTITLE("farm.processing.loading-subtitle"),
    FARM_PROCESSING_OPERATING_TITLE("farm.processing.operating-title"),
    FARM_PROCESSING_OPERATING_SUBTITLE("farm.processing.operating-subtitle"),
    FARM_PROCESSING_PACKING_TITLE("farm.processing.packing-title"),
    FARM_PROCESSING_PACKING_SUBTITLE("farm.processing.packing-subtitle"),
    FARM_PROCESSING_LOADING_HINT("farm.processing.loading-hint"),
    FARM_PROCESSING_OPERATING_HINT("farm.processing.operating-hint"),
    FARM_PROCESSING_PACKING_HINT("farm.processing.packing-hint"),
    FARM_PROCESSING_RAW_PICKED_UP("farm.processing.raw-picked-up"),
    FARM_PROCESSING_RAW_PICKED_UP_SUBTITLE("farm.processing.raw-picked-up-subtitle"),
    FARM_PROCESSING_PRODUCT_PICKED_UP("farm.processing.product-picked-up"),
    FARM_PROCESSING_PRODUCT_PICKED_UP_SUBTITLE("farm.processing.product-picked-up-subtitle"),
    FARM_PROCESSING_ALREADY_CARRYING("farm.processing.already-carrying"),
    FARM_PROCESSING_RETURNED("farm.processing.returned"),
    FARM_PROCESSING_RETURNED_SUBTITLE("farm.processing.returned-subtitle"),
    FARM_PROCESSING_MACHINE_WAITING_LABEL("farm.processing.machine-waiting-label"),
    FARM_PROCESSING_MACHINE_ACTIVE_LABEL("farm.processing.machine-active-label"),
    FARM_PROCESSING_MACHINE_DONE_LABEL("farm.processing.machine-done-label"),
    FARM_PROCESSING_OUTPUT_LABEL("farm.processing.output-label"),
    FARM_PROCESSING_BOSSBAR("farm.processing.bossbar"),
    FARM_BARN_FIRE_STARTED("farm.barn-fire.started"),
    FARM_BARN_FIRE_STARTED_SUBTITLE("farm.barn-fire.started-subtitle"),
    FARM_BARN_FIRE_PROGRESS("farm.barn-fire.progress"),
    FARM_BARN_FIRE_AIM_HINT("farm.barn-fire.aim-hint"),
    FARM_BARN_FIRE_BOSSBAR("farm.barn-fire.bossbar"),
    FARM_FROST_STARTED("farm.frost.started"),
    FARM_FROST_STARTED_SUBTITLE("farm.frost.started-subtitle"),
    FARM_FROST_PROGRESS("farm.frost.progress"),
    FARM_FROST_REQUIRED("farm.frost.required"),
    FARM_FROST_BOSSBAR("farm.frost.bossbar"),
    FARM_FROST_PICKED_UP("farm.frost.picked-up"),
    FARM_FROST_PICKED_UP_SUBTITLE("farm.frost.picked-up-subtitle"),
    FARM_FROST_FUELED("farm.frost.fueled"),
    FARM_FROST_FUELED_SUBTITLE("farm.frost.fueled-subtitle"),
    FARM_FROST_INVENTORY_FULL("farm.frost.inventory-full"),
    FARM_FROST_FIREWOOD_ITEM("farm.frost.firewood-item"),
    FARM_PERK_VENDOR_NAME("farm.perk-vendor-name"),
    FARM_PERK_MENU_TITLE("farm.perk-menu.title"),
    FARM_PERK_BALANCE("farm.perk-menu.balance"),
    FARM_PERK_PRICE("farm.perk-menu.price"),
    FARM_PERK_ACTIVE("farm.perk-menu.active"),
    FARM_PERK_BUY("farm.perk-menu.buy"),
    FARM_PERK_ALREADY_ACTIVE_TITLE("farm.perk-menu.already-active-title"),
    FARM_PERK_ALREADY_ACTIVE("farm.perk-menu.already-active"),
    FARM_PERK_ALREADY_ACTIVE_HINT("farm.perk-menu.already-active-hint"),
    FARM_PERK_NOT_ENOUGH_TITLE("farm.perk-menu.not-enough-title"),
    FARM_PERK_NOT_ENOUGH("farm.perk-menu.not-enough"),
    FARM_PERK_SAVE_FAILED_TITLE("farm.perk-menu.save-failed-title"),
    FARM_PERK_SAVE_FAILED("farm.perk-menu.save-failed"),
    FARM_PERK_PURCHASED("farm.perk-menu.purchased"),
    FARM_SPECIAL_PROGRESS("farm.special-progress"),
    FARM_SPECIAL_RESOLVED("farm.special-resolved"),
    FARM_SPECIAL_RESOLVED_SUBTITLE("farm.special-resolved-subtitle"),
    FARM_GIANT_CROP_STARTED("farm.giant-crop-started"),
    FARM_GIANT_CROP_STARTED_SUBTITLE("farm.giant-crop-started-subtitle"),
    FARM_GIANT_CROP_TOOL("farm.giant-crop-tool"),
    FARM_CHANNELS_STARTED("farm.channels-started"),
    FARM_CHANNELS_STARTED_SUBTITLE("farm.channels-started-subtitle"),
    FARM_CHANNELS_PROGRESS("farm.channels-progress"),
    FARM_CHANNELS_TOOL("farm.channels-tool"),
    FARM_CHANNELS_SHOVEL("farm.channels-shovel"),
    FARM_ACTION_INVENTORY_FULL("farm.action-inventory-full"),
    FARM_BOAR_BREAKOUT_STARTED("farm.boar-breakout.started"),
    FARM_BOAR_BREAKOUT_STARTED_SUBTITLE("farm.boar-breakout.started-subtitle"),
    FARM_BOAR_BREAKOUT_PROGRESS("farm.boar-breakout.progress"),
    FARM_BOAR_BREAKOUT_REQUIRED("farm.boar-breakout.required"),
    FARM_BOAR_BREAKOUT_BOSSBAR("farm.boar-breakout.bossbar"),
    FARM_BOAR_BREAKOUT_SHIELD("farm.boar-breakout.shield"),
    FARM_RIVAL_RAID_STARTED("farm.rival-raid.started"),
    FARM_RIVAL_RAID_STARTED_SUBTITLE("farm.rival-raid.started-subtitle"),
    FARM_RIVAL_RAID_PROGRESS("farm.rival-raid.progress"),
    FARM_RIVAL_RAID_REQUIRED("farm.rival-raid.required"),
    FARM_RIVAL_RAID_BOSSBAR("farm.rival-raid.bossbar"),
    FARM_RIVAL_RAID_GUN("farm.rival-raid.gun"),
    FARM_RIVAL_RAID_GRENADE("farm.rival-raid.grenade"),
    FARM_RIVAL_RAID_MOUNTED("farm.rival-raid.mounted"),
    FARM_RIVAL_RAID_FULL("farm.rival-raid.full"),
    FARM_RIVAL_RAID_WORKER("farm.rival-raid.worker"),
    FARM_RIVAL_RAID_PORTAL_LABEL("farm.rival-raid.portal-label"),
    // Keep the existing locale paths so both activities use the installed countdown copy.
    FARM_ACTIVITY_PORTAL_COUNTDOWN("farm.rival-raid.portal-countdown"),
    FARM_ACTIVITY_PORTAL_COUNTDOWN_SUBTITLE("farm.rival-raid.portal-countdown-subtitle"),
    FARM_TORNADO_RESOLVED("farm.tornado.resolved"),
    FARM_TORNADO_STARTED("farm.tornado.started"),
    FARM_TORNADO_STARTED_SUBTITLE("farm.tornado.started-subtitle"),
    FARM_TORNADO_PROGRESS("farm.tornado.progress"),
    FARM_TORNADO_REQUIRED("farm.tornado.required"),
    FARM_TORNADO_BOSSBAR("farm.tornado.bossbar"),
    FARM_HELL_RIFT_RUNE("farm.hell-greenhouse.rune"),
    FARM_HELL_RIFT_DORMANT("farm.hell-greenhouse.dormant"),
    FARM_HELL_RIFT_SEALED("farm.hell-greenhouse.sealed"),
    FARM_HELL_RIFT_CHARGING("farm.hell-greenhouse.charging"),
    FARM_HELL_GREENHOUSE_STARTED("farm.hell-greenhouse.started"),
    FARM_HELL_GREENHOUSE_STARTED_SUBTITLE("farm.hell-greenhouse.started-subtitle"),
    FARM_HELL_GREENHOUSE_BOSSBAR("farm.hell-greenhouse.bossbar"),
    FARM_HELL_GREENHOUSE_REQUIRED("farm.hell-greenhouse.required"),
    FARM_HELL_GREENHOUSE_PICKED("farm.hell-greenhouse.picked"),
    FARM_HELL_GREENHOUSE_COOLED("farm.hell-greenhouse.cooled"),
    FARM_HELL_GREENHOUSE_TOO_EARLY("farm.hell-greenhouse.too-early"),
    FARM_HELL_GREENHOUSE_HANDS_FULL("farm.hell-greenhouse.hands-full"),
    FARM_HELL_GREENHOUSE_EMPTY_HANDS("farm.hell-greenhouse.empty-hands"),
    FARM_HELL_GREENHOUSE_HOT_BURST("farm.hell-greenhouse.hot-burst"),
    FARM_HELL_GREENHOUSE_EVACUATE("farm.hell-greenhouse.evacuate"),
    FARM_HELL_GREENHOUSE_QUOTA_REQUIRED("farm.hell-greenhouse.quota-required"),
    FARM_HELL_GREENHOUSE_SUCCESS("farm.hell-greenhouse.success"),
    FARM_HELL_GREENHOUSE_PARTIAL("farm.hell-greenhouse.partial"),
    FARM_HELL_GREENHOUSE_TIMEOUT("farm.hell-greenhouse.timeout"),
    FARM_HELL_GREENHOUSE_VAT("farm.hell-greenhouse.vat"),
    FARM_HELL_GREENHOUSE_ENTRANCE("farm.hell-greenhouse.entrance"),
    FARM_HELL_GREENHOUSE_EXIT("farm.hell-greenhouse.exit"),
    FARM_HELL_GREENHOUSE_PEPPER("farm.hell-greenhouse.pepper"),
    FARM_HELL_GREENHOUSE_HEAT_WARNING("farm.hell-greenhouse.heat-warning"),
    FARM_HELL_GREENHOUSE_HEAT_ACTIVE("farm.hell-greenhouse.heat-active"),
    FARM_HELL_GREENHOUSE_LEFT("farm.hell-greenhouse.left"),
    FARM_HELL_GREENHOUSE_RIGHT("farm.hell-greenhouse.right"),
    FARM_NIGHT_SHIFT_STARTED("farm.night-shift-started"),
    FARM_NIGHT_SHIFT_STARTED_SUBTITLE("farm.night-shift-started-subtitle"),
    FARM_NIGHT_PATROL_AVOID("farm.night-patrol-avoid"),
    FARM_MARKET_STARTED("farm.market-started"),
    FARM_MARKET_STARTED_SUBTITLE("farm.market-started-subtitle"),
    FARM_MARKET_REQUIRED("farm.market-required"),
    FARM_MARKET_ACTIVE("farm.market-active"),
    FARM_MARKET_ACCEPTED("farm.market-accepted"),
    FARM_MARKET_ACCEPTED_SUBTITLE("farm.market-accepted-subtitle"),
    FARM_MARKET_DECLINED("farm.market-declined"),
    FARM_MARKET_EXPIRED("farm.market-expired"),
    FARM_MARKET_EXPIRED_SUBTITLE("farm.market-expired-subtitle"),
    FARM_MARKET_CHANGED("farm.market-changed"),
    FARM_MARKET_PROGRESS("farm.market-progress"),
    FARM_MARKET_PENDING_BOSSBAR("farm.market-pending-bossbar"),
    FARM_MARKET_ACTIVE_BOSSBAR("farm.market-active-bossbar"),
    FARM_MARKET_MENU_TITLE("farm.market-menu.title"),
    FARM_MARKET_MENU_ORDER("farm.market-menu.order"),
    FARM_MARKET_MENU_BONUS("farm.market-menu.bonus"),
    FARM_MARKET_MENU_PROGRESS("farm.market-menu.progress"),
    FARM_MARKET_MENU_TIME_LIMIT("farm.market-menu.time-limit"),
    FARM_MARKET_MENU_TIME_REMAINING("farm.market-menu.time-remaining"),
    FARM_MARKET_MENU_ACCEPT("farm.market-menu.accept"),
    FARM_MARKET_MENU_DECLINE("farm.market-menu.decline"),
    FARM_MARKET_MENU_CHOOSE("farm.market-menu.choose"),
    FARM_MARKET_MENU_CLOSE("farm.market-menu.close"),
    FARM_COMPLETED("farm.completed"),
    FARM_COMPLETED_SUBTITLE("farm.completed-subtitle"),
    FARM_REWARD_RECEIVED("farm.reward-received"),
    FARM_REWARD_CHAT("farm.reward-chat"),
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
    FARM_SUPPLY_ARCHERY("farm.supply-archery"),
    FARM_SUPPLY_FIRE("farm.supply-fire"),
    FARM_SUPPLY_FIRE_ITEM_NAME("farm.supply-fire-item-name"),
    FARM_SUPPLY_FIRE_ITEM_LORE("farm.supply-fire-item-lore"),
    FARM_COOLDOWN_BOSSBAR("farm.cooldown-bossbar"),
    FARM_PREPARATION_BOSSBAR("farm.preparation-bossbar"),
    FARM_PLANTING_BOSSBAR("farm.planting-bossbar"),
    FARM_CARE_BOSSBAR("farm.care-bossbar"),
    FARM_BOSSBAR("farm.bossbar"),
    FARM_INCIDENT_BOSSBAR("farm.incident-bossbar"),
    FARM_DROUGHT_BOSSBAR("farm.drought-bossbar"),
    FARM_BIRDS_BOSSBAR("farm.birds-bossbar"),
    FARM_SPECIAL_BOSSBAR("farm.special-bossbar"),
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
    LUMBER_TARGET_REQUIRED("lumber.target-required"),
    LUMBER_JOURNAL_FAILED("lumber.journal-failed"),
    LUMBER_SAW_LEFT("lumber.saw-left"),
    LUMBER_SAW_RIGHT("lumber.saw-right"),
    LUMBER_PLANK_UNAVAILABLE("lumber.plank-unavailable"),
    LUMBER_PLANK_RETURNED("lumber.plank-returned"),
    LUMBER_JAM_SEQUENCE("lumber.jam-sequence"),
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
    MINE_PROSPECT_REQUIRED("mine.prospect-required"),
    MINE_TARGET_REQUIRED("mine.target-required"),
    MINE_RECOVERY_PENDING("mine.recovery-pending"),
    MINE_INDEX_SHORTAGE("mine.index-shortage"),
    MINE_ORE_CRATE("mine.ore-crate"),
    MINE_LOADING_REQUIRED("mine.loading-required"),
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

internal val SCREEN_TITLE_SUBTITLES = mapOf(
    MessageKey.FARM_ENTRY_TITLE to MessageKey.FARM_ENTRY_SUBTITLE,
    MessageKey.FARM_PLANTING_STARTED to MessageKey.FARM_PLANTING_STARTED_SUBTITLE,
    MessageKey.FARM_PREPARATION_COMPLETED to MessageKey.FARM_PREPARATION_COMPLETED_SUBTITLE,
    MessageKey.FARM_CARE_RESOLVED to MessageKey.FARM_CARE_RESOLVED_SUBTITLE,
    MessageKey.FARM_CARE_SEEDER_RESOLVED to MessageKey.FARM_CARE_SEEDER_RESOLVED_SUBTITLE,
    MessageKey.FARM_MOLE_ENTERED to MessageKey.FARM_MOLE_ENTERED_SUBTITLE,
    MessageKey.FARM_INCIDENT_STARTED to MessageKey.FARM_INCIDENT_STARTED_SUBTITLE,
    MessageKey.FARM_INCIDENT_RESOLVED to MessageKey.FARM_INCIDENT_RESOLVED_SUBTITLE,
    MessageKey.FARM_DROUGHT_STARTED to MessageKey.FARM_DROUGHT_STARTED_SUBTITLE,
    MessageKey.FARM_BIRDS_STARTED to MessageKey.FARM_BIRDS_STARTED_SUBTITLE,
    MessageKey.FARM_ROUTE_STARTED to MessageKey.FARM_ROUTE_STARTED_SUBTITLE,
    MessageKey.FARM_ROUTE_MOUNTED to MessageKey.FARM_ROUTE_MOUNTED_SUBTITLE,
    MessageKey.FARM_ROUTE_STALLED to MessageKey.FARM_ROUTE_STALLED_SUBTITLE,
    MessageKey.FARM_ROUTE_PASSENGER_RESCUED to MessageKey.FARM_ROUTE_PASSENGER_RESCUED_SUBTITLE,
    MessageKey.FARM_ROUTE_PORTAL_JOINED to MessageKey.FARM_ROUTE_PORTAL_JOINED_SUBTITLE,
    MessageKey.FARM_PROCESSING_STARTED to MessageKey.FARM_PROCESSING_STARTED_SUBTITLE,
    MessageKey.FARM_PROCESSING_LOADING_TITLE to MessageKey.FARM_PROCESSING_LOADING_SUBTITLE,
    MessageKey.FARM_PROCESSING_OPERATING_TITLE to MessageKey.FARM_PROCESSING_OPERATING_SUBTITLE,
    MessageKey.FARM_PROCESSING_PACKING_TITLE to MessageKey.FARM_PROCESSING_PACKING_SUBTITLE,
    MessageKey.FARM_PROCESSING_RAW_PICKED_UP to MessageKey.FARM_PROCESSING_RAW_PICKED_UP_SUBTITLE,
    MessageKey.FARM_PROCESSING_PRODUCT_PICKED_UP to MessageKey.FARM_PROCESSING_PRODUCT_PICKED_UP_SUBTITLE,
    MessageKey.FARM_PROCESSING_RETURNED to MessageKey.FARM_PROCESSING_RETURNED_SUBTITLE,
    MessageKey.FARM_BARN_FIRE_STARTED to MessageKey.FARM_BARN_FIRE_STARTED_SUBTITLE,
    MessageKey.FARM_FROST_STARTED to MessageKey.FARM_FROST_STARTED_SUBTITLE,
    MessageKey.FARM_FROST_PICKED_UP to MessageKey.FARM_FROST_PICKED_UP_SUBTITLE,
    MessageKey.FARM_FROST_FUELED to MessageKey.FARM_FROST_FUELED_SUBTITLE,
    MessageKey.FARM_SPECIAL_RESOLVED to MessageKey.FARM_SPECIAL_RESOLVED_SUBTITLE,
    MessageKey.FARM_GIANT_CROP_STARTED to MessageKey.FARM_GIANT_CROP_STARTED_SUBTITLE,
    MessageKey.FARM_CHANNELS_STARTED to MessageKey.FARM_CHANNELS_STARTED_SUBTITLE,
    MessageKey.FARM_BOAR_BREAKOUT_STARTED to MessageKey.FARM_BOAR_BREAKOUT_STARTED_SUBTITLE,
    MessageKey.FARM_RIVAL_RAID_STARTED to MessageKey.FARM_RIVAL_RAID_STARTED_SUBTITLE,
    MessageKey.FARM_ACTIVITY_PORTAL_COUNTDOWN to MessageKey.FARM_ACTIVITY_PORTAL_COUNTDOWN_SUBTITLE,
    MessageKey.FARM_TORNADO_STARTED to MessageKey.FARM_TORNADO_STARTED_SUBTITLE,
    MessageKey.FARM_HELL_GREENHOUSE_STARTED to MessageKey.FARM_HELL_GREENHOUSE_STARTED_SUBTITLE,
    MessageKey.FARM_NIGHT_SHIFT_STARTED to MessageKey.FARM_NIGHT_SHIFT_STARTED_SUBTITLE,
    MessageKey.FARM_MARKET_EXPIRED to MessageKey.FARM_MARKET_EXPIRED_SUBTITLE,
    MessageKey.FARM_MARKET_STARTED to MessageKey.FARM_MARKET_STARTED_SUBTITLE,
    MessageKey.FARM_MARKET_ACCEPTED to MessageKey.FARM_MARKET_ACCEPTED_SUBTITLE,
    MessageKey.FARM_DELIVERY_STARTED to MessageKey.FARM_DELIVERY_STARTED_SUBTITLE,
    MessageKey.FARM_DELIVERY_PICKED_UP to MessageKey.FARM_DELIVERY_PICKED_UP_SUBTITLE,
    MessageKey.FARM_CROP_COMPLETED to MessageKey.FARM_CROP_COMPLETED_SUBTITLE,
    MessageKey.FARM_COMPLETED to MessageKey.FARM_COMPLETED_SUBTITLE,
    MessageKey.LUMBER_PROCESSING to MessageKey.LUMBER_PROCESSING_SUBTITLE,
    MessageKey.LUMBER_COMPLETED to MessageKey.LUMBER_COMPLETED_SUBTITLE,
    MessageKey.MINE_HAZARD_STARTED to MessageKey.MINE_HAZARD_STARTED_SUBTITLE,
    MessageKey.MINE_HAZARD_RESOLVED to MessageKey.MINE_HAZARD_RESOLVED_SUBTITLE,
    MessageKey.MINE_EXTRACTION_STARTED to MessageKey.MINE_EXTRACTION_STARTED_SUBTITLE,
    MessageKey.MINE_COMPLETED to MessageKey.MINE_COMPLETED_SUBTITLE,
)

class ArcFarmsLocale(
    private val dataRoot: Path,
    private val settings: () -> ArcFarmsConfig,
) {
    internal class CatalogSnapshot internal constructor(internal val renderer: LocalizedMiniMessage)

    @Volatile
    private var renderer = loadRenderer(loadLocaleConfigs(dataRoot))

    internal fun snapshot(): CatalogSnapshot = CatalogSnapshot(renderer)

    /** Parses new catalogs without publishing them into the live audience. */
    internal fun prepareReload(): CatalogSnapshot =
        CatalogSnapshot(loadRenderer(loadLocaleConfigs(dataRoot)))

    /** Parses and fully validates a candidate locale generation before publication. */
    internal fun prepareReload(candidate: ArcFarmsConfig): CatalogSnapshot {
        val catalogs = loadLocaleConfigs(dataRoot)
        validateCatalogs(catalogs, candidate)
        return CatalogSnapshot(loadRenderer(catalogs))
    }

    internal fun publish(snapshot: CatalogSnapshot) {
        renderer = snapshot.renderer
    }

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
        val active = renderer
        return active.render(path, localeTag(audience), values)
    }

    fun text(value: Any?): Component = renderer.literal(value)

    private fun loadRenderer(configs: Map<String, Config>): LocalizedMiniMessage = LocalizedMiniMessage(
        catalogs = configs.mapValues { (_, config) -> ConfigLocaleCatalog(config) },
        defaultLocale = { settings().defaultLocale },
    )

    private fun localeTag(audience: CommandSender?): String =
        if (settings().useClientLocale && audience is Player) audience.locale().toLanguageTag()
        else settings().defaultLocale

    companion object {
        fun synchronizeFiles(dataRoot: Path) {
            listOf("ru", "en").forEach { language ->
                Config(dataRoot, "lang/$language.yml")
                    .mergeMissingFromBundled("lang/$language.yml")
            }
        }

        fun requiredPaths(settings: ArcFarmsConfig): Set<String> = buildSet {
            addAll(MessageKey.entries.map(MessageKey::path))
            addAll(listOf("seals", "next", "balance", "currency", "farm-points", "active", "effect", "scope", "price", "duration", "hours", "state", "available", "quantity", "delivery", "farm-contribution", "lumber-contribution", "mine-contribution").map { "shop-table.$it" })
            addAll(listOf("bread", "steak", "golden-carrot", "description", "price", "buy", "not-enough", "waiting", "review", "full", "purchased", "delivered").map { "food.$it" })
            addAll(listOf("close", "back", "help", "details", "buy-perk", "buy-food", "back-root", "back-company", "back-shares", "back-participation").map { "dialog.$it" })
            addAll(listOf("main", "market", "enterprise-overview", "enterprise-farm", "enterprise-shares", "enterprise-confirm", "enterprise-participation", "farm-perks").map { "dialog.intro.$it" })
            addAll(listOf(
                "companies.participation.title", "companies.participation.summary-name", "companies.participation.summary",
                "companies.participation.voting", "companies.participation.eligibility", "companies.participation.advisory",
                "companies.participation.quorum", "companies.participation.steady.name", "companies.participation.steady.lore",
                "companies.participation.team.name", "companies.participation.team.lore", "companies.participation.challenge.name",
                "companies.participation.challenge.lore", "companies.participation.candidate", "companies.participation.selected",
                "companies.participation.vote-click", "companies.participation.advise-click", "companies.participation.confirm-title",
                "companies.participation.confirm-name", "companies.participation.confirm-lore", "companies.participation.confirm-click",
                "companies.participation.saved", "companies.participation.failed", "companies.participation.unavailable",
                "companies.participation.project-name", "companies.participation.project-progress", "companies.participation.project-purpose",
                "companies.participation.project-complete", "companies.participation.world-project", "companies.participation.order-premium",
                "companies.participation.order-shadow", "companies.participation.order-no-premium", "companies.participation.personal-result",
                "companies.participation.personal-shadow", "companies.participation.risk",
                "companies.shares.confirm.license", "companies.shares.confirm.risk",
            ))
            ru.ruscrafting.farms.domain.FarmIncidentType.entries.forEach { type ->
                val event = ru.ruscrafting.farms.domain.FarmEventTypeRegistry.definition(type)
                add(event.titlePath)
                add(event.subtitlePath)
                add(event.hintPath)
            }
            settings.farms.flatMapTo(this) { zone -> zone.orders.map { "order.farm.${it.id}" } }
            settings.farms.flatMapTo(this) { zone -> zone.crops.map { "crop.${it.lowercase()}" } }
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
            add("admin.blockreset-phase.scanning")
            add("admin.blockreset-phase.applying")
            listOf("copying", "writing", "reading", "safety_backup", "restoring")
                .mapTo(this) { "admin.backup-phase.$it" }
            listOf("selection_required", "cuboid_required", "wrong_world", "outside_zone", "too_large", "unknown_backup")
                .mapTo(this) { "admin.backup-rejection.$it" }
            add("admin.backup-reason.manual")
            add("admin.backup-reason.pre_restore")
            listOf(
                "managed", "patch", "drought", "drought-damaged", "pest-nest", "pest-damaged",
                "special-target", "special-damaged", "giant-crop", "orchard",
            )
                .mapTo(this) { "admin-inspect.tracking-kind.$it" }
            listOf("no-beds", "search-limit", "height", "unloaded", "region", "journal", "support", "obstruction", "admin-editing", "no-participants")
                .mapTo(this) { "admin.greenhouse-reasons.$it" }
            val adminStages = listOf(
                "preparation", "planting", "harvesting", "seeder", "weeds", "irrigation", "pollination", "covers", "scarecrows",
                "animals", "ditch-animals", "disease", "moles", "apples", "pests", "drought", "birds", "food-delivery", "giant-crop", "channels",
                "night-shift", "market", "processing", "barn-fire", "frost", "tornado", "hell-greenhouse", "delivery", "complete", "reset",
            )
            adminStages.mapTo(this) { "admin.stage.$it" }
            adminStages.mapTo(this) { "admin.stage-description.$it" }
            listOf(
                "pests", "drought", "birds", "food-delivery", "giant-crop", "channels", "night-shift", "market", "processing",
                "barn-fire", "frost", "tornado", "hell-greenhouse",
            )
                .mapTo(this) { "admin.event-description.$it" }
            listOf("giant-crop", "channels", "night-shift", "market", "tornado", "hell-greenhouse").forEach { incident ->
                add("incident.$incident.name")
                add("farm.entry-$incident")
            }
            listOf(
                "pests", "drought", "birds", "food-delivery", "giant-crop", "channels", "night-shift", "market",
                "processing", "barn-fire", "frost", "boar-breakout", "rival-raid", "tornado", "hell-greenhouse",
            ).forEach { incident ->
                add("scoreboard.objective.$incident")
                add("scoreboard.hint.$incident")
            }
            listOf("loading", "operating", "packing").forEach { stage ->
                add("scoreboard.objective.processing-$stage")
                add("scoreboard.hint.processing-$stage")
            }
            listOf(
                "preparation", "planting", "seeder-tilling", "seeder-planting", "channels", "night-shift",
                "market-pending", "processing", "barn-fire", "frost", "tornado", "hell-greenhouse",
            )
                .mapTo(this) { "scoreboard.hint-detail.$it" }
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
            add("lumber.guidance.title")
            add("lumber.guidance.bar")
            enumValues<ru.ruscrafting.farms.domain.LumberPhase>()
                .filterNot { it == ru.ruscrafting.farms.domain.LumberPhase.IDLE }
                .mapTo(this) { "lumber.guidance.${it.name.lowercase()}" }
            enumValues<ru.ruscrafting.farms.domain.LumberIncidentType>()
                .mapTo(this) { "lumber.guidance.${it.name.lowercase()}" }
        }

        fun validateFiles(dataRoot: Path, settings: ArcFarmsConfig) =
            validateCatalogs(loadLocaleConfigs(dataRoot), settings)

        private fun loadLocaleConfigs(dataRoot: Path): Map<String, Config> =
            LOCALES.associateWith { Config(dataRoot, "lang/$it.yml") }

        private fun validateCatalogs(configs: Map<String, Config>, settings: ArcFarmsConfig) {
            val mini = MiniMessage.miniMessage()
            requiredPaths(settings).forEach { path ->
                val placeholders = LOCALES.associateWith { language ->
                    val raw = configs.getValue(language).stringOrNull(path)
                    require(!raw.isNullOrBlank()) { "Locale $language is missing $path" }
                    val component = mini.deserialize(raw)
                    if (path in SCREEN_TITLE_PATHS) {
                        require(!containsLineBreak(raw, component)) {
                            "Locale $language screen title contains a line break at $path; move details into its subtitle"
                        }
                    }
                    val unknownClosings = CLOSING_TAG.findAll(raw)
                        .map { it.groupValues[1] }
                        .filterNot(MINIMESSAGE_CLOSING_TAGS::contains)
                        .toSet()
                    require(unknownClosings.isEmpty()) {
                        "Locale $language has an unsupported closing tag at $path: $unknownClosings"
                    }
                    localePlaceholders(raw).also { found ->
                        require(found.all(ALLOWED_LOCALE_PLACEHOLDERS::contains)) {
                            "Locale $language has an unknown placeholder at $path: ${found - ALLOWED_LOCALE_PLACEHOLDERS}"
                        }
                        val placeholderClosings = CLOSING_TAG.findAll(raw)
                            .map { it.groupValues[1] }
                            .filter(found::contains)
                            .toSet()
                        require(placeholderClosings.isEmpty()) {
                            "Locale $language uses a placeholder as a closing tag at $path: $placeholderClosings"
                        }
                    }
                }
                require(placeholders.values.distinct().size == 1) {
                    "Locale placeholder mismatch at $path: " +
                        placeholders.entries.joinToString { (language, found) -> "$language=$found" }
                }
                EXPECTED_PLACEHOLDERS[path]?.let { expected ->
                    require(placeholders.values.first() == expected) {
                        "Locale placeholders at $path must be $expected, found ${placeholders.values.first()}"
                    }
                }
            }
        }

        private fun localePlaceholders(raw: String): Set<String> = PLACEHOLDER_TAG.findAll(raw)
            .map { it.groupValues[1] }
            .filterNot(MINIMESSAGE_LITERAL_TAGS::contains)
            .toSet()

        private fun containsLineBreak(raw: String, component: Component): Boolean {
            val plain = PlainTextComponentSerializer.plainText().serialize(component)
            return SCREEN_LINE_BREAK_TAG.containsMatchIn(raw) || raw.contains("\\n") || raw.contains("\\r") ||
                raw.any { it == '\n' || it == '\r' || it == '\u240a' || it == '\u240d' } ||
                plain.any { it == '\n' || it == '\r' || it == '\u240a' || it == '\u240d' }
        }

        private val PLACEHOLDER_TAG = Regex("(?<!\\\\)<([a-z][a-z0-9_-]*)>")
        private val CLOSING_TAG = Regex("</([a-z][a-z0-9_-]*)>")
        private val SCREEN_LINE_BREAK_TAG = Regex("<(?:newline|br)>", RegexOption.IGNORE_CASE)
        private val SCREEN_TITLE_PATHS = SCREEN_TITLE_SUBTITLES.keys.mapTo(hashSetOf(), MessageKey::path)
        private val MINIMESSAGE_LITERAL_TAGS = setOf(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white", "bold", "b",
            "italic", "i", "em", "underlined", "u", "strikethrough", "st", "obfuscated", "obf", "reset", "newline", "br",
        )
        private val MINIMESSAGE_CLOSING_TAGS = MINIMESSAGE_LITERAL_TAGS + setOf(
            "color", "click", "hover", "insertion", "font", "keybind", "key", "translatable", "translate", "tr",
            "selector", "score", "nbt", "data", "block", "entity", "storage", "gradient", "transition", "rainbow",
            "shadow_color", "shadow", "pride", "sprite", "head", "fallback", "em",
        )
        private val LOCALES = listOf("ru", "en")
        private val ALLOWED_LOCALE_PLACEHOLDERS = setOf(
            "at", "candidates", "action", "active", "activity", "actor", "alive", "amount", "arguments", "beds", "blocks", "bonus",
            "bundle", "care", "carriers", "cart", "chunks", "command", "count", "crop", "crops", "customer",
            "cycle", "damaged", "data", "description", "distance", "done", "dry", "entities", "event", "expected",
            "farm", "flows", "health", "heat", "hint", "hours", "id", "incident", "instruction", "item", "leaves", "limit",
            "lumber", "material", "mine", "name", "nests", "next", "objective", "order", "orders", "original",
            "percent", "perk", "pests", "phase", "place", "planted", "player", "players", "plots", "point", "points", "remaining",
            "prefix", "price", "progress", "rarity", "reason", "records", "requirements", "restore", "reward", "route",
            "seals", "seconds", "seed", "sequence", "side", "size", "soil", "source", "spawned", "stage", "supply", "targets",
            "reserved", "shares", "issued", "tilled", "time", "tool", "total", "tracking", "type", "water", "wood", "workers", "world", "x", "y", "z",
            "zone", "weeks", "plan", "week", "votes", "target", "date", "contribution",
        )
        private val EXPECTED_PLACEHOLDERS = mapOf(
            "farm.hell-greenhouse.rune" to setOf("point"),
            "farm.hell-greenhouse.dormant" to setOf("point"),
            "farm.hell-greenhouse.sealed" to setOf("point"),
            "farm.hell-greenhouse.charging" to setOf("time"),
            "farm.hell-greenhouse.heat-warning" to setOf("side", "time"),
            "farm.hell-greenhouse.heat-active" to setOf("side"),
            "admin.incident-rejected" to setOf("prefix", "stage"),
            "admin.greenhouse-failed" to setOf("prefix", "zone", "candidates", "total"),
            "admin.greenhouse-reason" to setOf("prefix", "reason", "at", "material", "count"),
            "admin.greenhouse-paused" to setOf("prefix", "reason"),
            "menu.stats.lore" to setOf("farm", "lumber", "mine"),
            "menu.workday.lore" to setOf("cycle", "farm", "lumber", "mine"),
            "menu.workday.click" to setOf("activity"),
            "companies.card.orders" to setOf("orders"),
            "companies.card.worker-bonus" to setOf("percent"),
            "companies.card.available" to setOf("amount"),
            "companies.card.projected" to setOf("amount"),
            "companies.farm-detail.report.orders" to setOf("orders"),
            "companies.farm-detail.report.contributors" to setOf("workers"),
            "companies.farm-detail.report.gross" to setOf("amount"),
            "companies.farm-detail.report.retained" to setOf("amount"),
            "companies.farm-detail.report.projected" to setOf("amount"),
            "companies.farm-detail.report.personal" to setOf("amount"),
            "companies.farm-detail.report.settlement" to setOf("time"),
            "companies.farm-detail.workers.bonus" to setOf("percent"),
            "companies.farm-detail.workers.accrued" to setOf("amount"),
            "companies.farm-detail.workers.personal-accrued" to setOf("amount"),
            "companies.farm-detail.workers.projected" to setOf("amount"),
            "companies.farm-detail.workers.available" to setOf("amount"),
            "companies.farm-detail.workers.settlement" to setOf("time"),
            "companies.farm-detail.workers.contribution" to setOf("orders", "contribution"),
            "companies.farm-detail.policy.operating" to setOf("percent"),
            "companies.farm-detail.policy.dividend" to setOf("percent"),
            "companies.farm-detail.policy.upkeep" to setOf("amount"),
            "companies.farm-detail.license.envelope" to setOf("amount"),
            "companies.farm-detail.license.settled" to setOf("amount"),
            "companies.farm-detail.license.reserved" to setOf("amount"),
            "companies.farm-detail.license.available" to setOf("amount"),
            "companies.farm-detail.license.weeks" to setOf("weeks"),
            "companies.farm-detail.license.outcome" to emptySet(),
            "companies.farm-detail.market.stage" to setOf("stage"),
            "companies.farm-detail.market.orders" to setOf("orders", "target"),
            "companies.participation.summary" to setOf("plan"),
            "companies.participation.voting" to setOf("week"),
            "companies.participation.project-progress" to setOf("stage", "orders", "target"),
            "companies.participation.candidate" to setOf("votes", "workers"),
            "companies.participation.confirm-name" to setOf("plan"),
            "companies.participation.confirm-lore" to setOf("week"),
            "companies.participation.order-premium" to setOf("amount"),
            "companies.participation.order-shadow" to setOf("amount"),
            "companies.participation.order-no-premium" to emptySet(),
            "companies.participation.personal-result" to setOf("amount", "date"),
            "companies.participation.personal-shadow" to emptySet(),
            "companies.card.live-funding" to setOf("issued", "total"),
            "companies.card.live-active" to setOf("amount"),
            "companies.shares.status.phase" to setOf("phase"),
            "companies.shares.status.progress" to setOf("issued", "total", "reserved"),
            "companies.shares.status.price" to setOf("amount"),
            "companies.shares.status.deadline" to setOf("hours"),
            "companies.shares.holding.amount" to setOf("shares", "total"),
            "companies.shares.holding.limit" to setOf("limit"),
            "companies.shares.account.balance" to setOf("amount"),
            "companies.shares.account.available" to setOf("amount"),
            "companies.shares.account.review" to setOf("count"),
            "companies.shares.buy.name" to setOf("shares"),
            "companies.shares.buy.cost" to setOf("amount"),
            "companies.shares.buy.effect" to setOf("shares", "limit"),
            "companies.shares.withdraw.amount" to setOf("amount"),
            "companies.shares.confirm.name" to setOf("shares"),
            "companies.shares.confirm.cost" to setOf("amount"),
            "companies.shares.confirm.effect" to setOf("shares", "limit"),
            "companies.shares.confirm.license" to setOf("percent"),
            "companies.shares.confirm.risk" to emptySet(),
            "farm.perk-menu.price" to setOf("price", "hours"),
            "farm.perk-menu.active" to setOf("hours"),
            "farm.perk-menu.not-enough" to setOf("price"),
            "farm.market-menu.order" to setOf("crop", "amount"),
            "farm.market-menu.bonus" to setOf("bonus"),
            "farm.market-menu.progress" to setOf("done", "total"),
            "farm.market-menu.time-limit" to setOf("time"),
            "farm.market-menu.time-remaining" to setOf("time"),
        )
    }
}

package ru.ruscrafting.farms.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPointKind

class ArcFarmsCommand(
    private val service: ArcFarmsService,
    private val locale: ArcFarmsLocale,
    private val menu: ArcFarmsMenu,
    private val reload: () -> Result<Unit>,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            val player = sender as? Player
            if (player == null) sender.sendMessage(locale.render(MessageKey.HELP, sender)) else menu.open(player)
            return true
        }
        when (args[0].lowercase()) {
            "status" -> sendStatus(sender)
            "top" -> sendTop(sender, args.getOrNull(1))
            "travel" -> travel(sender, args.getOrNull(1))
            "reload" -> reload(sender)
            "admin" -> admin(sender, args.drop(1))
            "debug" -> debug(sender, args.drop(1))
            else -> sender.sendMessage(locale.render(MessageKey.HELP, sender))
        }
        return true
    }

    private fun travel(sender: CommandSender, rawKind: String?) {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(locale.render(MessageKey.PLAYER_ONLY, sender))
            return
        }
        val kind = parseKind(rawKind)
        if (kind == null) {
            sender.sendMessage(locale.render(MessageKey.BAD_ACTIVITY, sender))
            return
        }
        service.travel(player, kind)
    }

    private fun sendStatus(sender: CommandSender) {
        val statuses = service.statuses()
        sender.sendMessage(locale.render(MessageKey.STATUS_HEADER, sender))
        val workday = service.workday()
        if (workday == null) {
            sender.sendMessage(locale.render(MessageKey.STATUS_WORKDAY_LOADING, sender))
        } else {
            sender.sendMessage(
                locale.render(
                    MessageKey.STATUS_WORKDAY,
                    sender,
                    mapOf(
                        "cycle" to locale.text(workday.cycle),
                        "done" to locale.text(workday.completed.size),
                        "total" to locale.text(ActivityKind.entries.size),
                        "activity" to activityName(workday.recommended(), sender),
                    ),
                ),
            )
        }
        if (statuses.isEmpty()) {
            val key = if (ActivityKind.entries.any(service::canNavigate)) MessageKey.STATUS_RELAY else MessageKey.STATUS_EMPTY
            sender.sendMessage(locale.render(key, sender))
            return
        }
        statuses.forEach { status ->
            sender.sendMessage(
                locale.render(
                    MessageKey.STATUS_ENTRY,
                    sender,
                    mapOf(
                        "name" to if (status.kind == ActivityKind.MINE) {
                            locale.renderPath("route.mine.${status.id}", sender)
                        } else {
                            activityName(status.kind, sender)
                        },
                        "phase" to locale.renderPath(status.phasePath, sender),
                        "progress" to locale.text(status.progress),
                    ),
                ),
            )
        }
    }

    private fun sendTop(sender: CommandSender, rawKind: String?) {
        val kind = parseKind(rawKind)
        if (kind == null) {
            sender.sendMessage(locale.render(MessageKey.BAD_ACTIVITY, sender))
            return
        }
        val entries = service.leaderboard(kind)
        sender.sendMessage(
            locale.render(MessageKey.TOP_HEADER, sender, mapOf("activity" to activityName(kind, sender))),
        )
        if (entries.isEmpty()) {
            sender.sendMessage(locale.render(MessageKey.TOP_EMPTY, sender))
            return
        }
        entries.forEachIndexed { index, (playerId, amount) ->
            val playerName = Bukkit.getOfflinePlayer(playerId).name ?: playerId.toString().take(8)
            sender.sendMessage(
                locale.render(
                    MessageKey.TOP_ENTRY,
                    sender,
                    mapOf(
                        "place" to locale.text(index + 1),
                        "player" to Component.text(playerName),
                        "amount" to locale.text(amount),
                    ),
                ),
            )
        }
    }

    private fun reload(sender: CommandSender) {
        if (!sender.hasPermission("arcfarms.admin")) {
            sender.sendMessage(locale.render(MessageKey.NO_PERMISSION, sender))
            return
        }
        reload().fold(
            onSuccess = { sender.sendMessage(locale.render(MessageKey.RELOAD_OK, sender)) },
            onFailure = { failure ->
                sender.sendMessage(
                    locale.render(
                        MessageKey.RELOAD_FAILED,
                        sender,
                        mapOf("reason" to locale.text(failure.message ?: "unknown")),
                    ),
                )
            },
        )
    }

    private fun admin(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("arcfarms.admin")) {
            sender.sendMessage(locale.render(MessageKey.NO_PERMISSION, sender))
            return
        }
        val action = args.firstOrNull()?.lowercase()
        if (action == null || action == "help") {
            sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
            return
        }
        if (args.requestsHelp()) {
            val zone = args.getOrNull(1)?.takeUnless { it.equals("help", true) }
            when (action) {
                "edit" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_EDIT, sender))
                "point" -> sendPointHelp(sender, zone)
                "points" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_POINTS, sender))
                "unmanage" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_UNMANAGE, sender))
                "stage" -> sendStageHelp(sender, zone)
                "next" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_NEXT, sender))
                "finish" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_FINISH, sender))
                "event" -> sendEventHelp(sender, zone)
                else -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
            }
            return
        }
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(locale.render(MessageKey.PLAYER_ONLY, sender))
            return
        }
        when (action) {
            "edit" -> service.toggleAdminEdit(player)
            "point" -> {
                val zone = args.getOrNull(1)
                val point = args.getOrNull(2)?.let(::parsePoint)
                if (zone == null || point == null) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
                    return
                }
                service.adminSetFarmPoint(player, zone, point)
            }
            "points" -> {
                val zone = args.getOrNull(1)
                if (zone == null || !sendFarmPoints(sender, zone)) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
                }
            }
            "unmanage" -> {
                val zone = args.getOrNull(1)
                if (zone == null) sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
                else service.adminUnmanageSelection(player, zone)
            }
            "stage" -> {
                val zone = args.getOrNull(1)
                val stage = args.getOrNull(2)
                if (zone == null || stage == null) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
                    return
                }
                service.adminSetFarmStage(player, zone, stage)
            }
            "next" -> {
                val zone = args.getOrNull(1)
                if (zone == null) sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
                else service.adminAdvanceFarm(player, zone)
            }
            "finish" -> {
                val zone = args.getOrNull(1)
                if (zone == null) sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
                else service.adminSetFarmStage(player, zone, "complete")
            }
            "event" -> {
                val zone = args.getOrNull(1)
                val event = args.getOrNull(2)?.lowercase()
                if (zone == null || event !in EVENT_STAGES) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
                    return
                }
                service.adminSetFarmStage(player, zone, requireNotNull(event))
            }
            else -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
        }
    }

    private fun debug(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("arcfarms.admin")) {
            sender.sendMessage(locale.render(MessageKey.NO_PERMISSION, sender))
            return
        }
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(locale.render(MessageKey.PLAYER_ONLY, sender))
            return
        }
        val zone = args.firstOrNull()
        if (zone == null) {
            sender.sendMessage(locale.render(MessageKey.ADMIN_DEBUG_HELP, sender))
            return
        }
        when (args.getOrNull(1)?.lowercase() ?: "status") {
            "status" -> service.adminDebugFarmStatus(player, zone)
            "points" -> if (!sendFarmPoints(sender, zone)) {
                sender.sendMessage(locale.render(MessageKey.ADMIN_ZONE_UNKNOWN, sender, mapOf("zone" to locale.text(zone))))
            }
            "show", "markers" -> service.adminShowFarmGuidance(player, zone)
            "next", "resolve" -> service.adminAdvanceFarm(player, zone)
            "finish" -> service.adminSetFarmStage(player, zone, "complete")
            "reset" -> service.adminSetFarmStage(player, zone, "reset")
            "contract" -> {
                val orderId = args.getOrNull(2)
                if (orderId == null) sender.sendMessage(locale.render(MessageKey.ADMIN_DEBUG_HELP, sender))
                else service.adminSetFarmContract(player, zone, orderId)
            }
            "stage" -> {
                val stage = args.getOrNull(2)
                if (stage == null) sender.sendMessage(locale.render(MessageKey.ADMIN_DEBUG_HELP, sender))
                else service.adminSetFarmStage(player, zone, stage)
            }
            "event" -> {
                val event = args.getOrNull(2)?.lowercase()
                if (event !in EVENT_STAGES) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_DEBUG_HELP, sender))
                } else {
                    service.adminSetFarmStage(player, zone, requireNotNull(event))
                }
            }
            "give" -> {
                val kind = args.getOrNull(2)?.lowercase()
                if (kind !in setOf("tool", "seeds", "water")) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_DEBUG_HELP, sender))
                } else {
                    service.adminGiveFarmSupply(player, zone, requireNotNull(kind))
                }
            }
            else -> sender.sendMessage(locale.render(MessageKey.ADMIN_DEBUG_HELP, sender))
        }
    }

    private fun sendFarmPoints(sender: CommandSender, zone: String): Boolean {
        val points = service.adminFarmPoints(zone) ?: return false
        sender.sendMessage(locale.render(MessageKey.ADMIN_POINTS_HEADER, sender, mapOf("zone" to locale.text(zone))))
        points.forEach { (kind, point) ->
            sender.sendMessage(
                locale.render(
                    MessageKey.ADMIN_POINTS_ENTRY,
                    sender,
                    mapOf(
                        "point" to locale.renderPath("admin.point.${kind.name.lowercase()}", sender),
                        "world" to locale.text(point.world),
                        "x" to locale.text("%.2f".format(java.util.Locale.ROOT, point.x)),
                        "y" to locale.text("%.2f".format(java.util.Locale.ROOT, point.y)),
                        "z" to locale.text("%.2f".format(java.util.Locale.ROOT, point.z)),
                    ),
                ),
            )
        }
        return true
    }

    private fun sendPointHelp(sender: CommandSender, zone: String?) {
        sender.sendMessage(
            locale.render(
                MessageKey.ADMIN_POINT_HELP_HEADER,
                sender,
                mapOf("zone" to locale.text(zone ?: "…")),
            ),
        )
        FarmPointKind.entries.forEach { kind ->
            sender.sendMessage(
                locale.render(
                    MessageKey.ADMIN_POINT_HELP_ENTRY,
                    sender,
                    mapOf(
                        "id" to locale.text(pointArgument(kind)),
                        "point" to locale.renderPath("admin.point.${kind.name.lowercase()}", sender),
                        "description" to locale.renderPath("admin.point-description.${kind.name.lowercase()}", sender),
                    ),
                ),
            )
        }
        sender.sendMessage(locale.render(MessageKey.ADMIN_POINT_HELP_FOOTER, sender))
    }

    private fun sendStageHelp(sender: CommandSender, zone: String?) {
        sender.sendMessage(
            locale.render(
                MessageKey.ADMIN_STAGE_HELP_HEADER,
                sender,
                mapOf("zone" to locale.text(zone ?: "…")),
            ),
        )
        STAGE_STAGES.forEach { stage ->
            sender.sendMessage(
                locale.render(
                    MessageKey.ADMIN_STAGE_HELP_ENTRY,
                    sender,
                    mapOf(
                        "id" to locale.text(stage),
                        "stage" to locale.renderPath("admin.stage.$stage", sender),
                        "description" to locale.renderPath("admin.stage-description.$stage", sender),
                    ),
                ),
            )
        }
    }

    private fun sendEventHelp(sender: CommandSender, zone: String?) {
        sender.sendMessage(
            locale.render(
                MessageKey.ADMIN_EVENT_HELP_HEADER,
                sender,
                mapOf("zone" to locale.text(zone ?: "…")),
            ),
        )
        EVENT_STAGES.forEach { event ->
            val careType = CARE_EVENT_TYPES[event]
            val description = if (careType == null) {
                locale.renderPath("admin.event-description.$event", sender)
            } else {
                locale.renderPath("care.${careType.name.lowercase()}.instruction", sender)
            }
            sender.sendMessage(
                locale.render(
                    MessageKey.ADMIN_EVENT_HELP_ENTRY,
                    sender,
                    mapOf(
                        "id" to locale.text(event),
                        "event" to locale.renderPath("admin.stage.$event", sender),
                        "description" to description,
                    ),
                ),
            )
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.firstOrNull()?.lowercase() in setOf("admin", "debug") && !sender.hasPermission("arcfarms.admin")) {
            return emptyList()
        }
        return when (args.size) {
            1 -> buildList {
                add("status")
                add("top")
                add("travel")
                if (sender.hasPermission("arcfarms.admin")) {
                    add("reload")
                    add("admin")
                    add("debug")
                }
            }.filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when {
                args[0].equals("top", true) || args[0].equals("travel", true) ->
                    listOf("farm", "lumber", "mine").filter { it.startsWith(args[1], true) }
                args[0].equals("admin", true) && sender.hasPermission("arcfarms.admin") ->
                    listOf("help", "edit", "point", "points", "unmanage", "stage", "next", "finish", "event")
                        .filter { it.startsWith(args[1], true) }
                args[0].equals("debug", true) && sender.hasPermission("arcfarms.admin") ->
                    service.farmZoneIds().filter { it.startsWith(args[1], true) }
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("admin", true) && args[1].equals("edit", true) ->
                    listOf("help").filter { it.startsWith(args[2], true) }
                args[0].equals("admin", true) && args[1].lowercase() in
                    setOf("point", "points", "unmanage", "stage", "next", "finish", "event") ->
                    (service.farmZoneIds() + "help").filter { it.startsWith(args[2], true) }
                args[0].equals("debug", true) && sender.hasPermission("arcfarms.admin") ->
                    listOf("status", "contract", "stage", "next", "finish", "event", "give", "show", "points", "reset")
                        .filter { it.startsWith(args[2], true) }
                else -> emptyList()
            }
            4 -> when {
                args[0].equals("admin", true) && args[1].equals("point", true) ->
                    (POINT_ARGUMENTS + "help")
                        .filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].equals("stage", true) ->
                    (STAGE_STAGES + "help")
                        .filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].equals("event", true) ->
                    (EVENT_STAGES + "help").filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].lowercase() in
                    setOf("points", "unmanage", "next", "finish") ->
                    listOf("help").filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("stage", true) ->
                    (listOf("preparation", "planting", "harvesting") + CARE_STAGES + listOf("pests", "drought", "delivery", "complete", "reset"))
                        .filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("event", true) ->
                    EVENT_STAGES.filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("give", true) ->
                    listOf("tool", "seeds", "water").filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("contract", true) ->
                    service.farmOrderIds(args[1]).filter { it.startsWith(args[3], true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun parsePoint(raw: String): FarmPointKind? = when (raw.lowercase()) {
        "tool" -> FarmPointKind.TOOL
        "seeds" -> FarmPointKind.SEEDS
        "water" -> FarmPointKind.WATER
        "crates" -> FarmPointKind.CRATES
        "receiving" -> FarmPointKind.RECEIVING
        "cart" -> FarmPointKind.CART
        "customer" -> FarmPointKind.CUSTOMER
        "travel" -> FarmPointKind.TRAVEL
        "hive" -> FarmPointKind.HIVE
        "irrigation" -> FarmPointKind.IRRIGATION
        "covers" -> FarmPointKind.COVERS
        "scarecrows" -> FarmPointKind.SCARECROWS
        "barn", "pen" -> FarmPointKind.PEN
        else -> null
    }

    private fun pointArgument(kind: FarmPointKind): String = if (kind == FarmPointKind.PEN) "barn" else kind.name.lowercase()

    private fun List<String>.requestsHelp(): Boolean = drop(1).any { it.equals("help", ignoreCase = true) }

    private fun parseKind(raw: String?): ActivityKind? = when (raw?.lowercase()) {
        "farm" -> ActivityKind.FARM
        "lumber" -> ActivityKind.LUMBER
        "mine" -> ActivityKind.MINE
        else -> null
    }

    private fun activityName(kind: ActivityKind, sender: CommandSender): Component = locale.render(
        when (kind) {
            ActivityKind.FARM -> MessageKey.MENU_FARM_NAME
            ActivityKind.LUMBER -> MessageKey.MENU_LUMBER_NAME
            ActivityKind.MINE -> MessageKey.MENU_MINE_NAME
        },
        sender,
    )

    companion object {
        private val CARE_EVENT_TYPES = linkedMapOf(
            "seeder" to FarmCareType.SEEDER,
            "weeds" to FarmCareType.WEEDS,
            "irrigation" to FarmCareType.IRRIGATION,
            "pollination" to FarmCareType.POLLINATION,
            "covers" to FarmCareType.STORM_COVERS,
            "scarecrows" to FarmCareType.SCARECROWS,
            "animals" to FarmCareType.ANIMAL_RESCUE,
            "disease" to FarmCareType.DISEASE,
            "moles" to FarmCareType.MOLES,
        )
        private val CARE_STAGES = CARE_EVENT_TYPES.keys.toList()
        private val EVENT_STAGES = CARE_STAGES + listOf("pests", "drought")
        private val STAGE_STAGES = listOf("preparation", "planting", "harvesting") +
            EVENT_STAGES + listOf("delivery", "complete", "reset")
        private val POINT_ARGUMENTS = listOf(
            "tool", "seeds", "water", "crates", "receiving", "cart", "customer", "travel", "hive", "irrigation",
            "covers", "scarecrows", "barn",
        )
    }
}

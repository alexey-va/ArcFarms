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
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(locale.render(MessageKey.PLAYER_ONLY, sender))
            return
        }
        when (args.firstOrNull()?.lowercase()) {
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
            "event" -> {
                val zone = args.getOrNull(1)
                val event = args.getOrNull(2)?.lowercase()
                if (zone == null || event !in setOf("pests", "drought")) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
                    return
                }
                service.adminSetFarmStage(player, zone, requireNotNull(event))
            }
            "care" -> {
                val zone = args.getOrNull(1)
                val care = args.getOrNull(2)?.lowercase()
                if (zone == null || care !in CARE_STAGES) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
                    return
                }
                service.adminSetFarmStage(player, zone, requireNotNull(care))
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
            "reset" -> service.adminSetFarmStage(player, zone, "reset")
            "stage" -> {
                val stage = args.getOrNull(2)
                if (stage == null) sender.sendMessage(locale.render(MessageKey.ADMIN_DEBUG_HELP, sender))
                else service.adminSetFarmStage(player, zone, stage)
            }
            "event" -> {
                val event = args.getOrNull(2)?.lowercase()
                if (event !in setOf("pests", "drought")) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_DEBUG_HELP, sender))
                } else {
                    service.adminSetFarmStage(player, zone, requireNotNull(event))
                }
            }
            "care" -> {
                val care = args.getOrNull(2)?.lowercase()
                if (care !in CARE_STAGES) sender.sendMessage(locale.render(MessageKey.ADMIN_DEBUG_HELP, sender))
                else service.adminSetFarmStage(player, zone, requireNotNull(care))
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

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        when (args.size) {
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
                    listOf("edit", "point", "points", "stage", "next", "event", "care").filter { it.startsWith(args[1], true) }
                args[0].equals("debug", true) && sender.hasPermission("arcfarms.admin") ->
                    service.farmZoneIds().filter { it.startsWith(args[1], true) }
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("admin", true) && args[1].lowercase() in setOf("point", "points", "stage", "next", "event", "care") ->
                    service.farmZoneIds().filter { it.startsWith(args[2], true) }
                args[0].equals("debug", true) && sender.hasPermission("arcfarms.admin") ->
                    listOf("status", "stage", "next", "event", "care", "give", "show", "points", "reset")
                        .filter { it.startsWith(args[2], true) }
                else -> emptyList()
            }
            4 -> when {
                args[0].equals("admin", true) && args[1].equals("point", true) ->
                    listOf("tool", "seeds", "water", "crates", "receiving", "travel", "hive", "irrigation", "covers", "scarecrows", "barn")
                        .filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].equals("stage", true) ->
                    (listOf("preparation", "planting", "harvesting") + CARE_STAGES + listOf("pests", "drought", "delivery", "complete", "reset"))
                        .filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].equals("event", true) ->
                    listOf("pests", "drought").filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].equals("care", true) ->
                    CARE_STAGES.filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("stage", true) ->
                    (listOf("preparation", "planting", "harvesting") + CARE_STAGES + listOf("pests", "drought", "delivery", "complete", "reset"))
                        .filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("event", true) ->
                    listOf("pests", "drought").filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("care", true) ->
                    CARE_STAGES.filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("give", true) ->
                    listOf("tool", "seeds", "water").filter { it.startsWith(args[3], true) }
                else -> emptyList()
            }
            else -> emptyList()
        }

    private fun parsePoint(raw: String): FarmPointKind? = when (raw.lowercase()) {
        "tool" -> FarmPointKind.TOOL
        "seeds" -> FarmPointKind.SEEDS
        "water" -> FarmPointKind.WATER
        "crates" -> FarmPointKind.CRATES
        "receiving" -> FarmPointKind.RECEIVING
        "travel" -> FarmPointKind.TRAVEL
        "hive" -> FarmPointKind.HIVE
        "irrigation" -> FarmPointKind.IRRIGATION
        "covers" -> FarmPointKind.COVERS
        "scarecrows" -> FarmPointKind.SCARECROWS
        "barn", "pen" -> FarmPointKind.PEN
        else -> null
    }

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
        private val CARE_STAGES = listOf(
            "seeder", "weeds", "irrigation", "pollination", "covers", "scarecrows", "animals", "disease", "moles",
        )
    }
}

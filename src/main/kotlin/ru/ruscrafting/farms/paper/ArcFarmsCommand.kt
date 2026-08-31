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
        return try {
            execute(sender, args)
        } catch (failure: Exception) {
            service.reportCommandFailure(sender, label, failure)
            true
        }
    }

    private fun execute(sender: CommandSender, args: Array<out String>): Boolean {
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
                "inspect" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_INSPECT, sender))
                "point" -> sendPointHelp(sender, zone)
                "points" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_POINTS, sender))
                "unmanage" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_UNMANAGE, sender))
                "blockreset" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_BLOCKRESET, sender))
                "backup" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_BACKUP, sender))
                "stage" -> sendStageHelp(sender, zone)
                "next" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_NEXT, sender))
                "finish" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_FINISH, sender))
                "event" -> sendEventHelp(sender, zone)
                "route" -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_ROUTE, sender))
                "worksite" -> sendWorksiteAdminHelp(sender)
                in ADMIN_SHORTCUTS -> sendShortcutHelp(sender, action)
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
            "inspect" -> service.toggleAdminInspect(player)
            "point" -> {
                val zone = args.getOrNull(1)
                val point = args.getOrNull(2)?.let(::parsePoint)
                if (zone == null || point == null) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_HELP, sender))
                    return
                }
                if (args.size > 4) {
                    sendPointHelp(sender, zone)
                    return
                }
                when (args.getOrNull(3)?.lowercase()) {
                    null -> service.adminSetFarmPoint(player, zone, point)
                    "clear", "remove" -> service.adminClearFarmPoint(player, zone, point)
                    else -> sendPointHelp(sender, zone)
                }
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
            "blockreset" -> {
                val zone = args.getOrNull(1)
                if (zone == null) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_BLOCKRESET, sender))
                } else {
                    when (args.getOrNull(2)?.lowercase()) {
                        null -> service.adminStartFarmBlockReset(player, zone)
                        "status" -> service.adminFarmBlockResetStatus(player, zone)
                        else -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_BLOCKRESET, sender))
                    }
                }
            }
            "backup" -> {
                val zone = args.getOrNull(1)
                if (zone == null) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_BACKUP, sender))
                } else {
                    when (args.getOrNull(2)?.lowercase()) {
                        "save" -> service.adminSaveFarmBackup(player, zone)
                        "list" -> service.adminListFarmBackups(player, zone)
                        "status" -> service.adminFarmBackupStatus(player, zone)
                        "restore" -> args.getOrNull(3)?.let { service.adminRestoreFarmBackup(player, zone, it) }
                            ?: sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_BACKUP, sender))
                        else -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_BACKUP, sender))
                    }
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
            "route" -> {
                val zone = args.getOrNull(1)
                val operation = args.getOrNull(2)?.lowercase()
                val routeName = args.getOrNull(3)?.lowercase() ?: "main"
                if (zone == null || operation == null) {
                    sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_ROUTE, sender))
                    return
                }
                when (operation) {
                    "start" -> service.adminStartFarmRoute(player, zone, routeName)
                    "finish", "save" -> service.adminFinishFarmRoute(player)
                    "cancel" -> service.adminCancelFarmRoute(player)
                    "status" -> service.adminFarmRouteStatus(player, zone, routeName)
                    "clear", "remove" -> service.adminClearFarmRoute(player, zone, routeName)
                    else -> sender.sendMessage(locale.render(MessageKey.ADMIN_HELP_ROUTE, sender))
                }
            }
            "worksite" -> worksiteAdmin(player, args.drop(1))
            "reset-farm" -> args.getOrNull(1)?.let { service.adminSetFarmStage(player, it, "reset") }
                ?: sendShortcutHelp(sender, action)
            "stop-order-cycle" -> args.getOrNull(1)?.let { service.adminStopFarmOrderCycle(player, it) }
                ?: sendShortcutHelp(sender, action)
            "start-order-cycle" -> args.getOrNull(1)?.let { service.adminStartFarmOrderCycle(player, it) }
                ?: sendShortcutHelp(sender, action)
            "save-farm-backup" -> args.getOrNull(1)?.let { service.adminSaveFarmBackup(player, it) }
                ?: sendShortcutHelp(sender, action)
            "list-farm-backups" -> args.getOrNull(1)?.let { service.adminListFarmBackups(player, it) }
                ?: sendShortcutHelp(sender, action)
            "restore-farm-backup" -> {
                val zone = args.getOrNull(1)
                val backupId = args.getOrNull(2)
                if (zone == null || backupId == null) sendShortcutHelp(sender, action)
                else service.adminRestoreFarmBackup(player, zone, backupId)
            }
            "farm-backup-status" -> args.getOrNull(1)?.let { service.adminFarmBackupStatus(player, it) }
                ?: sendShortcutHelp(sender, action)
            "reindex-farm" -> args.getOrNull(1)?.let { service.adminStartFarmBlockReset(player, it) }
                ?: sendShortcutHelp(sender, action)
            "farm-reindex-status" -> args.getOrNull(1)?.let { service.adminFarmBlockResetStatus(player, it) }
                ?: sendShortcutHelp(sender, action)
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
                if (kind !in setOf("tool", "seeds", "water", "archery")) {
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
        val overridden = service.adminOverriddenFarmPoints(zone).orEmpty()
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
                        "source" to locale.renderPath(
                            if (kind in overridden) "admin.point-source.override" else "admin.point-source.default",
                            sender,
                        ),
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

    private fun sendShortcutHelp(sender: CommandSender, action: String) {
        sender.sendMessage(
            locale.render(
                MessageKey.ADMIN_HELP_SHORTCUT,
                sender,
                mapOf(
                    "command" to locale.text(action),
                    "arguments" to locale.text(if (action == "restore-farm-backup") "zone id" else "zone"),
                    "description" to locale.renderPath("admin.shortcut-description.$action", sender),
                ),
            ),
        )
    }

    private fun worksiteAdmin(player: Player, args: List<String>) {
        val kind = parseKind(args.getOrNull(0))?.takeIf { it != ActivityKind.FARM }
        val admin = kind?.let(service.worksiteAdmins::handler)
        val zone = args.getOrNull(1)
        val operation = args.getOrNull(2)?.lowercase()
        if (admin == null || zone == null || operation == null) {
            sendWorksiteAdminHelp(player)
            return
        }
        val values = mutableMapOf("activity" to locale.text(kind.name.lowercase()), "zone" to locale.text(zone))
        when (operation) {
            "status" -> {
                val status = admin.status(zone)
                if (status == null) {
                    player.sendMessage(locale.renderPath("admin.worksite.unknown", player, values))
                } else {
                    values += mapOf(
                        "phase" to locale.text(status.phase.lowercase()),
                        "sequence" to locale.text(status.sequence),
                        "incident" to locale.text(status.incident?.lowercase() ?: "—"),
                        "progress" to locale.text(status.objective?.let { "${it.completed}/${it.required}" } ?: "—"),
                    )
                    player.sendMessage(locale.renderPath("admin.worksite.status", player, values))
                }
            }
            "start" -> player.sendMessage(
                locale.renderPath(if (admin.start(zone, player)) "admin.worksite.started" else "admin.worksite.rejected", player, values),
            )
            "incident" -> {
                val incident = args.getOrNull(3)
                values["incident"] = locale.text(incident?.lowercase() ?: "—")
                val accepted = incident != null && admin.forceIncident(zone, incident, System.currentTimeMillis())
                player.sendMessage(
                    locale.renderPath(if (accepted) "admin.worksite.incident-started" else "admin.worksite.incident-rejected", player, values),
                )
            }
            "reindex" -> when (args.getOrNull(3)?.lowercase() ?: "start") {
                "start" -> player.sendMessage(
                    locale.renderPath(
                        if (admin.startReindex(zone)) "admin.worksite.reindex-started" else "admin.worksite.reindex-rejected",
                        player,
                        values,
                    ),
                )
                "tick" -> {
                    val tick = admin.tickReindex(zone, 262_144)
                    values += mapOf(
                        "blocks" to locale.text(tick?.scannedBlocks ?: 0),
                        "targets" to locale.text(tick?.indexedTargets ?: 0),
                    )
                    player.sendMessage(locale.renderPath("admin.worksite.reindex-progress", player, values))
                }
                "cancel" -> player.sendMessage(
                    locale.renderPath(
                        if (admin.cancelReindex(zone)) "admin.worksite.reindex-cancelled" else "admin.worksite.reindex-rejected",
                        player,
                        values,
                    ),
                )
                else -> sendWorksiteAdminHelp(player)
            }
            else -> sendWorksiteAdminHelp(player)
        }
    }

    private fun sendWorksiteAdminHelp(sender: CommandSender) {
        sender.sendMessage(locale.renderPath("admin.worksite.help", sender))
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
                    (listOf("help", "edit", "inspect", "point", "points", "unmanage", "blockreset", "backup", "stage", "next", "finish", "event", "route", "worksite") + ADMIN_SHORTCUTS)
                        .filter { it.startsWith(args[1], true) }
                args[0].equals("debug", true) && sender.hasPermission("arcfarms.admin") ->
                    service.farmZoneIds().filter { it.startsWith(args[1], true) }
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("admin", true) && args[1].equals("worksite", true) ->
                    listOf("lumber", "mine", "help").filter { it.startsWith(args[2], true) }
                args[0].equals("admin", true) && args[1].lowercase() in setOf("edit", "inspect") ->
                    listOf("help").filter { it.startsWith(args[2], true) }
                args[0].equals("admin", true) && args[1].lowercase() in
                    setOf("point", "points", "unmanage", "blockreset", "backup", "stage", "next", "finish", "event", "route") ->
                    (service.farmZoneIds() + "help").filter { it.startsWith(args[2], true) }
                args[0].equals("admin", true) && args[1].lowercase() in ADMIN_SHORTCUTS ->
                    (service.farmZoneIds() + "help").filter { it.startsWith(args[2], true) }
                args[0].equals("debug", true) && sender.hasPermission("arcfarms.admin") ->
                    listOf("status", "contract", "stage", "next", "finish", "event", "give", "show", "points", "reset")
                        .filter { it.startsWith(args[2], true) }
                else -> emptyList()
            }
            4 -> when {
                args[0].equals("admin", true) && args[1].equals("worksite", true) -> {
                    val kind = parseKind(args[2])
                    (kind?.let(service.worksiteAdmins::zoneIds).orEmpty() + "help").filter { it.startsWith(args[3], true) }
                }
                args[0].equals("admin", true) && args[1].equals("point", true) ->
                    (POINT_ARGUMENTS + "help")
                        .filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].equals("stage", true) ->
                    (STAGE_STAGES + "help")
                        .filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].equals("event", true) ->
                    (EVENT_STAGES + "help").filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].equals("route", true) ->
                    listOf("start", "finish", "cancel", "status", "clear", "help").filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].equals("blockreset", true) ->
                    listOf("status", "help").filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].equals("backup", true) ->
                    listOf("save", "list", "status", "restore", "help").filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].lowercase() in
                    setOf("points", "unmanage", "next", "finish") ->
                    listOf("help").filter { it.startsWith(args[3], true) }
                args[0].equals("admin", true) && args[1].lowercase() in ADMIN_SHORTCUTS ->
                    listOf("help").filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("stage", true) ->
                    (listOf("preparation", "planting", "harvesting") + CARE_STAGES +
                        listOf("pests", "drought", "giant-crop", "channels", "night-shift", "market", "delivery", "complete", "reset"))
                        .filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("event", true) ->
                    EVENT_STAGES.filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("give", true) ->
                    listOf("tool", "seeds", "water", "archery").filter { it.startsWith(args[3], true) }
                args[0].equals("debug", true) && args[2].equals("contract", true) ->
                    service.farmOrderIds(args[1]).filter { it.startsWith(args[3], true) }
                else -> emptyList()
            }
            5 -> when {
                args[0].equals("admin", true) && args[1].equals("worksite", true) ->
                    listOf("status", "start", "incident", "reindex", "help").filter { it.startsWith(args[4], true) }
                args[0].equals("admin", true) && args[1].equals("route", true) &&
                    args[3].lowercase() in setOf("start", "status", "clear", "remove") ->
                    (service.adminFarmRouteNames(args[2]) + "main")
                        .distinct()
                        .filter { it.startsWith(args[4], true) }
                args[0].equals("admin", true) && args[1].equals("point", true) ->
                    listOf("clear", "remove", "help").filter { it.startsWith(args[4], true) }
                args[0].equals("admin", true) && args[1].equals("backup", true) && args[3].equals("restore", true) ->
                    listOf("help").filter { it.startsWith(args[4], true) }
                else -> emptyList()
            }
            6 -> when {
                args[0].equals("admin", true) && args[1].equals("worksite", true) && args[4].equals("incident", true) -> {
                    val kind = parseKind(args[2])
                    kind?.let(service.worksiteAdmins::handler)?.incidentIds().orEmpty().filter { it.startsWith(args[5], true) }
                }
                args[0].equals("admin", true) && args[1].equals("worksite", true) && args[4].equals("reindex", true) ->
                    listOf("start", "tick", "cancel").filter { it.startsWith(args[5], true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun parsePoint(raw: String): FarmPointKind? = when (raw.lowercase()) {
        "tool" -> FarmPointKind.TOOL
        "seeds" -> FarmPointKind.SEEDS
        "water" -> FarmPointKind.WATER
        "archery", "bow" -> FarmPointKind.ARCHERY
        "crates" -> FarmPointKind.CRATES
        "receiving" -> FarmPointKind.RECEIVING
        "food-delivery-portal", "food_delivery_portal", "delivery-portal", "portal" ->
            FarmPointKind.FOOD_DELIVERY_PORTAL
        "cart" -> FarmPointKind.CART
        "customer" -> FarmPointKind.CUSTOMER
        "travel" -> FarmPointKind.TRAVEL
        "hive" -> FarmPointKind.HIVE
        "irrigation" -> FarmPointKind.IRRIGATION
        "covers" -> FarmPointKind.COVERS
        "scarecrows" -> FarmPointKind.SCARECROWS
        "barn", "pen" -> FarmPointKind.PEN
        "perk-vendor", "vendor" -> FarmPointKind.PERK_VENDOR
        "processing", "processing-machine", "workshop" -> FarmPointKind.PROCESSING
        "processing-input", "processing-input-1", "processing-raw" -> FarmPointKind.PROCESSING_INPUT
        "processing-input-2" -> FarmPointKind.PROCESSING_INPUT_2
        "processing-input-3" -> FarmPointKind.PROCESSING_INPUT_3
        "processing-input-4" -> FarmPointKind.PROCESSING_INPUT_4
        "processing-output", "processing-product" -> FarmPointKind.PROCESSING_OUTPUT
        "fire-equipment", "fire-hose", "extinguisher" -> FarmPointKind.FIRE_EQUIPMENT
        "firewood" -> FarmPointKind.FIREWOOD
        "ditch" -> FarmPointKind.DITCH
        "rival-farm", "rival_farm", "neighbor-farm" -> FarmPointKind.RIVAL_FARM
        else -> null
    }

    private fun pointArgument(kind: FarmPointKind): String = when (kind) {
        FarmPointKind.PEN -> "barn"
        FarmPointKind.PERK_VENDOR -> "perk-vendor"
        FarmPointKind.FIRE_EQUIPMENT -> "fire-equipment"
        FarmPointKind.FOOD_DELIVERY_PORTAL -> "food-delivery-portal"
        FarmPointKind.PROCESSING_INPUT -> "processing-input"
        FarmPointKind.PROCESSING_INPUT_2 -> "processing-input-2"
        FarmPointKind.PROCESSING_INPUT_3 -> "processing-input-3"
        FarmPointKind.PROCESSING_INPUT_4 -> "processing-input-4"
        FarmPointKind.PROCESSING_OUTPUT -> "processing-output"
        FarmPointKind.RIVAL_FARM -> "rival-farm"
        else -> kind.name.lowercase()
    }

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
            "apples" to FarmCareType.APPLE_HARVEST,
        )
        private val CARE_STAGES = CARE_EVENT_TYPES.keys.toList()
        private val EVENT_STAGES = CARE_STAGES +
            listOf(
                "pests", "drought", "birds", "giant-crop", "channels", "night-shift", "market",
                "food-delivery", "processing", "barn-fire", "frost",
                "boar-breakout", "rival-raid",
            )
        private val STAGE_STAGES = listOf("preparation", "planting", "harvesting") +
            EVENT_STAGES + listOf("delivery", "complete", "reset")
        private val POINT_ARGUMENTS = listOf(
            "tool", "seeds", "water", "crates", "receiving", "food-delivery-portal", "cart", "customer", "travel", "hive", "irrigation",
            "covers", "scarecrows", "barn", "archery", "perk-vendor", "processing", "processing-input",
            "processing-input-2", "processing-input-3", "processing-input-4", "processing-output", "fire-equipment",
            "firewood",
            "ditch", "rival-farm",
        )
        private val ADMIN_SHORTCUTS = listOf(
            "reset-farm",
            "stop-order-cycle",
            "start-order-cycle",
            "save-farm-backup",
            "list-farm-backups",
            "restore-farm-backup",
            "farm-backup-status",
            "reindex-farm",
            "farm-reindex-status",
        )
    }
}

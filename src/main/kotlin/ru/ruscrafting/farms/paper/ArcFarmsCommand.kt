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
            "reload" -> reload(sender)
            else -> sender.sendMessage(locale.render(MessageKey.HELP, sender))
        }
        return true
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

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        when (args.size) {
            1 -> buildList {
                add("status")
                add("top")
                if (sender.hasPermission("arcfarms.admin")) add("reload")
            }.filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("top", true)) listOf("farm", "lumber", "mine").filter { it.startsWith(args[1], true) } else emptyList()
            else -> emptyList()
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
}

package ru.ruscrafting.farms.paper.mine.lift

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import java.util.UUID
import java.util.logging.Level

/**
 * Owns the configured lift instances and keeps the scenario-facing API on the
 * legacy `main` lift. Service lifts share the command surface but never enter
 * mine scenario maintenance or event handling.
 */
internal class MineLiftRuntimeManager(
    private val plugin: JavaPlugin,
    private val locale: ArcFarmsLocale,
) : AutoCloseable, CommandExecutor, TabCompleter, MineLiftAccess {
    private val riderOwners = mutableMapOf<UUID, String>()
    private val runtimes: Map<String, MineLiftRuntime>

    init {
        val configured = MineLiftSettings.loadAll(plugin.dataFolder.toPath()) { id, failure ->
            plugin.logger.log(Level.SEVERE, "Mine lift $id configuration rejected; lift remains closed", failure)
        }
        lateinit var allRuntimes: Collection<MineLiftRuntime>
        val created = linkedMapOf<String, MineLiftRuntime>()
        configured.forEach { settings ->
            runCatching {
                MineLiftRuntime(
                    plugin = plugin,
                    locale = locale,
                    settings = settings,
                    claimRider = { player, lift ->
                        riderOwners[player]?.let { owner -> owner == lift } ?: run {
                            riderOwners[player] = lift
                            true
                        }
                    },
                    releaseRider = { player, lift ->
                        if (riderOwners[player] == lift) riderOwners.remove(player)
                    },
                    hasRecovery = { player -> allRuntimes.any { it.hasRecovery(player) } },
                )
            }.onSuccess { runtime -> created[settings.id] = runtime }
                .onFailure { failure ->
                    plugin.logger.log(
                        Level.SEVERE,
                        "Mine lift ${settings.id} unavailable; other lift runtimes remain active",
                        failure,
                    )
                }
        }
        allRuntimes = created.values
        runtimes = created
    }

    fun start() {
        requireNotNull(plugin.getCommand("minelift")).apply {
            setExecutor(this@MineLiftRuntimeManager)
            tabCompleter = this@MineLiftRuntimeManager
        }
        runtimes.values.forEach { runtime ->
            runCatching { runtime.start() }.onFailure { failure ->
                plugin.logger.log(Level.SEVERE, "Mine lift ${runtime.statusLine()} failed to start", failure)
                runCatching { runtime.close() }
            }
        }
    }

    fun ownsTeleport(event: PlayerTeleportEvent): Boolean = runtimes.values.any { it.ownsTeleport(event) }

    override fun floors(): List<MineLiftAccess.FloorSnapshot> = runtimes["main"]?.floors().orEmpty()

    override fun beginMaintenance(ownerKey: String): Boolean = runtimes["main"]?.beginMaintenance(ownerKey) == true

    override fun endMaintenance(ownerKey: String) {
        runtimes["main"]?.endMaintenance(ownerKey)
    }

    override fun maintenanceReady(ownerKey: String): Boolean = runtimes["main"]?.maintenanceReady(ownerKey) == true

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.firstOrNull()?.equals("status", ignoreCase = true) == true) {
            if (!sender.hasPermission("arcfarms.admin")) {
                sender.sendMessage(text("admin-required", sender))
                return true
            }
            val requested = args.getOrNull(1)?.lowercase()
            if (requested != null) {
                val runtime = runtimes[requested]
                if (runtime != null) sender.sendMessage(runtime.statusLine())
                else sender.sendMessage(text("unknown", sender, mapOf("id" to Component.text(requested))))
            } else if (runtimes.isEmpty()) {
                sender.sendMessage(text("none", sender))
            } else {
                runtimes.values.forEach { sender.sendMessage(it.statusLine()) }
            }
            return true
        }

        val player = sender as? Player ?: return true
        val firstArgument = args.firstOrNull()?.lowercase()
        val requestedId = firstArgument?.takeIf { it in runtimes }
        if (firstArgument != null && firstArgument.toIntOrNull() == null && requestedId == null) {
            player.sendMessage(text("unknown", player, mapOf("id" to Component.text(firstArgument))))
            return true
        }
        val nearby = runtimes.values.filter { it.isNearAuthorized(player) }
        val selected = if (requestedId != null) {
            runtimes.getValue(requestedId).takeIf { it in nearby }
        } else {
            nearby.singleOrNull()
        }
        if (selected == null) {
            if (nearby.size > 1 && requestedId == null) {
                player.sendMessage(text("several", player))
            } else if (requestedId != null) {
                player.sendMessage(text("unavailable-id", player, mapOf("id" to Component.text(requestedId))))
            } else {
                runtimes.values.firstOrNull()?.sendNearPanel(player)
            }
            return true
        }
        selected.handlePlayerCommand(player, if (requestedId == null) args else args.drop(1).toTypedArray())
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        val player = sender as? Player
        val nearby = player?.let { runtimes.values.filter { runtime -> runtime.isNearAuthorized(it) } }.orEmpty()
        if (args.size == 1) {
            val ids = runtimes.keys.filter { it.startsWith(args[0], ignoreCase = true) }
            val admin = if (sender.hasPermission("arcfarms.admin")) listOf("status") else emptyList()
            val floors = if (nearby.size == 1) nearby.single().floorArguments() else emptyList()
            return (ids + admin + floors).distinct()
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].lowercase() in runtimes) {
            val runtime = runtimes.getValue(args[0].lowercase())
            return if (player != null && runtime.isNearAuthorized(player)) {
                runtime.floorArguments().filter { it.startsWith(args[1]) }
            } else emptyList()
        }
        if (args.size == 2 && args[0].equals("status", ignoreCase = true) && sender.hasPermission("arcfarms.admin")) {
            return runtimes.keys.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }

    private fun text(key: String, sender: CommandSender, values: Map<String, Component> = emptyMap()) =
        locale.renderPath("mine-lift.$key", sender, values).decoration(TextDecoration.ITALIC, false)

    override fun close() {
        runtimes.values.forEach { runCatching { it.close() } }
        riderOwners.clear()
    }
}

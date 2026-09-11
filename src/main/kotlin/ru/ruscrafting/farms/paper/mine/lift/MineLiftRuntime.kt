package ru.ruscrafting.farms.paper.mine.lift

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.mine.lift.MineLiftMotion
import ru.ruscrafting.farms.paper.FarmDialogScreens
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.floor

internal class MineLiftMaintenanceClaim {
    private var owner: String? = null

    fun begin(ownerKey: String): Boolean {
        if (ownerKey.isBlank()) return false
        if (owner == null) owner = ownerKey
        return owner == ownerKey
    }

    fun end(ownerKey: String) { if (owner == ownerKey) owner = null }
    fun owns(ownerKey: String): Boolean = owner == ownerKey
    fun ownsAny(): Boolean = owner != null
    fun ready(ownerKey: String, phase: MineLiftMotion.Phase, riderCount: Int, queued: List<Int>): Boolean =
        owns(ownerKey) && phase == MineLiftMotion.Phase.DOCKED && riderCount == 0 && queued.isEmpty()
    fun clear() { owner = null }
}

/**
 * Main-thread transport owner: one cabin, four passengers, at most eight queued floor calls.
 * The location escrow commits before mounting, and survives quit, disable and process crashes.
 * No world blocks are changed by this runtime; a blocked surveyed shaft closes the lift.
 */
internal class MineLiftRuntime(
    private val plugin: JavaPlugin,
    private val locale: ArcFarmsLocale,
    private val settings: MineLiftSettings,
    private val claimRider: (UUID, String) -> Boolean = { _, _ -> true },
    private val releaseRider: (UUID, String) -> Unit = { _, _ -> },
    private val hasRecovery: (UUID) -> Boolean = { false },
) :
    AutoCloseable, Listener, CommandExecutor, TabCompleter, MineLiftAccess {
    private val recovery = MineLiftRecovery(plugin.dataFolder.toPath(), settings.id)
    private val tasks = LifecycleTaskScope()
    private val dialogs = PaperDialogRuntime(plugin)
    private val teleports = ScopedTeleportAuthorizer()
    private var scene: MineLiftScene? = null
    private var motion: MineLiftMotion? = null
    private val riders = linkedMapOf<UUID, Int>()
    private val chunks = mutableListOf<org.bukkit.Chunk>()
    private var closed = false
    private var tickNumber = 0L
    private var movementTask: ScheduledTask? = null
    private val exiting = mutableSetOf<UUID>()
    private val maintenance = MineLiftMaintenanceClaim()

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        Bukkit.getOnlinePlayers().forEach(::recover)
        tryStart()
    }

    private fun tryStart() {
        val config = settings
        if (closed || scene != null) return
        val world = Bukkit.getWorld(config.world) ?: return
        try {
            val requested = (config.floors.flatMap { listOf(it.exit.location(world), it.panel.location(world)) } +
                Location(world, config.x, config.floors.first().y, config.z)).map { it.chunk }.distinct()
            requested.forEach { if (it.addPluginChunkTicket(plugin)) chunks += it }
            require(config.floors.all { safe(it.exit.location(world)) }) { "Mine lift landing is obstructed" }
            val minX = floor(config.x - config.width / 2).toInt()
            val maxX = floor(config.x + config.width / 2).toInt()
            val minZ = floor(config.z - config.depth / 2).toInt()
            val maxZ = floor(config.z + config.depth / 2).toInt()
            for (x in minX..maxX) for (z in minZ..maxZ) {
                for (y in floor(config.floors.last().y).toInt()..floor(config.floors.first().y + 2.8).toInt()) {
                    require(world.getBlockAt(x, y, z).type.isAir) { "Mine lift shaft obstructed at $x,$y,$z" }
                }
            }
            val next = MineLiftScene(plugin, config, world)
            scene = next
            requested.forEach(next::cleanOrphans)
            motion = MineLiftMotion(config.floors.map { it.y }, config.speed)
            next.spawn(config.floors.first().y) { label(it) }
            movementTask = tasks.runTimer(1, 1) {
                try { tick() } catch (failure: Exception) {
                    plugin.logger.log(Level.SEVERE, "Mine lift stopped; recovering passengers", failure)
                    stopCabin()
                }
            }
            plugin.logger.info("MINE_LIFT ready world=${config.world} floors=${config.floors.size} seats=4")
        } catch (failure: Exception) {
            plugin.logger.log(Level.SEVERE, "Mine lift closed; inspect shaft and landing configuration", failure)
            stopCabin()
        }
    }

    private fun tick() {
        val cabin = scene ?: return
        val state = motion ?: return
        val config = settings
        tickNumber++
        val arrived = state.tick()
        check(cabin.move(state.y, state.phase == MineLiftMotion.Phase.DOCKED)) { "Mine lift entity movement rejected" }
        riders.keys.toList().forEach { id ->
            val player = Bukkit.getPlayer(id)
            val seat = cabin.seats[riders.getValue(id)]
            if (player == null || player.isDead) {
                riders.remove(id)
                releaseRider(id, settings.id)
                return@forEach
            }
            if (player.vehicle != seat) {
                recover(player)
                check(player.uniqueId !in riders) { "Mine lift passenger recovery rejected" }
            } else if (tickNumber % 10 == 0L) {
                player.sendActionBar(text(if (state.phase == MineLiftMotion.Phase.BOARDING) "boarding-hud" else "travelling", player,
                    mapOf("floor" to floorName(state.target, player))))
            }
        }
        if (arrived) {
            val exit = config.floors[state.floor].exit.location(requireNotNull(Bukkit.getWorld(config.world)))
            riders.keys.toList().mapNotNull(Bukkit::getPlayer).forEach { player ->
                check(unload(player, exit)) { "Mine lift arrival teleport rejected" }
                player.sendMessage(text("arrived", player, mapOf("floor" to floorName(state.floor, player))))
            }
            exit.world.playSound(exit, Sound.BLOCK_NOTE_BLOCK_BELL, .65f, 1.1f)
        }
        if (tickNumber % 20 == 0L) {
            config.floors.indices.forEach { cabin.label(it, label(it)) }
            if (state.phase == MineLiftMotion.Phase.MOVING) {
                val at = Location(Bukkit.getWorld(config.world), config.x, state.y, config.z)
                at.world.playSound(at, Sound.BLOCK_CHAIN_STEP, .55f, .65f)
            }
        }
    }

    private fun label(index: Int): Component = text("panel", values = mapOf("floor" to floorName(index),
        "state" to text(if (motion?.phase != MineLiftMotion.Phase.MOVING && motion?.floor == index) "panel-ready" else "panel-call")))

    private fun nearFloor(player: Player): Int? = settings.floors.indices.firstOrNull { index ->
        val config = settings
        player.world.name == config.world && player.location.distanceSquared(config.floors[index].exit.location(player.world)) <= 36 &&
            abs(player.y - config.floors[index].y) < 2.5
    }

    private fun open(player: Player, index: Int) {
        if (maintenance.ownsAny() || !player.hasPermission("arcfarms.mine") || nearFloor(player) != index || hasRecovery(player.uniqueId)) {
            player.sendMessage(text("unavailable", player)); return
        }
        val state = motion ?: run { player.sendMessage(text("unavailable", player)); return }
        if (state.floor != index || state.phase == MineLiftMotion.Phase.MOVING) {
            state.call(index)
            player.sendMessage(text("called", player, mapOf("floor" to floorName(index, player))))
            return
        }
        val config = settings
        dialogs.beginFlow(player)
        dialogs.open(player, PaperDialogScreen(
            id = "farms.mine_lift", title = FarmDialogScreens.nativeBody(text("title", player)),
            body = listOf(
                PaperDialogBody(
                    FarmDialogScreens.nativeBody(
                        text("description", player, mapOf("floor" to floorName(index, player)))
                    )
                )
            ),
            buttons = config.floors.indices.filter {
                it != index && (state.phase != MineLiftMotion.Phase.BOARDING || it == state.target)
            }.map { target ->
                PaperDialogButton(PaperDialogActionId.of("floor_$target"), FarmDialogScreens.nativeBody(text("go", player,
                    mapOf("floor" to floorName(target, player)))), width = 230, closeDialogBeforeAction = true,
                    onClick = { board(it.player, index, target) })
            }, columns = 2,
            exitButton = PaperDialogButton(PaperDialogActionId.of("close"), FarmDialogScreens.nativeControl(text("close", player)), width = 200, onClick = {}),
        ))
    }

    private fun board(player: Player, source: Int, destination: Int) {
        val config = settings
        val state = motion ?: return
        val cabin = scene ?: return
        if (maintenance.ownsAny() || !player.hasPermission("arcfarms.mine") || nearFloor(player) != source || state.floor != source ||
            player.isInsideVehicle || player.isDead || hasRecovery(player.uniqueId) ||
            state.phase == MineLiftMotion.Phase.MOVING ||
            (state.phase == MineLiftMotion.Phase.BOARDING && state.target != destination)) {
            player.sendMessage(text("unavailable", player)); return
        }
        val slot = cabin.seats.indices.firstOrNull { it !in riders.values && cabin.seats[it].passengers.isEmpty() }
            ?: run { player.sendMessage(text("full", player)); return }
        val returnPoint = config.floors[source].exit.location(player.world)
        if (!safe(returnPoint)) { player.sendMessage(text("unavailable", player)); return }
        if (!claimRider(player.uniqueId, settings.id)) { player.sendMessage(text("unavailable", player)); return }
        try {
            recovery.capture(player, returnPoint)
        } catch (failure: Exception) {
            releaseRider(player.uniqueId, settings.id)
            plugin.logger.log(Level.SEVERE, "Mine lift passenger escrow rejected for ${player.uniqueId}", failure)
            player.sendMessage(text("unavailable", player))
            return
        }
        if (!cabin.seats[slot].addPassenger(player)) {
            recover(player); player.sendMessage(text("unavailable", player)); return
        }
        riders[player.uniqueId] = slot
        check(state.board(destination))
        player.sendMessage(text("boarding", player, mapOf("floor" to floorName(destination, player))))
    }

    private fun safe(at: Location): Boolean = !at.block.isLiquid && at.block.isPassable &&
        at.clone().add(0.0, 1.0, 0.0).block.isPassable && at.clone().add(0.0, -1.0, 0.0).block.type.isSolid

    private fun unload(player: Player, destination: Location): Boolean {
        if (!safe(destination)) return false
        exiting += player.uniqueId
        try {
            if (player.isInsideVehicle && !player.leaveVehicle()) return false
            if (!teleports.authorize(player.uniqueId, destination) { player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN) }) return false
            if (player.world != destination.world || player.location.distanceSquared(destination) > .25) return false
            player.fallDistance = 0f
            recovery.acknowledge(player)
            riders.remove(player.uniqueId)
            releaseRider(player.uniqueId, settings.id)
            return true
        } finally { exiting -= player.uniqueId }
    }

    private fun recover(player: Player) {
        if (player.isDead || !recovery.contains(player.uniqueId)) return
        val target = recovery.destination(player.uniqueId) ?: return
        if (unload(player, target)) player.sendMessage(text("recovered", player))
    }

    fun ownsTeleport(event: PlayerTeleportEvent): Boolean = teleports.isAuthorized(event.player.uniqueId, event.to)

    /** Claims exclusive maintenance while the cabin finishes its current trip and unloads riders. */
    override fun beginMaintenance(ownerKey: String): Boolean {
        if (ownerKey.isBlank() || closed || scene == null || motion == null) return false
        if (!maintenance.begin(ownerKey)) return false
        val state = requireNotNull(motion)
        if (state.phase == MineLiftMotion.Phase.DOCKED && riders.isNotEmpty()) {
            val floor = settings.floors[state.floor]
            val world = Bukkit.getWorld(settings.world)
            if (world != null) riders.keys.toList().mapNotNull(Bukkit::getPlayer).forEach { unload(it, floor.exit.location(world)) }
        }
        return true
    }

    /** Releases maintenance only for its owner; repeated release is harmless. */
    override fun endMaintenance(ownerKey: String) {
        maintenance.end(ownerKey)
    }

    override fun maintenanceReady(ownerKey: String): Boolean {
        val state = motion ?: return false
        return maintenance.ready(ownerKey, state.phase, riders.size, state.queued)
    }

    /** Read-only configured floor facts for maintenance planning; values come from the loaded settings. */
    override fun floors(): List<MineLiftAccess.FloorSnapshot> {
        val config = settings
        val world = Bukkit.getWorld(config.world) ?: return emptyList()
        return config.floors.map { MineLiftAccess.FloorSnapshot(it.id, it.y, it.exit.location(world)) }
    }

    @EventHandler(ignoreCancelled = true)
    fun interact(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val index = scene?.panels?.get(event.rightClicked.uniqueId) ?: return
        event.isCancelled = true
        open(event.player, index)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun dismount(event: EntityDismountEvent) {
        val player = event.entity as? Player ?: return
        if (player.uniqueId !in riders || player.uniqueId in exiting || !event.isCancellable) return
        event.isCancelled = true
        if (motion?.phase != MineLiftMotion.Phase.MOVING) tasks.runLater(1) { recover(player) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun teleport(event: PlayerTeleportEvent) {
        if (event.player.uniqueId in riders && !teleports.isAuthorized(event.player.uniqueId, event.to)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun move(event: PlayerMoveEvent) {
        if (event is PlayerTeleportEvent || event.player.uniqueId in riders || event.player.uniqueId in exiting) return
        if (settings.contains(event.to) && settings.contains(event.from).not()) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun damage(event: EntityDamageEvent) { if (event.entity.uniqueId in riders) event.isCancelled = true }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        recover(event.player)
        if (riders.remove(event.player.uniqueId) != null) releaseRider(event.player.uniqueId, settings.id)
    }
    @EventHandler fun join(event: PlayerJoinEvent) { tasks.runLater(1) { recover(event.player) } }
    @EventHandler fun respawn(event: PlayerRespawnEvent) { tasks.runLater(1) { recover(event.player) } }
    @EventHandler fun worldLoad(event: WorldLoadEvent) {
        Bukkit.getOnlinePlayers().filter { recovery.contains(it.uniqueId) }.forEach(::recover)
        if (event.world.name == settings.world) tryStart()
    }
    @EventHandler fun chunkLoad(event: ChunkLoadEvent) { scene?.cleanOrphans(event.chunk) }

    fun isNearAuthorized(player: Player): Boolean = !closed && scene != null &&
        player.hasPermission("arcfarms.mine") && nearFloor(player) != null

    fun sendNearPanel(player: Player) { player.sendMessage(text("near-panel", player)) }

    fun handlePlayerCommand(player: Player, args: Array<out String>) {
        val index = nearFloor(player)
        if (index == null) {
            sendNearPanel(player)
            return
        }
        val target = args.firstOrNull()?.toIntOrNull()?.minus(1)
        if (target != null && target in settings.floors.indices && target != index) board(player, index, target)
        else open(player, index)
    }

    fun floorArguments(): List<String> = settings.floors.indices.map { (it + 1).toString() }

    fun statusLine(): String {
        val state = motion
        return "MINE_LIFT id=${settings.id} ready=${scene != null} phase=${state?.phase} floor=${state?.floor} " +
            "target=${state?.target} y=${state?.y} riders=${riders.size} recovery=${recovery.pendingCount} queue=${state?.queued}"
    }

    fun hasRecovery(player: UUID): Boolean = recovery.contains(player)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.firstOrNull() == "status" && sender.hasPermission("arcfarms.admin")) {
            sender.sendMessage(statusLine())
        } else if (sender is Player) {
            handlePlayerCommand(sender, args)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        if (args.size == 1) (settings.floors.indices.map { (it + 1).toString() } +
            if (sender.hasPermission("arcfarms.admin")) listOf("status") else emptyList()).filter { it.startsWith(args[0]) } else emptyList()

    private fun floorName(index: Int, player: Player? = null) = text("floors.${settings.floors[index].id}", player)
    private fun text(key: String, player: Player? = null, values: Map<String, Component> = emptyMap()) =
        locale.renderPath("mine-lift.$key", player, values).decoration(TextDecoration.ITALIC, false)

    private fun stopCabin() {
        movementTask?.cancel()
        movementTask = null
        riders.keys.toList().mapNotNull(Bukkit::getPlayer).forEach { player ->
            runCatching { recover(player) }.onFailure { plugin.logger.log(Level.SEVERE, "Mine lift recovery remains pending", it) }
        }
        riders.keys.forEach { releaseRider(it, settings.id) }
        scene?.close(); scene = null; motion = null; riders.clear()
        chunks.forEach { it.removePluginChunkTicket(plugin) }; chunks.clear()
    }

    override fun close() {
        if (closed) return
        closed = true
        maintenance.clear()
        stopCabin(); tasks.close(); dialogs.close(); HandlerList.unregisterAll(this)
    }
}

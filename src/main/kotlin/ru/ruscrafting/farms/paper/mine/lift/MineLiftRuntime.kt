package ru.ruscrafting.farms.paper.mine.lift

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
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
 * Main-thread transport owner: one cabin, capacity derived from its seat grid, at most eight queued floor calls.
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
    private val recovery: MineLiftRecovery = MineLiftRecovery(plugin.dataFolder.toPath(), settings.id),
    private val capture: ((() -> Boolean), (() -> Boolean)) -> CompletableFuture<Unit> = { current, step ->
        while (current() && !step()) Unit
        CompletableFuture.completedFuture(Unit)
    },
) :
    AutoCloseable, Listener, CommandExecutor, TabCompleter, MineLiftAccess {
    private val tasks = LifecycleTaskScope()
    private val dialogs = PaperDialogRuntime(plugin)
    private val teleports = ScopedTeleportAuthorizer()
    private var scene: MineLiftScene? = null
    private var motion: MineLiftMotion? = null
    private val riders = linkedMapOf<UUID, Int>()
    private val boarding = mutableMapOf<UUID, Int>()
    private val acknowledging = mutableSetOf<UUID>()
    private val chunks = mutableListOf<org.bukkit.Chunk>()
    private var closed = false
    private var preparing = false
    private var preparationEpoch = 0L
    private var tickNumber = 0L
    private var movementTask: ScheduledTask? = null
    private val exiting = mutableSetOf<UUID>()
    private val maintenance = MineLiftMaintenanceClaim()
    private val interactionGate = MineLiftInteractionGate()

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        Bukkit.getOnlinePlayers().forEach(::recover)
        tryStart()
    }

    private fun tryStart() {
        val config = settings
        if (closed || scene != null || preparing) return
        val world = Bukkit.getWorld(config.world) ?: return
        preparing = true
        val epoch = ++preparationEpoch
        val token = tasks.token()
        fun current() = !closed && epoch == preparationEpoch && Bukkit.getWorld(config.world) === world
        val minX = floor(config.x - config.width / 2).toInt()
        val maxX = floor(config.x + config.width / 2).toInt()
        val minZ = floor(config.z - config.depth / 2).toInt()
        val maxZ = floor(config.z + config.depth / 2).toInt()
        val minY = floor(config.floors.last().y).toInt()
        val maxY = floor(config.floors.first().y + config.theme.shaftClearanceAboveStop).toInt()
        if (minY < world.minHeight || maxY >= world.maxHeight) {
            preparing = false
            plugin.logger.severe("Mine lift closed; shaft is outside world height")
            return
        }
        val requested = linkedSetOf<Pair<Int, Int>>()
        config.floors.forEach { level ->
            listOf(level.exit, level.panel).forEach { point ->
                requested += floor(point.x).toInt() shr 4 to (floor(point.z).toInt() shr 4)
            }
        }
        for (cx in (minX shr 4)..(maxX shr 4)) for (cz in (minZ shr 4)..(maxZ shr 4)) requested += cx to cz
        var loads = CompletableFuture.completedFuture(Unit)
        requested.forEach { (cx, cz) ->
            loads = loads.thenCompose {
                tasks.callSync(token) {
                    check(current()) { "Mine lift preparation superseded" }
                    world.getChunkAtAsync(cx, cz, true)
                }.thenCompose { it }.thenCompose { chunk ->
                    tasks.callSync(token) {
                        check(current()) { "Mine lift preparation superseded" }
                        if (chunk.addPluginChunkTicket(plugin)) chunks += chunk
                    }
                }
            }
        }
        loads.thenCompose {
            tasks.callSync(token) {
                check(current()) { "Mine lift preparation superseded" }
                require(config.floors.all { safe(it.exit.location(world)) }) { "Mine lift landing is obstructed" }
                var x = minX
                var z = minZ
                var y = minY
                capture(::current) {
                    require(world.isChunkLoaded(x shr 4, z shr 4)) { "Mine lift shaft chunk unloaded" }
                    require(world.getBlockAt(x, y, z).type.isAir) { "Mine lift shaft obstructed at $x,$y,$z" }
                    y++
                    if (y > maxY) { y = minY; z++ }
                    if (z > maxZ) { z = minZ; x++ }
                    x > maxX
                }
            }.thenCompose { it }
        }.thenCompose {
            tasks.callSync(token) {
                check(current()) { "Mine lift preparation superseded" }
                val next = MineLiftScene(plugin, config, world)
                scene = next
                var chunkIndex = 0
                var entities = emptyArray<org.bukkit.entity.Entity>()
                var entityIndex = 0
                capture(::current) {
                    if (entityIndex < entities.size) {
                        next.cleanOrphan(entities[entityIndex++])
                        false
                    } else if (chunkIndex < chunks.size) {
                        entities = chunks[chunkIndex++].entities
                        entityIndex = 0
                        false
                    } else true
                }.thenCompose {
                    tasks.callSync(token) {
                        check(current() && scene === next) { "Mine lift preparation superseded" }
                        motion = MineLiftMotion(config.floors.map { it.y }, config.speed,
                            if (config.theme == MineLiftTheme.CASINO) CASINO_BOARDING_TICKS else MineLiftMotion.BOARDING_TICKS)
                        next.spawn(config.floors.first().y, { label(it) }) { step -> capture(::current, step) }
                    }.thenCompose { it }
                }
            }.thenCompose { it }
        }.whenComplete { _, failure ->
            tasks.runSync(token) {
                if (!current()) return@runSync
                preparing = false
                if (failure != null) {
                    plugin.logger.log(Level.SEVERE, "Mine lift closed; inspect shaft and landing configuration", failure)
                    stopCabin()
                } else {
                    movementTask = tasks.runTimer(1, 1) {
                        try { tick() } catch (problem: Exception) {
                            plugin.logger.log(Level.SEVERE, "Mine lift stopped; recovering passengers", problem)
                            stopCabin()
                        }
                    }
                    plugin.logger.info("MINE_LIFT ready world=${config.world} floors=${config.floors.size} seats=${scene?.seats?.size}")
                }
            }
        }
    }

    private fun tick() {
        val cabin = scene ?: return
        val state = motion ?: return
        val config = settings
        tickNumber++
        val arrived = state.tick()
        check(cabin.move(state.y, state.doorsOpen)) { "Mine lift entity movement rejected" }
        riders.keys.toList().forEach { id ->
            val player = Bukkit.getPlayer(id)
            val seat = cabin.seats[riders.getValue(id)]
            if (player == null || player.isDead) {
                player?.let(::stopMusic)
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
            if (state.phase == MineLiftMotion.Phase.MOVING && config.theme != MineLiftTheme.CASINO) {
                val at = Location(Bukkit.getWorld(config.world), config.x, state.y, config.z)
                at.world.playSound(at, Sound.BLOCK_CHAIN_STEP, .55f, .65f)
            }
        }
    }

    private fun label(index: Int): Component = text("panel", values = mapOf("floor" to floorName(index),
        "state" to text(if (motion?.doorsOpen == true && motion?.floor == index) "panel-ready" else "panel-call")))

    private fun nearFloor(player: Player): Int? = settings.floors.indices.firstOrNull { index ->
        val config = settings
        player.world.name == config.world && player.location.distanceSquared(config.floors[index].exit.location(player.world)) <= 36 &&
            abs(player.y - config.floors[index].y) < 2.5
    }

    private fun nearCabin(player: Player, index: Int): Boolean {
        val state = motion ?: return false
        return state.doorsOpen && state.floor == index && settings.cabinContains(player.location, state.y)
    }

    private fun near(player: Player, index: Int, allowCabin: Boolean): Boolean =
        nearFloor(player) == index || (allowCabin && nearCabin(player, index))

    private fun open(player: Player, index: Int, allowCabin: Boolean = false): Boolean {
        if (maintenance.ownsAny() || !player.hasPermission("arcfarms.mine") || !near(player, index, allowCabin) || hasRecovery(player.uniqueId)) {
            player.sendMessage(text("unavailable", player)); return false
        }
        val state = motion ?: run { player.sendMessage(text("unavailable", player)); return false }
        if (state.floor != index || !state.doorsOpen) {
            state.call(index)
            player.sendMessage(text("called", player, mapOf("floor" to floorName(index, player))))
            return true
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
                state.canBoard(it)
            }.map { target ->
                PaperDialogButton(PaperDialogActionId.of("floor_$target"), FarmDialogScreens.nativeBody(text("go", player,
                    mapOf("floor" to floorName(target, player)))), width = 230, closeDialogBeforeAction = true,
                    onClick = { board(it.player, index, target, allowCabin) })
            }, columns = 2,
            exitButton = PaperDialogButton(PaperDialogActionId.of("close"), FarmDialogScreens.nativeControl(text("close", player)), width = 200, onClick = {}),
        ))
        return true
    }

    /** Shared cabin-click and walk-in path; choosing a floor still boards through the same menu action. */
    private fun openCabinMenu(player: Player): Boolean {
        val state = motion
        val floor = state?.takeIf { it.doorsOpen }?.floor
        if (floor == null || !nearCabin(player, floor)) {
            player.sendMessage(text("unavailable", player))
            return false
        }
        return open(player, floor, allowCabin = true)
    }

    private fun board(player: Player, source: Int, destination: Int, allowCabin: Boolean = false) {
        val config = settings
        val state = motion ?: return
        val cabin = scene ?: return
        if (maintenance.ownsAny() || !player.hasPermission("arcfarms.mine") || !near(player, source, allowCabin) || state.floor != source ||
            player.isInsideVehicle || player.isDead || player.uniqueId in boarding || hasRecovery(player.uniqueId) ||
            !state.canBoard(destination)) {
            player.sendMessage(text("unavailable", player)); return
        }
        val slot = cabin.seats.indices.firstOrNull { it !in riders.values && it !in boarding.values && cabin.seats[it].passengers.isEmpty() }
            ?: run { player.sendMessage(text("full", player)); return }
        val returnPoint = config.floors[source].exit.location(player.world)
        if (!safe(returnPoint)) { player.sendMessage(text("unavailable", player)); return }
        if (!claimRider(player.uniqueId, settings.id)) { player.sendMessage(text("unavailable", player)); return }
        boarding[player.uniqueId] = slot
        val token = tasks.token()
        recovery.capture(player, returnPoint).whenComplete { _, failure ->
            tasks.runSync(token) {
                boarding.remove(player.uniqueId)
                if (failure != null) {
                    releaseRider(player.uniqueId, settings.id)
                    plugin.logger.log(Level.SEVERE, "Mine lift passenger escrow rejected for ${player.uniqueId}", failure)
                    if (player.isOnline) player.sendMessage(text("unavailable", player))
                    return@runSync
                }
                if (!player.isOnline) { releaseRider(player.uniqueId, settings.id); return@runSync }
                val stillBoardable = scene === cabin && motion === state && !closed && !maintenance.ownsAny() &&
                    near(player, source, allowCabin) && !player.isDead && !player.isInsideVehicle &&
                    state.floor == source && state.canBoard(destination) &&
                    cabin.seats[slot].passengers.isEmpty()
                if (!stillBoardable || !cabin.seats[slot].addPassenger(player)) {
                    recover(player)
                    player.sendMessage(text("unavailable", player))
                    return@runSync
                }
                riders[player.uniqueId] = slot
                check(state.board(destination))
                if (config.theme == MineLiftTheme.CASINO) {
                    player.playSound(player, CASINO_LIFT_MUSIC, SoundCategory.RECORDS, .65f, 1f)
                }
                interactionGate.clearWalkIn(player.uniqueId)
                player.sendMessage(text("boarding", player, mapOf("floor" to floorName(destination, player))))
            }
        }
    }

    private fun safe(at: Location): Boolean = !at.block.isLiquid && at.block.isPassable &&
        at.clone().add(0.0, 1.0, 0.0).block.isPassable && at.clone().add(0.0, -1.0, 0.0).block.type.isSolid

    private fun unload(player: Player, destination: Location): Boolean {
        stopMusic(player)
        if (player.uniqueId in acknowledging || !safe(destination)) return false
        exiting += player.uniqueId
        try {
            if (player.isInsideVehicle && !player.leaveVehicle()) return false
            if (!teleports.authorize(player.uniqueId, destination) { player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN) }) return false
            if (player.world != destination.world || player.location.distanceSquared(destination) > .25) return false
            player.fallDistance = 0f
            val playerId = player.uniqueId
            acknowledging += playerId
            recovery.acknowledge(player).whenComplete { _, failure ->
                if (failure != null) plugin.logger.log(Level.SEVERE, "Mine lift recovery acknowledgement remains pending for $playerId", failure)
                tasks.runSync { acknowledging -= playerId }
            }
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
    override fun excludesEvent(point: Location): Boolean = settings.excludesEvent(point)

    override fun floors(): List<MineLiftAccess.FloorSnapshot> {
        val config = settings
        val world = Bukkit.getWorld(config.world) ?: return emptyList()
        return config.floors.map { MineLiftAccess.FloorSnapshot(it.id, it.y, it.exit.location(world)) }
    }

    @EventHandler(ignoreCancelled = true)
    fun interact(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (handleInteraction(event.player, event.rightClicked)) event.isCancelled = true
    }

    /** Interaction entities report left-clicks through Paper's pre-attack event, not damage. */
    @EventHandler(ignoreCancelled = false)
    fun attack(event: PrePlayerAttackEntityEvent) {
        // Vanilla pre-cancels entities that cannot receive normal attack logic. Keep
        // that cancellation for unrelated targets, while still routing our owned
        // Interaction target through the same cabin validation as a right-click.
        if (event.isCancelled && event.willAttack()) return
        if (handleInteraction(event.player, event.attacked)) event.isCancelled = true
    }

    private fun handleInteraction(player: Player, target: org.bukkit.entity.Entity): Boolean {
        val cabin = scene ?: return false
        val index = cabin.panels[target.uniqueId]
        val isCabinInteraction = cabin.ownsCabinInteraction(target)
        if (index == null && !isCabinInteraction) return false
        if (interactionGate.isDuplicate(player.uniqueId, Bukkit.getCurrentTick().toLong())) return true
        if (isCabinInteraction) {
            openCabinMenu(player)
        } else {
            open(player, requireNotNull(index))
        }
        return true
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
        interactionGate.clearWalkIn(event.player.uniqueId)
        if (event.player.uniqueId in riders && !teleports.isAuthorized(event.player.uniqueId, event.to)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun move(event: PlayerMoveEvent) {
        val player = event.player
        if (player.uniqueId in riders || player.uniqueId in exiting) return
        if (event is PlayerTeleportEvent) return

        val enteringShaft = settings.contains(event.to) && !settings.contains(event.from)
        if (!enteringShaft) {
            if (interactionGate.hasPendingWalkIn(player.uniqueId) && settings.movedAwayFromCabin(event.from, event.to)) {
                interactionGate.clearWalkIn(player.uniqueId)
            }
            return
        }

        val state = motion
        val floor = state?.takeIf { it.doorsOpen }?.floor
        val canEnter = floor != null && settings.entersCabin(event.from, event.to, floor) &&
            scene?.isDoorOpenAt(floor) == true && !maintenance.ownsAny() &&
            player.hasPermission("arcfarms.mine") && !player.isDead && !player.isInsideVehicle &&
            !hasRecovery(player.uniqueId) && nearCabin(player, floor)
        if (!canEnter) {
            event.isCancelled = true
            return
        }

        if (interactionGate.shouldOpenWalkInMenu(player.uniqueId, Bukkit.getCurrentTick().toLong())) {
            openCabinMenu(player)
        }
        // The cabin deck is a visual BlockDisplay. Keep this crossing blocked until
        // the existing destination action escrows, mounts, and boards the passenger.
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun damage(event: EntityDamageEvent) { if (event.entity.uniqueId in riders) event.isCancelled = true }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        stopMusic(event.player)
        interactionGate.clear(event.player.uniqueId)
        recover(event.player)
        if (riders.remove(event.player.uniqueId) != null) releaseRider(event.player.uniqueId, settings.id)
    }
    @EventHandler fun join(event: PlayerJoinEvent) { tasks.runLater(1) { recover(event.player) } }
    @EventHandler fun respawn(event: PlayerRespawnEvent) {
        interactionGate.clearWalkIn(event.player.uniqueId)
        tasks.runLater(1) { recover(event.player) }
    }
    @EventHandler fun worldLoad(event: WorldLoadEvent) {
        Bukkit.getOnlinePlayers().filter { recovery.contains(it.uniqueId) }.forEach(::recover)
        if (event.world.name == settings.world) tryStart()
    }
    @EventHandler fun chunkLoad(event: ChunkLoadEvent) {
        val currentScene = scene ?: return
        val chunk = event.chunk
        val entities = chunk.entities.iterator()
        capture({ !closed && scene === currentScene && chunk.isLoaded }) {
            if (!entities.hasNext()) true else { currentScene.cleanOrphan(entities.next()); !entities.hasNext() }
        }
    }

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
        if (args.size == 1) ((if (sender is Player && isNearAuthorized(sender)) floorArguments() else emptyList()) +
            if (sender.hasPermission("arcfarms.admin")) listOf("status") else emptyList()).filter { it.startsWith(args[0], true) } else emptyList()

    private fun floorName(index: Int, player: Player? = null) = text(
        "floors.${settings.floors[index].id}", player, mapOf("number" to Component.text(index + 1)),
    )
    private fun text(key: String, player: Player? = null, values: Map<String, Component> = emptyMap()) =
        locale.renderPath(settings.theme.messagePath(key), player, values).decoration(TextDecoration.ITALIC, false)

    private fun stopMusic(player: Player) {
        if (settings.theme == MineLiftTheme.CASINO) player.stopSound(CASINO_LIFT_MUSIC, SoundCategory.RECORDS)
    }

    private fun stopCabin() {
        preparationEpoch++
        preparing = false
        movementTask?.cancel()
        movementTask = null
        riders.keys.toList().mapNotNull(Bukkit::getPlayer).forEach { player ->
            stopMusic(player)
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
        stopCabin(); tasks.close(); dialogs.close(); interactionGate.clear(); HandlerList.unregisterAll(this)
    }
}

private const val CASINO_LIFT_MUSIC = "arc_casino:lift_local_forecast"
private const val CASINO_BOARDING_TICKS = 100

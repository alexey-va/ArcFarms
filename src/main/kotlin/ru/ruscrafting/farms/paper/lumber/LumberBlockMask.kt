package ru.ruscrafting.farms.paper.lumber

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.LumbermillSettings
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.UUID

/** Per-connection fake blocks only. Restoration always captures current terrain, never saved originals. */
internal class LumberBlockMask(private val tasks: WorksiteTaskPort, private val storage: WorksiteStatePort) {
    private class View(val player: Player) {
        var desired = emptySet<WorksitePosition>()
        val applied = mutableSetOf<WorksitePosition>()
        val confirmedHidden = mutableSetOf<WorksitePosition>()
        var revision = 0L
        var running = false
        var failures = 0
    }
    private val views = mutableMapOf<UUID, View>()

    fun apply(player: Player, positions: Collection<WorksitePosition>, refresh: Boolean = false) {
        val desired = if (player.hasPermission(LumbermillSettings.BETA_PERMISSION)) positions.toSet() else emptySet()
        val view = views[player.uniqueId]?.takeIf { it.player === player }
            ?: View(player).also { views[player.uniqueId] = it }
        if (view.desired == desired && (!refresh || view.running)) return
        view.desired = desired
        val revision = ++view.revision
        val operations = ((view.applied - desired) + desired).iterator()
        val token = tasks.lifecycleToken()
        view.running = true
        tasks.capture(token, { views[player.uniqueId] === view && view.revision == revision }) {
            if (!player.isOnline) { views.remove(player.uniqueId, view); true }
            else {
                var count = 0
                while (operations.hasNext() && count++ < 24) {
                    val point = operations.next()
                    val world = Bukkit.getWorld(point.world)
                    if (world == null || player.world !== world || !world.isChunkLoaded(point.x shr 4, point.z shr 4)) {
                        view.applied.remove(point)
                        view.confirmedHidden.remove(point)
                        continue
                    }
                    val hide = point in view.desired && player.hasPermission(LumbermillSettings.BETA_PERMISSION)
                    val location = Location(world, point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
                    val data = if (hide) Material.AIR.createBlockData() else world.getBlockAt(point.x, point.y, point.z).blockData
                    // Sending can fail after an uncertain write; retain restoration intent before hiding.
                    if (hide) view.applied += point
                    else view.confirmedHidden -= point // A failed restore may already have revealed native terrain.
                    player.sendBlockChange(location, data)
                    if (hide) view.confirmedHidden += point else view.applied -= point
                }
                if (!operations.hasNext()) {
                    if (view.desired.isEmpty() && view.applied.isEmpty()) views.remove(player.uniqueId, view)
                    true
                } else false
            }
        }.whenComplete { _, failure -> tasks.runSync(token) {
            if (view.revision == revision) view.running = false
            if (failure != null && failure !is java.util.concurrent.CancellationException && views[player.uniqueId] === view && view.revision == revision) {
                view.failures++
                if (view.failures == 1 || view.failures % 10 == 0)
                    storage.log(java.util.logging.Level.WARNING, "Lumber block mask will retry: player=${player.uniqueId} attempt=${view.failures}", failure)
                tasks.runLater(token, (view.failures * 20L).coerceAtMost(200L)) {
                    if (views[player.uniqueId] === view && view.revision == revision) apply(player, view.desired, refresh = true)
                }
            } else if (failure == null && view.revision == revision && view.failures > 0) {
                storage.log(java.util.logging.Level.INFO, "Lumber block mask recovered: player=${player.uniqueId} attempts=${view.failures}")
                view.failures = 0
            }
        } }
    }

    fun ready(player: Player, positions: Collection<WorksitePosition>): Boolean {
        if (!player.hasPermission(LumbermillSettings.BETA_PERMISSION) || !player.isOnline) return false
        if (positions.isEmpty()) return true
        val view = views[player.uniqueId]?.takeIf { it.player === player } ?: return false
        // Check each native chunk once; terrain capture and packet sends stay in the existing bounded job.
        val unavailable = positions.map { Triple(it.world, it.x shr 4, it.z shr 4) }.toSet().filterTo(hashSetOf()) { (name, x, z) ->
            val world = Bukkit.getWorld(name)
            world == null || player.world !== world || !world.isChunkLoaded(x, z)
        }
        if (unavailable.isNotEmpty()) positions.filter { Triple(it.world, it.x shr 4, it.z shr 4) in unavailable }.forEach {
            view.confirmedHidden -= it
            view.applied -= it
        }
        return view.desired.containsAll(positions) && view.confirmedHidden.containsAll(positions)
    }

    fun release(player: Player) { if (views[player.uniqueId]?.player === player) apply(player, emptySet(), refresh = true) }

    fun cleanup() {
        // Reload/shutdown may already have invalidated task tokens. Finish this bounded connection cleanup now.
        var failures = 0
        var firstFailure: Throwable? = null
        var firstPlayer: UUID? = null
        views.values.toList().forEach { view ->
            view.revision++
            val player = view.player
            if (player.isOnline) (view.applied + view.desired).forEach { point ->
                runCatching {
                    val world = Bukkit.getWorld(point.world)
                    if (world != null && player.world === world && world.isChunkLoaded(point.x shr 4, point.z shr 4))
                        player.sendBlockChange(Location(world, point.x.toDouble(), point.y.toDouble(), point.z.toDouble()),
                            world.getBlockAt(point.x, point.y, point.z).blockData)
                }.exceptionOrNull()?.let { failure ->
                    failures++
                    if (firstFailure == null) { firstFailure = failure; firstPlayer = player.uniqueId }
                }
            }
        }
        views.clear()
        firstFailure?.let { storage.log(java.util.logging.Level.WARNING,
            "Lumber block mask cleanup had failed restores: failures=$failures first_player=$firstPlayer", it) }
    }
}

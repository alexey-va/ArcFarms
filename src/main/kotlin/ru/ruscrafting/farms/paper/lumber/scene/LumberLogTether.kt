package ru.ruscrafting.farms.paper.lumber.scene

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberVisualView
import ru.ruscrafting.farms.paper.worksite.WorksiteCrankTethers
import java.util.UUID
import java.util.logging.Level

/** Reuses the worksite's native vanilla rope, privately visible to this round's beta audience. */
internal class LumberLogTether(private val plugin: Plugin) {
    private val tethers = WorksiteCrankTethers(NamespacedKey(plugin, "lumber_log_tether"), plugin)
    private var carrier: UUID? = null
    private var retryAt = 0L
    private var lastWarningAt = Long.MIN_VALUE

    fun render(view: LumberVisualView, viewers: Collection<Player>) {
        val player = view.state.cartCarrier?.let(Bukkit::getPlayer)?.takeIf { it in viewers }
        val pose = view.state.cart
        if (view.state.phase != LumberPhase.HAULING || player == null || pose == null || player.world.name != pose.world) {
            clear()
            return
        }
        if (carrier != player.uniqueId) clear()
        carrier = player.uniqueId
        val now = System.currentTimeMillis()
        if (now < retryAt) return
        val at = LumberSceneGeometry.leashAnchor(pose)
        val result = tethers.attach(SCOPE, player, Location(player.world, at.x, at.y, at.z), viewers)
        if (result.isFailure) {
            retryAt = now + 1_000L
            if (lastWarningAt == Long.MIN_VALUE || now - lastWarningAt >= 30_000L) {
                lastWarningAt = now
                plugin.logger.log(Level.WARNING, "Lumber log leash unavailable: round=${view.state.roundId} player=${player.uniqueId}; retry=1s", result.exceptionOrNull())
            }
        }
    }

    fun release(player: Player) { if (carrier == player.uniqueId) clear() }
    fun clear() { tethers.clear(SCOPE); carrier = null; retryAt = 0L }

    private companion object { const val SCOPE = "lumber-log" }
}

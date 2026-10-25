package ru.ruscrafting.farms.paper.lumber.sawmill

import com.sk89q.worldguard.bukkit.event.entity.UseEntityEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin

/** Loaded only with WorldGuard present; grants public use of this scene's exact live carrier. */
internal class CedarSawmillPublicUse(
    plugin: Plugin,
    private val controller: CedarSawmillController,
) : Listener, AutoCloseable {
    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onUseEntity(event: UseEntityEvent) {
        if (event.isCancelled) return
        val original = event.originalEvent as? PlayerInteractEntityEvent ?: return
        if (original.isCancelled || original.hand != EquipmentSlot.HAND ||
            original.rightClicked !== event.entity || event.cause.firstPlayer !== original.player
        ) return
        if (controller.allowsPublicUse(original.player, event.entity)) event.setAllowed(true)
    }

    override fun close() = HandlerList.unregisterAll(this)
}

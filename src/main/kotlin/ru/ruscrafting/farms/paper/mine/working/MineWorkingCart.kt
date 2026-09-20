package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Minecart
import org.bukkit.entity.minecart.RideableMinecart
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.extraction.MineCartEffects
import java.util.UUID

/** Real rail-test vehicle, owned by the same stage lifecycle as the task markers. */
internal class MineWorkingCart(plugin: Plugin) : MineCartEffects {
    private val key = NamespacedKey(plugin, "mine_working_vehicle")
    private val carts = mutableMapOf<String, UUID>()
    private val positions = mutableMapOf<String, WorksitePosition>()
    override fun show(runtime: MineRuntime, position: WorksitePosition, yaw: Float) {
        val world = Bukkit.getWorld(position.world) ?: return
        val at = Location(world, position.x + .5, position.y + 1.1, position.z + .5, yaw, 0f)
        val cart = carts[runtime.settings.id]?.let(Bukkit::getEntity) as? Minecart
        if (cart?.isValid == true) cart.teleport(at, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN,
            io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS)
        else {
            hide(runtime.settings.id)
            val spawned = world.spawn(at, RideableMinecart::class.java) {
                it.isPersistent = false
                it.isInvulnerable = true
                it.isGlowing = true
                it.setGravity(false)
                it.maxSpeed = 0.0
                it.persistentDataContainer.set(key, PersistentDataType.STRING, runtime.settings.id)
            }
            carts[runtime.settings.id] = spawned.uniqueId
        }
        positions[runtime.settings.id] = position
    }
    override fun hide(zoneId: String) {
        carts.remove(zoneId)?.let(Bukkit::getEntity)?.remove()
        positions.remove(zoneId)
    }
    override fun position(zoneId: String) = positions[zoneId]
    override fun reconcileChunk(chunk: org.bukkit.Chunk) {
        chunk.entities.filter { it.persistentDataContainer.has(key) && it.uniqueId !in carts.values }.forEach { it.remove() }
    }
}

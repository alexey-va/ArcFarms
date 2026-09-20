package ru.ruscrafting.farms.paper.mine.expedition

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f

/** Small, non-colliding controls. The cave and machinery are actual world blocks. */
internal class MineExpeditionMarkers(plugin: Plugin) {
    data class Target(val id: String, val location: Location, val material: Material, val label: Component,
        val block: Boolean = false)
    private data class Marker(val display: Display, val hitbox: Interaction, val label: TextDisplay,
        var target: Target)
    private val key = NamespacedKey(plugin, "mine_expedition_control")
    private val markers = linkedMapOf<String, Marker>()

    fun identity(entity: Entity): String? = entity.persistentDataContainer.get(key, PersistentDataType.STRING)

    fun reconcile(scope: String, targets: List<Target>) {
        val expected = targets.mapTo(hashSetOf()) { "$scope/${it.id}" }
        markers.keys.filter { it.startsWith("$scope/") && it !in expected }.forEach(::remove)
        for (target in targets) {
            val id = "$scope/${target.id}"
            val old = markers[id]
            if (old == null || !old.display.isValid || old.target.material != target.material || old.target.block != target.block) {
                remove(id)
                markers[id] = spawn(id, target)
            } else {
                if (old.target.location != target.location) {
                    old.display.teleport(displayLocation(target))
                    old.hitbox.teleport(target.location)
                    old.label.teleport(target.location.clone().add(0.0, 1.65, 0.0))
                }
                if (old.target.label != target.label) old.label.text(target.label)
                old.target = target
            }
        }
    }

    fun nearest(location: Location, scope: String): Target? = markers.entries.asSequence()
        .filter { it.key.startsWith("$scope/") && it.value.target.location.world === location.world }
        .map { it.value.target }.filter { it.location.distanceSquared(location) <= 6.25 }
        .minByOrNull { it.location.distanceSquared(location) }

    fun clear(scope: String) = markers.keys.filter { it.startsWith("$scope/") }.forEach(::remove)
    fun cleanup() = markers.keys.toList().forEach(::remove)

    fun rotate(scope: String, id: String, radians: Double) {
        val display = markers["$scope/$id"]?.display as? ItemDisplay ?: return
        display.interpolationDuration = 2
        display.interpolationDelay = 0
        display.transformation = Transformation(Vector3f(), AxisAngle4f(radians.toFloat(), 0f, 1f, 0f),
            Vector3f(0.85f), AxisAngle4f())
    }

    private fun spawn(id: String, target: Target): Marker {
        val world = target.location.world
        val visual: Display = if (target.block) {
            world.spawn(displayLocation(target), BlockDisplay::class.java) { entity ->
                entity.block = target.material.createBlockData()
                entity.transformation = Transformation(Vector3f(-0.005f), AxisAngle4f(), Vector3f(1.01f), AxisAngle4f())
                configure(entity)
            }
        } else world.spawn(displayLocation(target), ItemDisplay::class.java) { entity ->
            entity.setItemStack(ItemStack(target.material))
            entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            entity.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(0.85f), AxisAngle4f())
            configure(entity)
        }
        val hitbox = world.spawn(target.location, Interaction::class.java) { entity ->
            entity.interactionWidth = 1.15f
            entity.interactionHeight = 1.5f
            entity.isResponsive = true
            entity.isPersistent = false
            entity.persistentDataContainer.set(key, PersistentDataType.STRING, id)
        }
        val label = world.spawn(target.location.clone().add(0.0, 1.65, 0.0), TextDisplay::class.java) { entity ->
            entity.text(target.label)
            entity.billboard = Display.Billboard.CENTER
            entity.brightness = Display.Brightness(15, 15)
            entity.viewRange = 0.7f
            entity.backgroundColor = Color.fromARGB(130, 12, 18, 24)
            entity.isShadowed = true
            entity.isPersistent = false
            entity.lineWidth = 190
        }
        return Marker(visual, hitbox, label, target)
    }

    private fun configure(entity: Display) {
        entity.isPersistent = false
        entity.isGlowing = true
        entity.glowColorOverride = Color.fromRGB(255, 187, 77)
        entity.brightness = Display.Brightness(15, 15)
        entity.viewRange = 5f
        entity.teleportDuration = 2
    }

    private fun displayLocation(target: Target): Location = target.location.clone().add(
        if (target.block) -0.5 else 0.0, if (target.block) 0.0 else 0.75, if (target.block) -0.5 else 0.0)

    private fun remove(id: String) {
        val marker = markers.remove(id) ?: return
        marker.display.remove()
        marker.hitbox.remove()
        marker.label.remove()
    }
}

package ru.ruscrafting.farms.paper.worksite

import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.joml.AxisAngle4f
import org.joml.Vector3f
import org.bukkit.util.Transformation

/** Stateless ItemDisplay renderer for worksite cargo owned by a caller's lease map. */
internal class WorksiteCarriedDisplayRenderer {
    fun spawn(
        player: Player,
        item: ItemStack,
        transform: ItemDisplay.ItemDisplayTransform,
        scale: Float,
        viewRange: Float,
        forwardOffset: Double,
        yOffset: Double,
        configure: (ItemDisplay) -> Unit = {},
    ): ItemDisplay = player.world.spawn(carriedLocation(player, forwardOffset, yOffset), ItemDisplay::class.java) { display ->
        display.setItemStack(item)
        display.itemDisplayTransform = transform
        display.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(scale, scale, scale), AxisAngle4f())
        display.viewRange = viewRange
        display.teleportDuration = 1
        display.isGlowing = true
        display.isPersistent = false
        configure(display)
    }

    fun move(display: ItemDisplay, player: Player, forwardOffset: Double, yOffset: Double) {
        if (display.world === player.world) display.teleport(carriedLocation(player, forwardOffset, yOffset))
    }

    fun remove(display: ItemDisplay) { display.remove() }

    private fun carriedLocation(player: Player, forwardOffset: Double, yOffset: Double) =
        WorksiteCarryable.carriedLocation(player, forwardOffset, yOffset)
}

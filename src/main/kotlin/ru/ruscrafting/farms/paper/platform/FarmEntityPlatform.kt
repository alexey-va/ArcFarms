package ru.ruscrafting.farms.paper.platform

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.TextDisplay

internal data class FarmTextDisplayStyle(
    val billboard: Display.Billboard = Display.Billboard.VERTICAL,
    val alignment: TextDisplay.TextAlignment = TextDisplay.TextAlignment.CENTER,
    val lineWidth: Int = 180,
    val backgroundColor: Color = Color.fromARGB(0, 0, 0, 0),
    val shadowed: Boolean = true,
    val viewRange: Float,
    val persistent: Boolean = false,
)

/** Entity mutations whose complete Paper behavior is outside gameplay ownership. */
internal interface FarmEntityPlatform {
    fun configureTextDisplay(entity: TextDisplay, text: Component, style: FarmTextDisplayStyle)

    fun setRemoveWhenFarAway(entity: LivingEntity, value: Boolean)

    fun ejectPassengers(entity: Entity): Boolean
}

internal object PaperFarmEntityPlatform : FarmEntityPlatform {
    override fun configureTextDisplay(entity: TextDisplay, text: Component, style: FarmTextDisplayStyle) {
        entity.text(text)
        entity.billboard = style.billboard
        entity.alignment = style.alignment
        entity.lineWidth = style.lineWidth
        entity.backgroundColor = style.backgroundColor
        entity.isShadowed = style.shadowed
        entity.viewRange = style.viewRange
        entity.isPersistent = style.persistent
    }

    override fun setRemoveWhenFarAway(entity: LivingEntity, value: Boolean) {
        entity.removeWhenFarAway = value
    }

    override fun ejectPassengers(entity: Entity): Boolean = entity.eject()
}

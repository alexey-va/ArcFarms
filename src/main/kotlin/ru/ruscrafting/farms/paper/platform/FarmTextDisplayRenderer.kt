package ru.ruscrafting.farms.paper.platform

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.entity.Display
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

/** Applies the complete visual contract of one farm TextDisplay. */
internal fun interface FarmTextDisplayRenderer {
    fun render(entity: TextDisplay, text: Component, style: FarmTextDisplayStyle)
}

internal object PaperFarmTextDisplayRenderer : FarmTextDisplayRenderer {
    override fun render(entity: TextDisplay, text: Component, style: FarmTextDisplayStyle) {
        entity.text(text)
        entity.billboard = style.billboard
        entity.alignment = style.alignment
        entity.lineWidth = style.lineWidth
        entity.backgroundColor = style.backgroundColor
        entity.isShadowed = style.shadowed
        entity.viewRange = style.viewRange
        entity.isPersistent = style.persistent
    }
}

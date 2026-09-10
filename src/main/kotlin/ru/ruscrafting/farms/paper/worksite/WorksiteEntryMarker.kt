package ru.ruscrafting.farms.paper.worksite

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.FarmCareVisualSettings
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.farm.care.bukkit
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle

/** Activity-neutral native marker: item display, label and interaction hitbox. */
internal class WorksiteEntryMarker(
    private val locale: ArcFarmsLocale,
    private val textDisplays: FarmTextDisplayRenderer,
) {
    private val labelStyle = FarmTextDisplayStyle(viewRange = 0.8f)

    fun spawn(
        location: Location,
        visual: FarmCareVisualSettings,
        labelPath: String,
        glowing: Boolean,
        viewRange: Float,
        mark: (Entity) -> Unit,
        values: Map<String, net.kyori.adventure.text.Component> = emptyMap(),
    ): List<Entity> {
        val result = mutableListOf<Entity>()
        val material = MaterialRules.material(visual.material)
        if (material != org.bukkit.Material.AIR) {
            val stack = ItemStack(material).also { item ->
                if (visual.customModelData > 0) item.itemMeta = item.itemMeta.also { it.setCustomModelData(visual.customModelData) }
            }
            result += location.world.spawn(location.clone().add(0.0, visual.displayYOffset, 0.0), ItemDisplay::class.java) { entity ->
                entity.setItemStack(stack)
                entity.itemDisplayTransform = visual.displayTransform.bukkit
                val current = entity.transformation
                entity.transformation = Transformation(
                    current.translation, AxisAngle4f(current.leftRotation), Vector3f(visual.displayScale), AxisAngle4f(current.rightRotation),
                )
                entity.viewRange = viewRange
                entity.isGlowing = glowing
                entity.isPersistent = false
                mark(entity)
            }
        }
        result += location.world.spawn(location.clone().add(0.0, 1.85, 0.0), TextDisplay::class.java) { entity ->
            textDisplays.render(entity, locale.renderPath(labelPath, values = values), labelStyle.copy(viewRange = viewRange))
            mark(entity)
        }
        result += location.world.spawn(location.clone().add(0.0, 0.55, 0.0), Interaction::class.java) { entity ->
            entity.interactionWidth = 1.45f
            entity.interactionHeight = 1.7f
            entity.isResponsive = true
            entity.isPersistent = false
            mark(entity)
        }
        return result
    }
}

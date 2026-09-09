package ru.ruscrafting.farms.paper.farm.expedition

import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.FarmCareVisualSettings
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.farm.FarmFieldPoiVisibility
import ru.ruscrafting.farms.paper.farm.care.FarmCarePresentation
import ru.ruscrafting.farms.paper.farm.care.bukkit
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayRenderer
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle
import org.bukkit.Location

/** Owns the common visible, clickable surface entrance marker. */
internal class FarmUndergroundEntryMarker(
    settings: () -> ArcFarmsConfig,
    private val locale: ArcFarmsLocale,
    private val textDisplays: FarmTextDisplayRenderer,
) {
    private val presentation = FarmCarePresentation(settings)
    private val labelStyle = FarmTextDisplayStyle(viewRange = 0.8f)

    fun spawn(
        runtime: FarmRuntime,
        location: Location,
        visual: FarmCareVisualSettings,
        labelPath: String,
        glowing: Boolean,
        mark: (Entity) -> Unit,
    ): List<Entity> {
        val result = mutableListOf<Entity>()
        val material = MaterialRules.material(visual.material)
        val viewRange = FarmFieldPoiVisibility.fullField(runtime.settings.displayViewRange)
        if (material != org.bukkit.Material.AIR) {
            val stack = ItemStack(material).also { item ->
                if (visual.customModelData > 0) item.itemMeta = item.itemMeta.also { it.setCustomModelData(visual.customModelData) }
            }
            result += location.world.spawn(location.clone().add(0.0, visual.displayYOffset, 0.0), ItemDisplay::class.java) { entity ->
                entity.setItemStack(stack)
                entity.itemDisplayTransform = visual.displayTransform.bukkit
                presentation.scale(entity, visual.displayScale)
                entity.viewRange = viewRange
                entity.isGlowing = glowing
                entity.isPersistent = false
                mark(entity)
            }
        }
        result += location.world.spawn(location.clone().add(0.0, 1.85, 0.0), TextDisplay::class.java) { entity ->
            textDisplays.render(entity, locale.renderPath(labelPath), labelStyle.copy(viewRange = viewRange))
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

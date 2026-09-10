package ru.ruscrafting.farms.paper.mine.extraction

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.FarmItemDisplayTransform
import ru.ruscrafting.farms.config.MineCartVisualSettings
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.mine.MineRuntime
import java.util.UUID

internal interface MineCartEffects {
    fun show(runtime: MineRuntime, position: WorksitePosition, yaw: Float)
    fun hide(zoneId: String)
    fun position(zoneId: String): WorksitePosition?
    fun reconcileChunk(chunk: org.bukkit.Chunk) = Unit
}

/** Keeps the active extraction scene stable while applying live visual configuration on every reconcile. */
internal class PaperMineCartEffects(
    private val plugin: Plugin,
    namespace: String = "mine_cart_zone",
    private val visualOverride: MineCartVisualSettings? = null,
) : MineCartEffects {
    private val displays = mutableMapOf<String, UUID>()
    private val interactions = mutableMapOf<String, UUID>()
    private val positions = mutableMapOf<String, WorksitePosition>()
    private val zoneKey = NamespacedKey(plugin, namespace)

    override fun show(runtime: MineRuntime, position: WorksitePosition, yaw: Float) {
        val world = Bukkit.getWorld(position.world) ?: return
        val visual = visualOverride ?: runtime.settings.cartVisual
        val interactionLocation = Location(world, position.x + 0.5, position.y + 1.0, position.z + 0.5, yaw, 0f)
        val displayLocation = interactionLocation.clone().add(0.0, visual.yOffset, 0.0)
        val display = displays[runtime.settings.id]?.let(Bukkit::getEntity) as? ItemDisplay
        val interaction = interactions[runtime.settings.id]?.let(Bukkit::getEntity) as? Interaction
        if (display?.isValid == true && interaction?.isValid == true) {
            display.teleport(displayLocation)
            interaction.teleport(interactionLocation)
            applyVisual(display, visual)
            applyHitbox(interaction, visual)
        } else {
            hide(runtime.settings.id)
            val spawnedDisplay = world.spawn(displayLocation, ItemDisplay::class.java) { entity ->
                applyVisual(entity, visual)
                entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
            }
            val spawnedInteraction = world.spawn(interactionLocation, Interaction::class.java) { entity ->
                applyHitbox(entity, visual)
                entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
            }
            displays[runtime.settings.id] = spawnedDisplay.uniqueId
            interactions[runtime.settings.id] = spawnedInteraction.uniqueId
        }
        positions[runtime.settings.id] = position
    }

    override fun hide(zoneId: String) {
        displays.remove(zoneId)?.let(Bukkit::getEntity)?.remove()
        interactions.remove(zoneId)?.let(Bukkit::getEntity)?.remove()
        positions.remove(zoneId)
    }

    override fun position(zoneId: String): WorksitePosition? = positions[zoneId]

    override fun reconcileChunk(chunk: org.bukkit.Chunk) {
        chunk.entities.forEach { entity ->
            val zone = entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return@forEach
            when (entity) {
                is ItemDisplay -> reconcileEntity(displays, zone, entity)
                is Interaction -> reconcileEntity(interactions, zone, entity)
                else -> entity.remove()
            }
        }
    }

    private fun reconcileEntity(owners: MutableMap<String, UUID>, zone: String, entity: Entity) {
        val current = owners[zone]
        if (current == null || Bukkit.getEntity(current)?.isValid != true) owners[zone] = entity.uniqueId
        else if (current != entity.uniqueId) entity.remove()
    }

    private fun applyVisual(display: ItemDisplay, visual: MineCartVisualSettings) {
        display.isPersistent = false
        display.setItemStack(ItemStack(MaterialRules.material(visual.material)).also { stack ->
            stack.editMeta { meta ->
                if (visual.customModelData > 0) meta.setCustomModelData(visual.customModelData)
                visual.itemModel?.let { model -> meta.setItemModel(requireNotNull(NamespacedKey.fromString(model))) }
            }
        })
        display.itemDisplayTransform = when (visual.displayTransform) {
            FarmItemDisplayTransform.GROUND -> ItemDisplay.ItemDisplayTransform.GROUND
            FarmItemDisplayTransform.FIXED -> ItemDisplay.ItemDisplayTransform.FIXED
            FarmItemDisplayTransform.HEAD -> ItemDisplay.ItemDisplayTransform.HEAD
        }
        display.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(visual.scale), AxisAngle4f())
        display.viewRange = visual.viewRange
    }

    private fun applyHitbox(interaction: Interaction, visual: MineCartVisualSettings) {
        interaction.isPersistent = false
        interaction.interactionWidth = visual.interactionWidth
        interaction.interactionHeight = visual.interactionHeight
        interaction.isResponsive = true
    }
}

internal class MineCartScene(private val effects: MineCartEffects) {
    fun reconcile(runtime: MineRuntime, route: MineExtractionRoute?) {
        if (runtime.state.phase != ru.ruscrafting.farms.domain.MinePhase.EXTRACTION || route == null) {
            effects.hide(runtime.settings.id)
            return
        }
        val current = route.sample(runtime.state.routeIndex)
        val next = route.sample((runtime.state.routeIndex + 1).coerceAtMost(route.finalIndex))
        val yaw = Math.toDegrees(kotlin.math.atan2(-(next.x - current.x).toDouble(), (next.z - current.z).toDouble())).toFloat()
        effects.show(runtime, current, yaw)
    }

    fun position(zoneId: String): WorksitePosition? = effects.position(zoneId)
    fun cleanup(zoneId: String) = effects.hide(zoneId)
    fun reconcileChunk(chunk: org.bukkit.Chunk) = effects.reconcileChunk(chunk)
}

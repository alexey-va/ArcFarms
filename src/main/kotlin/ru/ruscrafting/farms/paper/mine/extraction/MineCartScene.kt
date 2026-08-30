package ru.ruscrafting.farms.paper.mine.extraction

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
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
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import java.util.UUID

internal interface MineCartEffects {
    fun show(runtime: MineRuntime, position: WorksitePosition, yaw: Float)
    fun hide(zoneId: String)
    fun position(zoneId: String): WorksitePosition?
    fun reconcileChunk(chunk: org.bukkit.Chunk) = Unit
}

/**
 * IA contact proof: elitecreatures:medieval_market_decoration_v1_cart_2, PAPER:10747,
 * model SHA-256 06fcde3cc7fdd62b8bcd5920dbd806c3867dd719abcae279c8fc11f58a3d425c.
 * GROUND at entity scale 3 has support Y=-0.5625, hence entity Y=surface+0.5625.
 */
internal class PaperMineCartEffects(private val plugin: Plugin) : MineCartEffects {
    private val displays = mutableMapOf<String, UUID>()
    private val interactions = mutableMapOf<String, UUID>()
    private val positions = mutableMapOf<String, WorksitePosition>()
    private val zoneKey = NamespacedKey(plugin, "mine_cart_zone")

    override fun show(runtime: MineRuntime, position: WorksitePosition, yaw: Float) {
        val world = Bukkit.getWorld(position.world) ?: return
        val entityLocation = Location(
            world, position.x + 0.5, position.y + 1.0 + GROUND_Y_OFFSET, position.z + 0.5, yaw, 0f,
        )
        val display = displays[runtime.settings.id]?.let(Bukkit::getEntity) as? ItemDisplay
        val interaction = interactions[runtime.settings.id]?.let(Bukkit::getEntity) as? Interaction
        if (display?.isValid == true && interaction?.isValid == true) {
            display.teleport(entityLocation)
            interaction.teleport(entityLocation.clone().subtract(0.0, GROUND_Y_OFFSET, 0.0))
        } else {
            hide(runtime.settings.id)
            val item = ItemStack(Material.PAPER).also { stack -> stack.editMeta { it.setCustomModelData(CUSTOM_MODEL_DATA) } }
            val spawnedDisplay = world.spawn(entityLocation, ItemDisplay::class.java) { entity ->
                entity.setItemStack(item)
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
                entity.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(3f), AxisAngle4f())
                entity.viewRange = 48f
                entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
            }
            val spawnedInteraction = world.spawn(
                entityLocation.clone().subtract(0.0, GROUND_Y_OFFSET, 0.0), Interaction::class.java,
            ) { entity ->
                entity.interactionWidth = 2.4f
                entity.interactionHeight = 1.8f
                entity.isResponsive = true
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

    private companion object {
        const val CUSTOM_MODEL_DATA = 10_747
        const val GROUND_Y_OFFSET = 0.5625
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

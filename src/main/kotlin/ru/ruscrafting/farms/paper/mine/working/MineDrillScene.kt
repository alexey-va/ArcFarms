package ru.ruscrafting.farms.paper.mine.working

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Minecart
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.MineWorkingStage
import ru.ruscrafting.farms.domain.MineWorkingState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.worksite.WorksiteBlockGlow
import java.util.UUID
import kotlin.math.PI

/**
 * The mechanized visual for the authored excavation front.
 *
 * The cart is a real Minecart so the player sees the expected vehicle shape,
 * while the drill head remains a separate nonpersistent BlockDisplay. The root
 * controller owns right-click handling and cancels the event before Minecraft
 * can mount the operator.
 */
internal class MineDrillScene(plugin: Plugin) {
    private data class Entities(
        val cart: Minecart,
        val head: BlockDisplay,
        val control: Interaction,
        var position: WorksitePosition,
        var lastEffectAt: Long? = null,
    )

    private val zoneKey = NamespacedKey(plugin, "mine_working_drill_zone")
    private val roleKey = NamespacedKey(plugin, "mine_working_drill_role")
    private val worldKey = NamespacedKey(plugin, "mine_working_drill_world")
    private val xKey = NamespacedKey(plugin, "mine_working_drill_x")
    private val yKey = NamespacedKey(plugin, "mine_working_drill_y")
    private val zKey = NamespacedKey(plugin, "mine_working_drill_z")
    private val scenes = mutableMapOf<String, Entities>()

    /** Reconciles the cart and head at the current excavation front. */
    fun reconcile(runtime: MineRuntime, scene: MineWorkingScene, running: Boolean, now: Long) {
        val working = runtime.state.incident?.working
        if (working == null || working.stage != MineWorkingStage.EXCAVATE || scene.plan.excavation.isEmpty()) {
            cleanup(runtime.settings.id)
            return
        }
        val position = currentDrillPosition(scene.plan, working)
        val world = runtime.region.world
        val cartLocation = position.location(world).add(0.0, CART_Y_OFFSET, 0.0)
        val current = scenes[runtime.settings.id]
            ?.takeIf { it.cart.isValid && it.head.isValid && it.control.isValid && it.cart.world === world }
            ?: run {
                scenes.remove(runtime.settings.id)?.let(::remove)
                spawn(runtime, position, cartLocation)
            }
        current.position = position
        move(current, cartLocation)
        mark(current.cart, runtime, position, CART_ROLE)
        mark(current.control, runtime, position, CONTROL_ROLE)
        mark(current.head, runtime, position, HEAD_ROLE)
        animate(current, running, now)
        if (running && current.lastEffectAt?.let { now - it >= EFFECT_INTERVAL_MILLIS } != false) {
            effects(current)
            current.lastEffectAt = now
        }
    }

    /** Returns the zone and authored block position represented by a drill control. */
    fun target(entity: Entity): Pair<String, WorksitePosition>? {
        val zone = entity.persistentDataContainer.get(zoneKey, PersistentDataType.STRING) ?: return null
        val role = entity.persistentDataContainer.get(roleKey, PersistentDataType.STRING)
        if (role !in setOf(CART_ROLE, CONTROL_ROLE)) return null
        val world = entity.persistentDataContainer.get(worldKey, PersistentDataType.STRING) ?: return null
        val x = entity.persistentDataContainer.get(xKey, PersistentDataType.INTEGER) ?: return null
        val y = entity.persistentDataContainer.get(yKey, PersistentDataType.INTEGER) ?: return null
        val z = entity.persistentDataContainer.get(zKey, PersistentDataType.INTEGER) ?: return null
        return zone to WorksitePosition(world, x, y, z)
    }

    fun owns(entity: Entity): Boolean = entity.persistentDataContainer.has(zoneKey, PersistentDataType.STRING)

    fun cleanup(zoneId: String) {
        scenes.remove(zoneId)?.let(::remove)
    }

    /** Removes orphaned nonpersistent entities left by a reload or plugin stop. */
    fun reconcileLoaded() {
        Bukkit.getWorlds().forEach { world ->
            world.loadedChunks.forEach { chunk ->
                chunk.entities.filter(::owns).forEach(Entity::remove)
            }
        }
        scenes.clear()
    }

    private fun spawn(runtime: MineRuntime, position: WorksitePosition, location: Location): Entities {
        val world = location.world ?: error("Drill location has no world")
        val zone = runtime.settings.id
        val cart = world.spawn(location, Minecart::class.java) { entity ->
            entity.isPersistent = false
            entity.isInvulnerable = true
            entity.isGlowing = true
            entity.isSlowWhenEmpty = true
            entity.maxSpeed = 0.0
            entity.velocity = org.bukkit.util.Vector()
            mark(entity, runtime, position, CART_ROLE)
        }
        val head = world.spawn(location.clone().add(0.0, HEAD_Y_OFFSET, 0.0), BlockDisplay::class.java) { entity ->
            WorksiteBlockGlow.apply(entity, Material.DRIPSTONE_BLOCK.createBlockData(), HEAD_GLOW)
            entity.isPersistent = false
            entity.viewRange = 8f
            mark(entity, runtime, position, HEAD_ROLE)
        }
        val control = world.spawn(location.clone().add(0.0, CONTROL_Y_OFFSET, 0.0), Interaction::class.java) { entity ->
            entity.interactionWidth = 1.8f
            entity.interactionHeight = 1.35f
            entity.isResponsive = true
            entity.isPersistent = false
            mark(entity, runtime, position, CONTROL_ROLE)
        }
        return Entities(cart, head, control, position).also { scenes[zone] = it }
    }

    private fun move(entities: Entities, location: Location) {
        entities.cart.teleport(location)
        entities.cart.velocity = org.bukkit.util.Vector()
        entities.head.teleport(location.clone().add(0.0, HEAD_Y_OFFSET, 0.0))
        entities.control.teleport(location.clone().add(0.0, CONTROL_Y_OFFSET, 0.0))
    }

    private fun animate(entities: Entities, running: Boolean, now: Long) {
        val radians = if (running) ((now % ROTATION_PERIOD_MILLIS).toDouble() / ROTATION_PERIOD_MILLIS) * 2.0 * PI else 0.0
        entities.head.transformation = Transformation(
            Vector3f(-0.34f, -0.34f, -0.34f),
            AxisAngle4f(radians.toFloat(), 0f, 1f, 0f),
            Vector3f(0.68f),
            AxisAngle4f(),
        )
        entities.cart.isGlowing = true
        entities.head.isGlowing = true
    }

    private fun effects(entities: Entities) {
        val world = entities.cart.world ?: return
        val at = entities.head.location.clone().add(0.0, 0.15, 0.0)
        world.spawnParticle(Particle.BLOCK, at, 6, 0.18, 0.18, 0.18, 0.03, Material.STONE.createBlockData())
        world.playSound(entities.cart.location, Sound.BLOCK_GRINDSTONE_USE, 0.32f, 0.72f)
    }

    private fun remove(entities: Entities) {
        entities.cart.remove()
        entities.head.remove()
        entities.control.remove()
    }

    private fun mark(entity: Entity, runtime: MineRuntime, position: WorksitePosition, role: String) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role)
        entity.persistentDataContainer.set(worldKey, PersistentDataType.STRING, position.world)
        entity.persistentDataContainer.set(xKey, PersistentDataType.INTEGER, position.x)
        entity.persistentDataContainer.set(yKey, PersistentDataType.INTEGER, position.y)
        entity.persistentDataContainer.set(zKey, PersistentDataType.INTEGER, position.z)
    }

    private fun currentDrillPosition(plan: MineWorkingPlan, working: MineWorkingState): WorksitePosition {
        val ordered = plan.excavation.withIndex().sortedWith(
            compareBy<IndexedValue<WorksitePosition>>({ forwardDistance(plan.placement, it.value) }, { it.value.x }, { it.value.z }),
        )
        val front = ordered.firstOrNull { it.index !in working.completed } ?: ordered.last()
        // The previous face is one block behind the nearest remaining face. If
        // that face is the first one, the authored entrance is the safe anchor.
        val previousIndex = ordered.indexOf(front) - 1
        return if (previousIndex >= 0) ordered[previousIndex].value else plan.placement.position(0, 1, 0)
    }

    private fun forwardDistance(placement: ru.ruscrafting.farms.domain.MineWorkingPlacement, position: WorksitePosition): Int = when (placement.direction) {
        0 -> position.z - placement.entrance.z
        1 -> placement.entrance.x - position.x
        2 -> placement.entrance.z - position.z
        else -> position.x - placement.entrance.x
    }

    private companion object {
        const val CART_ROLE = "cart"
        const val CONTROL_ROLE = "control"
        const val HEAD_ROLE = "head"
        const val CART_Y_OFFSET = 0.1
        const val HEAD_Y_OFFSET = 0.72
        const val CONTROL_Y_OFFSET = 0.45
        const val EFFECT_INTERVAL_MILLIS = 250L
        const val ROTATION_PERIOD_MILLIS = 900L
        val HEAD_GLOW = Color.fromRGB(255, 115, 48)
    }
}

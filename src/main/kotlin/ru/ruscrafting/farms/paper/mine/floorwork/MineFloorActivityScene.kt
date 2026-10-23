package ru.ruscrafting.farms.paper.mine.floorwork

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Interaction
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.farms.domain.MineIncidentType
import kotlin.math.cos
import kotlin.math.sin

/** Only the two floor machines' owned packet parts and non-persistent hitboxes. */
internal class MineFloorActivityScene(
    private val plugin: Plugin,
    private val renderer: PaperPacketDisplays,
    val zoneId: String,
    val type: MineIncidentType,
    val anchor: Location,
    val sequence: Long,
) {
    private val parts = linkedMapOf<String, PacketBlockDisplay>()
    val controls = linkedMapOf<String, Interaction>()

    fun spawn() {
        try {
            MineFloorActivityGeometry.controls(type).forEach { (role, point) ->
                controls[role] = anchor.world.spawn(at(anchor, point).subtract(0.0, .55, 0.0), Interaction::class.java) {
                    it.interactionWidth = 0f
                    it.interactionHeight = 1.1f
                    it.isResponsive = true
                    it.isPersistent = false
                    it.persistentDataContainer.set(NamespacedKey(plugin, OWNER_KEY), PersistentDataType.STRING, zoneId)
                }
            }
            render(MineFloorActivityGeometry.Pose(), null)
        } catch (failure: Throwable) {
            remove()
            throw failure
        }
    }

    fun render(pose: MineFloorActivityGeometry.Pose, activeControl: String?) {
        val yaw = Math.toRadians(-anchor.yaw.toDouble()).toFloat()
        val root = Quaternionf().rotationY(yaw)
        MineFloorActivityGeometry.parts(type, pose).forEach { part ->
            val display = parts.getOrPut(part.id) {
                renderer.spawnBlock(anchor, part.material.createBlockData()).apply {
                    viewRange = 2f
                    interpolationDuration = 2
                    glowColorOverride = Color.fromRGB(255, 191, 78)
                }
            }
            val rotation = Quaternionf(root).mul(part.rotation)
            val center = root.transform(Vector3f(part.center))
            val corner = rotation.transform(Vector3f(part.size).mul(-.5f)).add(center)
            val transform = Transformation(corner, rotation, Vector3f(part.size), Quaternionf())
            if (display.transformation != transform) {
                display.interpolationDelay = 0
                display.transformation = transform
            }
            display.isVisibleByDefault = part.visible
            display.isGlowing = activeControl != null && part.control == activeControl
        }
        controls.forEach { (role, hitbox) ->
            hitbox.interactionWidth = if (role == activeControl) 1.15f else 0f
        }
    }

    fun role(entityId: java.util.UUID): String? = controls.entries.firstOrNull { it.value.uniqueId == entityId }?.key

    fun remove() {
        controls.values.forEach(Interaction::remove)
        parts.values.forEach(PacketBlockDisplay::remove)
        controls.clear()
        parts.clear()
    }

    companion object {
        const val OWNER_KEY = "mine_floor_activity"

        fun at(anchor: Location, local: Vector3f): Location {
            val yaw = Math.toRadians(anchor.yaw.toDouble())
            return anchor.clone().add(
                cos(yaw) * local.x - sin(yaw) * local.z,
                local.y.toDouble(),
                sin(yaw) * local.x + cos(yaw) * local.z,
            )
        }

        fun local(anchor: Location, location: Location): Vector3f {
            val yaw = Math.toRadians(anchor.yaw.toDouble())
            val x = location.x - anchor.x; val z = location.z - anchor.z
            return Vector3f((cos(yaw) * x + sin(yaw) * z).toFloat(),
                (location.y - anchor.y).toFloat(), (-sin(yaw) * x + cos(yaw) * z).toFloat())
        }
    }
}

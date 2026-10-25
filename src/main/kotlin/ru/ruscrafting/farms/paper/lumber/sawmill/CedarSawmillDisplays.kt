package ru.ruscrafting.farms.paper.lumber.sawmill

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose

/** Keyed visuals over the existing shared owner; every workpiece keeps its handle through the cut. */
internal class CedarSawmillDisplays(
    val world: World,
    displays: PaperPacketDisplays,
) : AutoCloseable {
    private val scene = PacketDisplayScene<String>(displays)
    private val parts = CedarSawmillGeometry.parts.associateBy { it.id }
    private val blocks = parts.values.mapNotNull { part ->
        part.blockData?.let { part.id to Bukkit.createBlockData(it) }
    }.toMap()
    private val items = parts.values.mapNotNull { part ->
        part.itemModel?.let { model -> part.id to item(model) }
    }.toMap()

    val blockCount: Int get() = blocks.size
    val itemCount: Int get() = items.size

    fun render(frame: CedarSawmillAnimation.Frame, smooth: Boolean, audience: SceneAudience) {
        require(frame.poses.size == parts.size && frame.poses.map { it.partId }.toSet() == parts.keys) {
            "The sawmill frame must retain every authored part key"
        }
        val style = SceneDisplayStyle(
            Display.Billboard.FIXED,
            Display.Brightness(CedarSawmillGeometry.BODY_BLOCK_LIGHT, CedarSawmillGeometry.BODY_SKY_LIGHT),
            0, if (smooth) 2 else 0, (CedarSawmillGeometry.VIEW_RANGE_BLOCKS / 64.0).toFloat(),
            0f, 0f, 0f, 0f, false, null,
        )
        val origin = CedarSawmillGeometry.origin
        val anchor = Location(world, origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())
        val rendered = frame.poses.map { pose ->
            val part = parts.getValue(pose.partId)
            val translation = if (part.itemModel != null) Vector3f(pose.center) else {
                pose.rotation.transform(Vector3f(pose.size).mul(-.5f)).add(pose.center)
            }
            val transform = Transformation(translation, Quaternionf(pose.rotation), Vector3f(pose.size), Quaternionf())
            val at = ScenePose(anchor, transform)
            if (part.itemModel != null) {
                ScenePart.Item(part.id, at, style, items.getValue(part.id), ItemDisplay.ItemDisplayTransform.NONE, pose.visible)
            } else {
                ScenePart.Block(part.id, at, style, blocks.getValue(part.id), pose.visible)
            }
        }
        scene.apply(SceneFrame(rendered, audience, 0))
    }

    fun entityIds(): Map<String, Int?> = parts.keys.associateWith(scene::entityId)

    override fun close() = scene.close()

    companion object {
        internal fun item(model: String): ItemStack = ItemStack(Material.PAPER).also { item ->
            val meta = item.itemMeta
            meta.itemModel = requireNotNull(NamespacedKey.fromString(model)) { "Invalid sawmill item model: $model" }
            meta.setCustomModelData(CUSTOM_MODEL_DATA.getValue(model))
            item.itemMeta = meta
        }
        internal val CUSTOM_MODEL_DATA = mapOf(
            "arc_farms:cedar_sawmill_raw" to 271001,
            "arc_farms:cedar_sawmill_sawn" to 271002,
            "arc_farms:cedar_sawmill_slab" to 271003,
            "arc_farms:cedar_sawmill_blade" to 271004,
            "arc_farms:cedar_sawmill_pulley" to 271005,
        )
    }
}

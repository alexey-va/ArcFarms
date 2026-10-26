package ru.ruscrafting.farms.paper.lumber.scene

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.PaperSpatialControls
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose
import ru.arc.paper.scene.SpatialControl
import ru.arc.paper.scene.SpatialControlController
import ru.arc.paper.scene.SpatialControlFrame
import ru.arc.paper.scene.SpatialHitPlane
import ru.arc.paper.scene.SpatialInputProfile
import ru.arc.paper.scene.SpatialNativeTarget
import ru.arc.paper.scene.SpatialReach
import ru.arc.paper.scene.SpatialSceneKey
import ru.arc.paper.scene.SpatialSceneScope
import ru.ruscrafting.farms.domain.lumber.LumberAction
import ru.ruscrafting.farms.domain.lumber.LumberInput
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberPose
import ru.ruscrafting.farms.domain.lumber.LumberVisualView
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillController
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillDisplays
import ru.ruscrafting.farms.paper.lumber.sawmill.CedarSawmillGeometry
import java.util.UUID

/** Packet visuals with private right-click proxies; axe input remains owned by the worksite. */
internal class PacketLumberPresentation(
    plugin: Plugin,
    private val displays: PaperPacketDisplays,
    private val worldName: String,
    private val permission: String,
    private val warehouse: LumberPose,
    private val onUse: (Player) -> Unit,
) : SpatialControlController<Long, LumberInput>(), LumberPresentation {
    private var viewers: Set<UUID> = emptySet()
    private val group = displays.createAudienceGroup { it.hasPermission(permission) && it.world.name == worldName && it.uniqueId in viewers }
    private val audience = SceneAudience.Public(group)
    private val scene = PacketDisplayScene<String>(displays)
    private val controls = PaperSpatialControls(plugin, displays, this)
    private val logTether = LumberLogTether(plugin)
    private var mill: CedarSawmillController? = null
    private var worldId: UUID? = null
    private var view: LumberVisualView? = null
    private var deliveredFrom: LumberPose? = null
    private var deliveredAt = 0L
    private var frame: SpatialControlFrame<Long, LumberInput>? = null
    private var masked: Set<WorksitePosition> = emptySet()
    private val blocks = mutableMapOf<String, org.bukkit.block.data.BlockData>()
    private val items = CedarSawmillDisplays.CUSTOM_MODEL_DATA.keys.associateWith(CedarSawmillDisplays::item)

    override fun render(view: LumberVisualView) {
        this.view?.let { previous ->
            if (view.state.completedRounds > previous.state.completedRounds) {
                deliveredFrom = previous.state.cart
                deliveredAt = view.now
            }
        }
        this.view = view
        viewers = view.viewers
        val world = Bukkit.getWorld(worldName) ?: return
        if (worldId != world.uid) {
            scene.reset()
            mill?.close()
            mill = CedarSawmillController(world, displays)
            worldId = world.uid
        }
        masked = view.state.tree?.maskedBlocks.orEmpty().mapTo(hashSetOf()) { it.position }
        mill?.render(view, audience)
        val parts = LumberSceneGeometry.sample(view, warehouse) + if (view.state.completedRounds > 0)
            LumberSceneGeometry.storedBoards(warehouse, deliveredFrom, (view.now - deliveredAt) / 1000.0) else emptyList()
        scene.apply(SceneFrame(parts.map { rendered(world, it, view) }, audience, 0))
        logTether.render(view, view.viewers.mapNotNull(Bukkit::getPlayer).filter(::eligible))
        frame = SpatialControlFrame(view.state.roundId, audience, SpatialSceneScope.World,
            targets(view, parts).flatMap { part -> lumberControls(world, part) },
            // Existing worksite profile accepts owned targets in protected regions;
            // every activation still re-enters the beta-gated controller.
            SpatialInputProfile.ENTITY_BOTH, SpatialReach.NativeInteraction, true, emptyList())
        controls.apply(KEY, requireNotNull(frame))
    }

    override fun target(player: Player): LumberInput? {
        if (!eligible(player)) return null
        return controls.target(KEY, player)?.action
    }

    override fun current(player: Player, scene: SpatialSceneKey): SpatialControlFrame<Long, LumberInput>? =
        frame?.takeIf { scene == KEY && eligible(player) && it.context == view?.state?.roundId }

    // The owner re-targets the current frame and rechecks authority before accepting a transition.
    override fun activate(player: Player, scene: SpatialSceneKey, context: Long, action: LumberInput) {
        if (scene == KEY && context == view?.state?.roundId && eligible(player)) onUse(player)
    }

    override fun blocksRay(player: Player, context: Long, block: Block): Boolean =
        WorksitePosition(block.world.name, block.x, block.y, block.z) !in masked

    override fun release(player: Player) {
        viewers = viewers - player.uniqueId
        logTether.release(player)
    }

    override fun cleanup(reason: String) {
        viewers = emptySet()
        frame = null
        view = null
        deliveredFrom = null
        controls.clear(KEY)
        controls.close()
        logTether.clear()
        scene.close()
        mill?.close()
        mill = null
        group.close()
        blocks.clear()
        masked = emptySet()
    }

    private fun eligible(player: Player): Boolean = player.hasPermission(permission) && player.world.name == worldName &&
        player.uniqueId in viewers && player.isOnline && !player.isDead

    private fun rendered(world: World, part: LumberSceneGeometry.Part, view: LumberVisualView): ScenePart<String> {
        // A stable per-round anchor makes falling/hauling movement use transform interpolation.
        val root = view.state.tree?.root
        val anchor = Location(world, root?.x?.toDouble() ?: 188.5, root?.y?.toDouble() ?: 73.0, root?.z?.toDouble() ?: 182.2)
        val translation = Vector3f(part.center).sub(anchor.x.toFloat(), anchor.y.toFloat(), anchor.z.toFloat())
        if (part.itemModel == null) translation.add(part.rotation.transform(Vector3f(part.size).mul(-.5f)))
        val pose = ScenePose(anchor, Transformation(translation, Quaternionf(part.rotation), Vector3f(part.size), Quaternionf()))
        val glowing = part.action != null
        val style = STYLE.copy(glowing = glowing, glowColor = if (glowing) Color.fromRGB(0xffc857) else null)
        return if (part.itemModel != null) ScenePart.Item(part.id, pose, style, items.getValue(part.itemModel), ItemDisplay.ItemDisplayTransform.NONE, part.visible)
        else ScenePart.Block(part.id, pose, style, blocks.getOrPut(requireNotNull(part.block)) { Bukkit.createBlockData(part.block) }, part.visible)
    }

    private fun targets(view: LumberVisualView, parts: List<LumberSceneGeometry.Part>): List<LumberSceneGeometry.Part> = buildList {
        val state = view.state
        fun box(id: String, at: LumberPose, size: Vector3f, action: LumberAction) {
            add(LumberSceneGeometry.Part(id, LumberSceneGeometry.vector(at), size,
                Quaternionf().rotationY(Math.toRadians(-at.yaw.toDouble()).toFloat()), action = LumberInput(action)))
        }
        when (state.phase) {
            LumberPhase.CHOPPING -> state.tree?.root?.let { root ->
                box("trunk", LumberPose(root.world, root.x + .5, root.y + 1.0, root.z + .5), Vector3f(1f, 2f, 1f), LumberAction.CHOP_TREE)
            }
            LumberPhase.DELIMBING -> addAll(parts.filter { it.action?.action == LumberAction.CUT_BRANCH })
            LumberPhase.LOADING_CART -> state.tree?.let {
                add(LumberSceneGeometry.pickupTarget(LumberSceneGeometry.felledStock(it), LumberAction.LOAD_CART))
            }
            LumberPhase.HAULING -> {
                if (state.cartCarrier == null) state.cart?.let {
                    add(LumberSceneGeometry.pickupTarget(LumberSceneGeometry.draggedStock(it), LumberAction.TAKE_CART))
                }
                if (state.cartCarrier != null) box("receiving-zone", LumberSceneGeometry.receivingCenter().copy(y = LumberSceneGeometry.receivingCenter().y + .75),
                    Vector3f(6f, 1.5f, 6f), LumberAction.LOAD_SAWMILL)
            }
            LumberPhase.SAWING -> if (!state.sawRunning) {
                val controlIds = CedarSawmillGeometry.parts.filter { it.control }.mapTo(hashSetOf()) { it.id }
                CedarSawmillController.frame(view).poses.filter { it.partId in controlIds }.forEach { pose ->
                    add(LumberSceneGeometry.Part(pose.partId, Vector3f(pose.center).add(CedarSawmillGeometry.origin), pose.size, pose.rotation,
                        action = LumberInput(LumberAction.SAW_LEVER)))
                }
            }
            LumberPhase.COLLECTING_BOARDS -> {
                add(LumberSceneGeometry.pickupTarget(LumberSceneGeometry.boardCartPickup(state), LumberAction.COLLECT_BOARDS))
                if (state.boardCartLoaded) addAll(parts.filter { it.id == "board-cart-handle" })
            }
            LumberPhase.DELIVERING -> if (state.boardCartLoaded) box("warehouse", warehouse.copy(y = warehouse.y + .8), Vector3f(2.5f, 1.6f, 2.5f), LumberAction.DELIVER_BOARDS)
            else -> Unit
        }
    }

    private companion object {
        val KEY = SpatialSceneKey("cedar-lumber-workpiece")
        val STYLE = SceneDisplayStyle(Display.Billboard.FIXED, Display.Brightness(12, 12), 0, 2, 1.25f, 0f, 0f, 0f, 0f, false, null)
    }
}

/** Six small client targets cover the log without a single oversized square interaction. */
internal fun lumberControls(world: World, part: LumberSceneGeometry.Part): List<SpatialControl<LumberInput>> {
    val action = requireNotNull(part.action)
    val planes = lumberHitPlanes(world, part)
    if (action.action in setOf(LumberAction.CHOP_TREE, LumberAction.CUT_BRANCH, LumberAction.LOAD_SAWMILL))
        return listOf(SpatialControl(part.id, action, planes, null))
    val count = if (part.id == "pickup-log") 6 else 1
    return (0 until count).map { index ->
        val size = Vector3f(part.size.x, part.size.y, part.size.z / count)
        val center = Vector3f(0f, 0f, -part.size.z / 2 + size.z * (index + .5f)).rotate(part.rotation).add(part.center)
        val corners = (0..7).map { bits -> Vector3f(
            if (bits and 1 == 0) -size.x / 2 else size.x / 2,
            if (bits and 2 == 0) -size.y / 2 else size.y / 2,
            if (bits and 4 == 0) -size.z / 2 else size.z / 2).rotate(part.rotation).add(center) }
        val width = maxOf(corners.maxOf { it.x } - corners.minOf { it.x }, corners.maxOf { it.z } - corners.minOf { it.z })
        val bottom = corners.minOf { it.y }
        val target = SpatialNativeTarget(Location(world, center.x.toDouble(), bottom.toDouble(), center.z.toDouble()),
            width, corners.maxOf { it.y } - bottom)
        SpatialControl("${part.id}-$index", action, planes, target)
    }
}

/** Full cuboid faces used by both qualified input and its endpoint/side ray regression. */
internal fun lumberHitPlanes(world: World, part: LumberSceneGeometry.Part): List<SpatialHitPlane> {
    val basis = listOf(Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f), Vector3f(0f, 0f, 1f)).map { part.rotation.transform(it) }
    val sizes = listOf(part.size.x, part.size.y, part.size.z)
    return (0..2).flatMap { axis ->
        val right = if (axis == 0) 2 else 0
        val up = if (axis == 1) 2 else 1
        listOf(-1f, 1f).map { side ->
            val point = Vector3f(basis[axis]).mul(side * sizes[axis] / 2).add(part.center)
            SpatialHitPlane(Location(world, point.x.toDouble(), point.y.toDouble(), point.z.toDouble()),
                basis[right].vector(), basis[up].vector(), sizes[right].toDouble(), sizes[up].toDouble(), false)
        }
    }
}

private fun Vector3f.vector() = Vector(x.toDouble(), y.toDouble(), z.toDouble())

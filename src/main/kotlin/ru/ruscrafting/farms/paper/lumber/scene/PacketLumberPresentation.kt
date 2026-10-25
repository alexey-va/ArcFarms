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

/** Packet-only scene; qualified axe input is routed by the worksite before any event cancellation. */
internal class PacketLumberPresentation(
    plugin: Plugin,
    private val displays: PaperPacketDisplays,
    private val worldName: String,
    private val permission: String,
    private val warehouse: LumberPose,
) : SpatialControlController<Long, LumberInput>(), LumberPresentation {
    private var viewers: Set<UUID> = emptySet()
    private val group = displays.createAudienceGroup { it.hasPermission(permission) && it.world.name == worldName && it.uniqueId in viewers }
    private val audience = SceneAudience.Public(group)
    private val scene = PacketDisplayScene<String>(displays)
    private val controls = PaperSpatialControls(plugin, displays, this)
    private val carriedBoards = LumberBoardCarry(displays, group)
    private var mill: CedarSawmillController? = null
    private var worldId: UUID? = null
    private var view: LumberVisualView? = null
    private var frame: SpatialControlFrame<Long, LumberInput>? = null
    private var masked: Set<WorksitePosition> = emptySet()
    private val blocks = mutableMapOf<String, org.bukkit.block.data.BlockData>()
    private val items = CedarSawmillDisplays.CUSTOM_MODEL_DATA.keys.associateWith(CedarSawmillDisplays::item)

    override fun render(view: LumberVisualView) {
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
        val parts = LumberSceneGeometry.sample(view)
        scene.apply(SceneFrame(parts.map { rendered(world, it, view) }, audience, 0))
        carriedBoards.render(view, world)
        frame = SpatialControlFrame(view.state.roundId, audience, SpatialSceneScope.World,
            targets(view, parts).map { part -> SpatialControl(part.id, requireNotNull(part.action), planes(world, part), null, true, true) },
            SpatialInputProfile.EXTERNAL_RAY, SpatialReach.NativeInteraction, true, emptyList())
        controls.apply(KEY, requireNotNull(frame))
    }

    override fun target(player: Player): LumberInput? {
        if (!eligible(player)) return null
        return controls.target(KEY, player)?.action
    }

    override fun current(player: Player, scene: SpatialSceneKey): SpatialControlFrame<Long, LumberInput>? =
        frame?.takeIf { scene == KEY && eligible(player) && it.context == view?.state?.roundId }

    // Only target() is used: accepted actions belong to LumberController, never to a visual callback.
    override fun activate(player: Player, scene: SpatialSceneKey, context: Long, action: LumberInput) = Unit

    override fun blocksRay(player: Player, context: Long, block: Block): Boolean =
        WorksitePosition(block.world.name, block.x, block.y, block.z) !in masked

    override fun release(player: Player) {
        viewers = viewers - player.uniqueId
        if (view?.state?.boardsCarrier == player.uniqueId) carriedBoards.clear()
    }

    override fun cleanup(reason: String) {
        viewers = emptySet()
        frame = null
        view = null
        controls.clear(KEY)
        controls.close()
        carriedBoards.clear()
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
        val style = STYLE.copy(glowing = part.action?.action == LumberAction.CUT_BRANCH,
            glowColor = if (part.action?.action == LumberAction.CUT_BRANCH) Color.fromRGB(0xffc857) else null)
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
            LumberPhase.LOADING_CART -> state.tree?.let { box("load-log", LumberSceneGeometry.felledStock(it), Vector3f(1.1f, 1.1f, LumberSceneGeometry.STOCK_LENGTH.toFloat()), LumberAction.LOAD_CART) }
            LumberPhase.HAULING -> {
                addAll(parts.filter { it.id == "cart-handle" }.map { part ->
                    val dock = LumberSceneGeometry.sawCartDock()
                    val cart = state.cart
                    if (cart != null && kotlin.math.hypot(cart.x - dock.x, cart.z - dock.z) <= .75)
                        part.copy(action = LumberInput(LumberAction.LOAD_SAWMILL)) else part
                })
                box("mill-intake", LumberSceneGeometry.sawIntake().copy(y = LumberSceneGeometry.sawIntake().y - .8), Vector3f(2.0f, .45f, 2.5f), LumberAction.LOAD_SAWMILL)
            }
            LumberPhase.SAWING -> if (!state.sawRunning) {
                val controlIds = CedarSawmillGeometry.parts.filter { it.control }.mapTo(hashSetOf()) { it.id }
                CedarSawmillController.frame(view).poses.filter { it.partId in controlIds }.forEach { pose ->
                    add(LumberSceneGeometry.Part(pose.partId, Vector3f(pose.center).add(CedarSawmillGeometry.origin), pose.size, pose.rotation,
                        action = LumberInput(LumberAction.SAW_LEVER)))
                }
            }
            LumberPhase.COLLECTING_BOARDS -> addAll(LumberSceneGeometry.boards(LumberSceneGeometry.outputPickup()).map { it.copy(action = LumberInput(LumberAction.COLLECT_BOARDS)) })
            LumberPhase.DELIVERING -> box("warehouse", warehouse.copy(y = warehouse.y + .8), Vector3f(2.5f, 1.6f, 2.5f), LumberAction.DELIVER_BOARDS)
            else -> Unit
        }
    }

    /** Full visible cuboid faces, with no native entity or unrelated-world cancellation. */
    private fun planes(world: World, part: LumberSceneGeometry.Part): List<SpatialHitPlane> {
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
    private companion object {
        val KEY = SpatialSceneKey("cedar-lumber-workpiece")
        val STYLE = SceneDisplayStyle(Display.Billboard.FIXED, Display.Brightness(12, 12), 0, 2, 1.25f, 0f, 0f, 0f, 0f, false, null)
    }
}

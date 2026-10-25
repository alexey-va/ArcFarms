package ru.ruscrafting.farms.paper.lumber.sawmill

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.PaperSpatialControls
import ru.arc.paper.scene.SpatialControl
import ru.arc.paper.scene.SpatialControlController
import ru.arc.paper.scene.SpatialControlFrame
import ru.arc.paper.scene.SpatialHitPlane
import ru.arc.paper.scene.SpatialInputProfile
import ru.arc.paper.scene.SpatialNativeTarget
import ru.arc.paper.scene.SpatialReach
import ru.arc.paper.scene.SpatialSceneKey
import ru.arc.paper.scene.SpatialSceneScope

/**
 * One authored public machine, separate from shifts because it changes no player or world state.
 * The module's supervised fast-visual hook advances the clock; Core owns input and viewer recovery.
 */
internal class CedarSawmillController(
    private val plugin: Plugin,
    private val serverId: String,
    private val displays: PaperPacketDisplays,
) : SpatialControlController<Unit, Unit>() {
    private val playback = CedarSawmillPlayback()
    private var active = false
    private var visualTick = 0L
    private var visuals: CedarSawmillDisplays? = null
    private var controls: PaperSpatialControls<Unit, Unit>? = null
    private var input: SpatialControlFrame<Unit, Unit>? = null
    private var renderedTicks = -1
    private var renderedPhase: CedarSawmillPlayback.Phase? = null

    fun activateLoadedState() {
        cleanup("activate")
        active = serverId == "spawn"
        if (active) render()
    }

    fun updateVisuals() {
        if (!active) return
        val prior = playback.phase
        playback.tick()
        if (++visualTick % 2L == 0L || prior != playback.phase) render()
        if (prior != playback.phase) report("finished")
    }

    fun reconcileChunk(chunk: Chunk) {
        if (active && chunk.world.name == WORLD_NAME) render()
    }

    fun beforeChunkUnload(chunk: Chunk) {
        val world = visuals?.world ?: return
        val center = CedarSawmillGeometry.interactionCenter
        val origin = CedarSawmillGeometry.origin
        if (chunk.world.uid == world.uid && chunk.x == ((origin.x + center.x).toInt() shr 4) &&
            chunk.z == ((origin.z + center.z).toInt() shr 4)
        ) controls?.clear(SCENE_KEY)
    }

    override fun current(player: Player, scene: SpatialSceneKey): SpatialControlFrame<Unit, Unit>? =
        input?.takeIf { active && scene == SCENE_KEY && player.world.uid == visuals?.world?.uid }

    override fun activate(player: Player, scene: SpatialSceneKey, context: Unit, action: Unit) {
        if (current(player, scene) == null) return
        playback.toggle()
        render()
        report("lever")
    }

    override fun inputFailed(player: Player, scene: SpatialSceneKey, context: Unit, failure: Exception) {
        cleanup("input_failed")
    }

    fun snapshot(): Map<String, Any> = linkedMapOf(
        "active" to active,
        "world" to WORLD_NAME,
        "phase" to playback.phase.name,
        "elapsed_ticks" to playback.elapsedTicks,
        "cycles_started" to playback.cyclesStarted,
        "block_parts" to (visuals?.blockCount ?: 0),
        "item_parts" to (visuals?.itemCount ?: 0),
        "control_parts" to input?.controls.orEmpty().size,
        "desired_native_targets" to input?.controls.orEmpty().count { it.nativeTarget != null },
    )

    fun cleanup(reason: String) {
        active = false
        input = null
        retireVisuals()
        playback.reset()
        visualTick = 0L
    }

    private fun render() {
        if (!active) return
        val world = Bukkit.getWorld(WORLD_NAME)
        if (world == null || (visuals != null && visuals?.world?.uid != world.uid)) retireVisuals()
        if (world == null) return
        val created = visuals == null
        val view = visuals ?: CedarSawmillDisplays(world, displays).also {
            visuals = it
            controls = PaperSpatialControls(plugin, displays, this)
        }
        if (renderedTicks != playback.elapsedTicks || renderedPhase != playback.phase) {
            val frame = CedarSawmillAnimation.sample(playback.elapsedSeconds)
            view.render(frame, playback.phase == CedarSawmillPlayback.Phase.RUNNING &&
                renderedTicks >= 0 && playback.elapsedTicks >= renderedTicks)
            input = controlFrame(world, frame)
            controls?.apply(SCENE_KEY, requireNotNull(input))
            renderedTicks = playback.elapsedTicks
            renderedPhase = playback.phase
        } else {
            val owner = controls
            if (owner != null && !owner.contains(SCENE_KEY)) input?.let { owner.apply(SCENE_KEY, it) }
        }
        if (created) report("ready")
    }

    private fun controlFrame(world: World, frame: CedarSawmillAnimation.Frame): SpatialControlFrame<Unit, Unit> {
        val controlIds = CedarSawmillGeometry.parts.filter { it.control }.mapTo(hashSetOf()) { it.id }
        val center = Vector3f(CedarSawmillGeometry.interactionCenter).add(CedarSawmillGeometry.origin)
        val size = CedarSawmillGeometry.interactionSize
        val native = SpatialNativeTarget(
            Location(world, center.x.toDouble(), (center.y - size.y / 2).toDouble(), center.z.toDouble()),
            maxOf(size.x, size.z), size.y,
        )
        val targets = frame.poses.filter { it.partId in controlIds }.mapIndexed { index, pose ->
            SpatialControl(pose.partId, Unit, planes(world, pose), if (index == 0) native else null, pose.visible, true)
        }
        check(targets.isNotEmpty()) { "The sawmill lever must have authored control geometry" }
        return SpatialControlFrame(
            Unit, CedarSawmillDisplays.AUDIENCE, SpatialSceneScope.World, targets,
            SpatialInputProfile.ENTITY_RIGHT, SpatialReach.NativeInteraction, true, emptyList(),
        )
    }

    /** Six exact faces of each currently rendered lever cuboid; the larger native box only carries input. */
    private fun planes(world: World, pose: CedarSawmillAnimation.Pose): List<SpatialHitPlane> {
        val basis = listOf(Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f), Vector3f(0f, 0f, 1f))
            .map { pose.rotation.transform(it) }
        val center = Vector3f(pose.center).add(CedarSawmillGeometry.origin)
        val sizes = listOf(pose.size.x, pose.size.y, pose.size.z)
        return (0..2).flatMap { axis ->
            val right = if (axis == 0) 2 else 0
            val up = if (axis == 1) 2 else 1
            listOf(-1f, 1f).map { side ->
                val at = Vector3f(basis[axis]).mul(side * sizes[axis] / 2).add(center)
                SpatialHitPlane(
                    Location(world, at.x.toDouble(), at.y.toDouble(), at.z.toDouble()),
                    basis[right].vector(), basis[up].vector(), sizes[right].toDouble(), sizes[up].toDouble(), false,
                )
            }
        }
    }

    private fun retireVisuals() {
        input = null
        var failure: Throwable? = null
        listOf<() -> Unit>({ controls?.clear(SCENE_KEY) }, { controls?.close() }, { visuals?.close() }).forEach { close ->
            runCatching(close).exceptionOrNull()?.let {
                if (failure == null) failure = it else requireNotNull(failure).addSuppressed(it)
            }
        }
        controls = null
        visuals = null
        renderedTicks = -1
        renderedPhase = null
        failure?.let { throw it }
    }

    private fun report(operation: String) {
        plugin.logger.info("Cedar sawmill operation=$operation " + snapshot().entries.joinToString(" ") { "${it.key}=${it.value}" })
    }

    private fun Vector3f.vector(): Vector = Vector(x.toDouble(), y.toDouble(), z.toDouble())

    companion object {
        const val WORLD_NAME = "rc_atelier_cedar_sawmill"
        val SCENE_KEY = SpatialSceneKey("cedar-sawmill")
    }
}

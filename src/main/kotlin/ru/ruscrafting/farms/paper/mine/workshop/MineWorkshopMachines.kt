package ru.ruscrafting.farms.paper.mine.workshop

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayBlueprints
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayLighting
import ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionMarkerGeometry
import kotlin.math.PI
import kotlin.math.roundToInt

/** Packet-only permanent workshop assemblies and their actionable controls. */
internal class MineWorkshopMachines(private val plugin: Plugin) {
    internal enum class Animation {
        STATIC,
        ROLLERS,
        FEED,
        BELT,
        CARGO,
        HEAT_CORE,
        NEEDLE,
        MOLTEN,
        COOLING,
        COOLING_ROLLERS,
    }

    internal data class Visual(
        val part: MineDisplayBlueprints.Part,
        val scale: Float,
        val offset: Vector3f,
        val control: String? = null,
        val yaw: Float = 0f,
        val animation: Animation = Animation.STATIC,
        val path: List<Vector3f> = emptyList(),
    )

    internal class Machine internal constructor(
        private val renderer: PaperPacketDisplays,
        private val role: String,
        val body: PacketBlockDisplay,
        private val displays: List<PacketBlockDisplay>,
        private val visuals: List<Visual>,
        val controls: Map<String, Location>,
        /** Optional final pickup point; output uses the connected rack end. */
        val interactionCenter: Location? = null,
    ) {
        private val controlStarts = mutableMapOf<String, Long>()
        private val manualMotionVisibility = mutableMapOf<String, Boolean>()
        private var captionLabel: PacketTextDisplay? = null
        private var captionControl: String? = null
        private val hiddenUntil = mutableMapOf<PacketBlockDisplay, Long>()

        fun remove() {
            captionLabel?.remove()
            captionLabel = null
            captionControl = null
            displays.forEach(PacketBlockDisplay::remove)
        }

        /** Return all transient pieces to their hidden, pre-batch positions. */
        fun reset() {
            controlStarts.clear()
            manualMotionVisibility.clear()
            hiddenUntil.clear()
            caption(null)
            renderProcess(MineWorkshopVisualState(), 0L)
            displays.zip(visuals).forEach { (display, visual) ->
                if (visual.part.motion in LEGACY_TRANSIENT_MOTIONS) display.isVisibleByDefault = false
            }
            highlight(false)
        }

        /** Compatibility render used by older scene callers during creation. */
        fun render(phase: Float, now: Long = 0L) {
            renderProcess(MineWorkshopVisualState(), now, legacyPhase = phase)
        }

        /** Render one normalized process frame. */
        fun renderProcess(state: MineWorkshopVisualState, now: Long) {
            renderProcess(state, now, legacyPhase = 0f)
        }

        fun pulse(control: String, now: Long) {
            if (control in controls) controlStarts[control] = now
        }

        fun setMotionVisible(motion: String, visible: Boolean) {
            manualMotionVisibility[motion] = visible
            displays.zip(visuals).forEach { (display, visual) ->
                if (visual.part.motion == motion) display.isVisibleByDefault = visible
            }
        }

        /** Only the next actionable part glows; a ready tap uses green. */
        fun highlight(active: Boolean, control: String? = null) {
            val precise = controls.isNotEmpty()
            displays.zip(visuals).forEach { (display, visual) ->
                val part = visual.part
                val glowing = if (precise) {
                    active && visual.control == control &&
                        (part.motion in ACTIONABLE_MOTIONS || control == FEED)
                } else if (role == OUTPUT) {
                    active && visual.animation == Animation.COOLING
                } else {
                    active
                }
                display.isGlowing = glowing || (visual.animation == Animation.HEAT_CORE && display.isGlowing)
                display.glowColorOverride = when {
                    glowing && control == TAP -> READY_GLOW
                    glowing -> ACTIVE_GLOW
                    else -> IDLE_GLOW
                }
            }
        }

        /** Replace a small packet caption above a machine or its control. */
        fun caption(text: Component?, control: String? = null) {
            if (text == null) {
                captionLabel?.remove()
                captionLabel = null
                captionControl = null
                return
            }
            if (captionControl != control) {
                captionLabel?.remove()
                captionLabel = null
            }
            val anchor = control?.let(controls::get)
                ?: interactionCenter?.clone()?.add(0.0, .85, 0.0)
                ?: body.location.clone().add(0.0, 1.85, .65)
            val label = captionLabel ?: renderer.spawnText(
                if (control != null) MineWorkshopGeometry.captionLocation(anchor) else anchor,
                text,
            ).also { created ->
                created.billboard = Display.Billboard.VERTICAL
                created.brightness = Display.Brightness(15, 15)
                created.viewRange = 3f
                created.backgroundColor = Color.fromARGB(100, 12, 18, 24)
                created.isShadowed = true
                created.isSeeThrough = true
                created.lineWidth = 180
                created.transformation = Transformation(
                    Vector3f(), Quaternionf(), Vector3f(.65f), Quaternionf(),
                )
                captionLabel = created
                captionControl = control
            }
            label.text(text)
        }

        private fun renderProcess(state: MineWorkshopVisualState, now: Long, legacyPhase: Float) {
            displays.zip(visuals).forEach { (display, visual) ->
                val part = visual.part
                val wantsVisible = isVisible(visual, state, now)
                if (!wantsVisible) {
                    hiddenUntil[display] = maxOf(
                        hiddenUntil[display] ?: 0L,
                        MineWorkshopAnimation.hiddenUntil(now),
                    )
                }
                val visible = wantsVisible && MineWorkshopAnimation.canReappear(now, hiddenUntil[display])
                display.isVisibleByDefault = visible
                display.interpolationDuration = if (visible) 2 else 0
                val poseState = if (!visible && visual.animation in RESETTABLE_ANIMATIONS) state.copy(
                    transfer = if (state.transferActive) 0f else state.transfer,
                    pouring = if (state.pouringActive) 0f else state.pouring,
                ) else state
                val phase = phaseFor(visual, poseState, now, legacyPhase)
                val pose = MineWorkshopVisualPose.pose(visual, phase, poseState)
                val continuous = MineExpeditionMarkerGeometry.continuousRotation(
                    pose.rotation,
                    display.transformation.leftRotation,
                )
                val corner = continuous.transform(Vector3f(pose.size).mul(-0.5f)).add(pose.center)
                val desired = Transformation(corner, continuous, pose.size, Quaternionf())
                if (display.transformation != desired) {
                    display.interpolationDelay = 0
                    display.transformation = desired
                }
                if (visual.animation == Animation.HEAT_CORE) {
                    display.isGlowing = visible && state.heatVisible
                    display.glowColorOverride = heatGlow(state.temperature, state.heatReady)
                }
            }
        }

        private fun isVisible(visual: Visual, state: MineWorkshopVisualState, now: Long): Boolean {
            val motion = visual.part.motion
            manualMotionVisibility[motion]?.let { return it }
            if (visual.part.idleHidden) {
                return when (visual.animation) {
                    Animation.FEED -> state.crushing && MineWorkshopAnimation.feedVisible(
                        MineWorkshopAnimation.feedProgress(
                            MineWorkshopAnimation.cycleProgress(now, 1_200L),
                            visual.part.angle,
                        ),
                    )
                    Animation.CARGO, Animation.BELT -> state.transferActive
                    Animation.MOLTEN -> state.pouringActive && MineWorkshopAnimation.hotVisible(state.pouring)
                    Animation.COOLING -> state.pouringActive && MineWorkshopAnimation.coolingVisible(state.pouring)
                    else -> false
                }
            }
            return when (visual.animation) {
                Animation.ROLLERS, Animation.COOLING_ROLLERS -> true
                Animation.FEED -> state.crushing
                Animation.BELT -> true
                Animation.CARGO -> state.transferActive
                Animation.HEAT_CORE -> state.heatVisible
                Animation.NEEDLE -> true
                Animation.MOLTEN -> state.pouringActive && MineWorkshopAnimation.hotVisible(state.pouring)
                Animation.COOLING -> state.pouringActive && MineWorkshopAnimation.coolingVisible(state.pouring)
                Animation.STATIC -> true
            }
        }

        private fun phaseFor(visual: Visual, state: MineWorkshopVisualState, now: Long, legacy: Float): Float {
            val part = visual.part
            val control = visual.control
            if (part.motion == "lever" && control == AIR) {
                val started = controlStarts[control]
                if (started == null) return if (state.airOpen) PI.toFloat() else 0f
                val progress = ((now - started).coerceAtLeast(0L).toFloat() / CONTROL_ANIMATION_MILLIS).coerceIn(0f, 1f)
                return if (state.airOpen) PI.toFloat() * progress else PI.toFloat() * (1f - progress)
            }
            startedPhase(control, now)?.let { return it }
            return when (visual.animation) {
                Animation.ROLLERS -> if (state.crushing) cycle(now, 900L) else legacy
                Animation.FEED -> if (state.crushing) cycle(now, 1_200L) else legacy
                Animation.BELT, Animation.CARGO -> if (state.transferActive) state.transfer * TAU else legacy
                Animation.COOLING_ROLLERS -> if (state.pouringActive) MineWorkshopAnimation.coolingProgress(state.pouring) * TAU * 4f else 0f
                Animation.NEEDLE -> state.temperature * 2.45f
                else -> legacy
            }
        }

        private fun startedPhase(control: String?, now: Long): Float? = control?.let { key ->
            controlStarts[key]?.let { started ->
                val progress = ((now - started).coerceAtLeast(0L).toFloat() / CONTROL_ANIMATION_MILLIS).coerceIn(0f, 1f)
                progress * TAU
            }
        }

        private fun cycle(now: Long, period: Long): Float =
            ((now % period).toFloat() / period.toFloat()) * TAU

        companion object {
            private val ACTIVE_GLOW = Color.fromRGB(255, 183, 65)
            private val READY_GLOW = Color.fromRGB(85, 217, 139)
            private val IDLE_GLOW = Color.fromRGB(130, 130, 130)
            private val ACTIONABLE_MOTIONS = setOf("lever", "signal")
            private val LEGACY_TRANSIENT_MOTIONS = setOf("feed", "cargo", "processed")
            private val RESETTABLE_ANIMATIONS = setOf(Animation.BELT, Animation.CARGO, Animation.MOLTEN, Animation.COOLING)
            private const val CONTROL_ANIMATION_MILLIS = 480f
            private val TAU = (PI * 2).toFloat()

            private fun heatGlow(temperature: Float, ready: Boolean): Color = when {
                ready -> READY_GLOW
                temperature >= .6f -> Color.fromRGB(255, 138, 56)
                else -> Color.fromRGB(255, 91, 46)
            }
        }
    }

    private var renderer: PaperPacketDisplays? = null

    private fun renderer() = renderer ?: PaperPacketDisplays(requireNotNull(plugin) {
        "A plugin is required to spawn live workshop displays"
    }).also { renderer = it }

    fun close() {
        renderer?.close()
        renderer = null
    }

    fun create(role: String, at: Location): Machine {
        val packetRenderer = renderer()
        val visuals = buildVisuals(role)
        val displays = ArrayList<PacketBlockDisplay>(visuals.size)
        try {
            visuals.forEach { visual ->
                val display = packetRenderer.spawnBlock(at, visual.part.material.createBlockData()).apply {
                    brightness = MineDisplayLighting.brightness(visual.part.material)
                    viewRange = 3f
                    interpolationDuration = 2
                    teleportDuration = 2
                    isVisibleByDefault = !visual.part.idleHidden
                    glowColorOverride = Color.fromRGB(255, 183, 65)
                }
                displays += display
            }
        } catch (failure: Throwable) {
            displays.forEach(PacketBlockDisplay::remove)
            throw failure
        }
        val body = displays.firstOrNull() ?: error("Workshop model $role has no visual parts")
        val controls = controlLocations(role, at)
        val interactionCenter = if (role == OUTPUT) MineWorkshopGeometry.PICKUP_OFFSET.let {
            at.clone().add(it.x.toDouble(), it.y.toDouble(), it.z.toDouble())
        } else null
        return Machine(packetRenderer, role, body, displays, visuals, controls, interactionCenter)
            .also { it.render(0f, 0L) }
    }

    companion object {
        private fun buildVisuals(role: String): List<Visual> = buildList {
        fun model(
            kind: String,
            scale: Float,
            offset: Vector3f = Vector3f(),
            control: String? = null,
            yaw: Float = 0f,
            animation: Animation? = null,
            path: List<Vector3f> = emptyList(),
        ) {
            MineDisplayBlueprints.model(kind).forEachIndexed { partIndex, sourcePart ->
                // Workshop owns its short, waist-high outlet instead of the factory floor trough.
                if (kind == "furnace" && sourcePart.center.z > 4f) return@forEachIndexed
                // These generic yellow pads look like extra buttons beside the real drive handle.
                if (kind == "factory_crusher" && sourcePart.material == Material.YELLOW_TERRACOTTA) return@forEachIndexed
                // Sink the shared foundation slightly into the floor so the
                // hopper and conveyor feet do not share its underside plane.
                val part = if (kind in setOf("factory_crusher", "furnace") && partIndex == 0)
                    sourcePart.copy(center = Vector3f(sourcePart.center).add(0f, -.05f, 0f)) else sourcePart
                val mapped = animation ?: if (kind == "furnace" && part.material == Material.ORANGE_STAINED_GLASS) Animation.HEAT_CORE else when (part.motion) {
                    "rotate", "counter_rotate" -> Animation.ROLLERS
                    "feed" -> Animation.FEED
                    "belt" -> Animation.BELT
                    "cargo" -> Animation.CARGO
                    else -> Animation.STATIC
                }
                val partPath = if (mapped == Animation.CARGO && path.isNotEmpty()) {
                    // The five charge chunks are one train, not five coincident
                    // blocks. Keep their authored z offsets and stagger their
                    // x positions by .65 world blocks while preserving the
                    // same one-way route.
                    val index = (part.angle * 5f / (2f * PI.toFloat())).roundToInt().coerceIn(0, 4)
                    val stagger = ((2 - index) * .65f) / scale
                    path.map { point ->
                        Vector3f(point).add(stagger, 0f, part.center.z)
                    }
                } else emptyList()
                add(Visual(part, scale, Vector3f(offset), control, yaw, mapped, partPath))
            }
        }

        fun part(
            material: Material,
            center: Vector3f,
            size: Vector3f,
            motion: String = "fixed",
            moving: Boolean = false,
            pivot: Vector3f = Vector3f(),
            hidden: Boolean = false,
            control: String? = null,
            yaw: Float = 0f,
            animation: Animation = Animation.STATIC,
            path: List<Vector3f> = emptyList(),
        ) {
            add(Visual(
                MineDisplayBlueprints.Part(material, center, size, moving = moving, pivot = pivot, motion = motion, idleHidden = hidden),
                1f, Vector3f(), control, yaw, animation, path,
            ))
        }

        when (role) {
            "crusher" -> {
                model("factory_crusher", .6f)
                model("inlet_hopper", .55f, Vector3f(0f, 0f, 2.15f), FEED)
                addAll(MineWorkshopAttachments.crusherPanel())
                // The collector overlaps the conveyor head so the material route is visibly joined.
                part(Material.POLISHED_BLACKSTONE, Vector3f(0f, .72f, 0f), Vector3f(3.4f, .22f, 2.2f))
                part(Material.CUT_COPPER, Vector3f(2.8f, .93f, -1.04f), Vector3f(3.0f, .14f, .16f))
                part(Material.CUT_COPPER, Vector3f(2.8f, .93f, 1.04f), Vector3f(3.0f, .14f, .16f))
                model(
                    "factory_conveyor", .45f, Vector3f(2.8f, 0f, 0f),
                    path = MineWorkshopGeometry.CONVEYOR_CARGO,
                )
            }
            "furnace" -> {
                // Rotate the furnace so its rear charging mouth meets the x-axis conveyor.
                model("furnace", .5f, yaw = MineWorkshopGeometry.FURNACE_YAW_RADIANS)
                addAll(MineWorkshopAttachments.furnacePanel())
            }
            "ore" -> model("inlet_hopper", .55f)
            "output" -> addAll(MineWorkshopAttachments.casting())
            "shipping" -> addAll(MineWorkshopAttachments.rack())
            else -> error("Unknown ore workshop machine role: $role")
        }
    }

        private fun controlLocations(role: String, at: Location): Map<String, Location> = when (role) {
        "crusher" -> listOf(FEED, DRIVE).associateWith { MineWorkshopGeometry.controlLocation(at, it) }
        "furnace" -> listOf(AIR, TAP).associateWith { MineWorkshopGeometry.controlLocation(at, it) }
        else -> emptyMap()
    }

        /** Pure visual source used by the offline Atelier exporter and validators. */
        @JvmStatic
        @JvmName("previewVisuals")
        internal fun previewVisuals(role: String): List<Visual> = buildVisuals(role)

        const val FEED = "crusher_feed"
        const val DRIVE = "crusher_drive"
        const val AIR = "furnace_air"
        const val TAP = "furnace_tap"
        val CRUSH_CONTROLS = listOf(FEED, DRIVE)
        private const val OUTPUT = "output"
    }
}

package ru.ruscrafting.farms.paper.mine.workshop

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayBlueprints
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayLighting
import ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionMarkerGeometry

/** Packet-only permanent workshop assemblies and their small actionable controls. */
internal class MineWorkshopMachines(private val plugin: Plugin) {
    internal data class Visual(
        val part: MineDisplayBlueprints.Part,
        val scale: Float,
        val offset: Vector3f,
        val control: String? = null,
    )

    internal class Machine internal constructor(
        val body: PacketBlockDisplay,
        private val displays: List<PacketBlockDisplay>,
        private val visuals: List<Visual>,
        val controls: Map<String, Location>,
    ) {
        private val controlStarts = mutableMapOf<String, Long>()

        fun remove() = displays.forEach(PacketBlockDisplay::remove)

        /** Keep all transient poses at a known state after a reload or completed batch. */
        fun reset() {
            controlStarts.clear()
            render(0f, 0L)
            setMotionVisible("feed", false)
            setMotionVisible("cargo", false)
            setMotionVisible("processed", false)
            highlight(false)
        }

        /** Smooth blueprint animation; moving rollers and belts never teleport between poses. */
        fun render(phase: Float, now: Long = 0L) {
            displays.zip(visuals).forEach { (display, visual) ->
                val part = visual.part
                val localPhase = visual.control?.let { control ->
                    controlStarts[control]?.let { started ->
                        (((now - started).coerceAtLeast(0L).toFloat() / CONTROL_ANIMATION_MILLIS) * (2f * Math.PI.toFloat()))
                            .coerceAtMost(2f * Math.PI.toFloat())
                    } ?: 0f
                } ?: phase
                val rotation = MineDisplayBlueprints.rotation(part, localPhase)
                val size = Vector3f(part.size).mul(visual.scale)
                val center = MineDisplayBlueprints.center(part, localPhase).mul(visual.scale).add(visual.offset)
                val continuous = MineExpeditionMarkerGeometry.continuousRotation(
                    rotation,
                    display.transformation.leftRotation,
                )
                val corner = continuous.transform(Vector3f(size).mul(-0.5f)).add(center)
                val desired = Transformation(corner, continuous, size, Quaternionf())
                if (display.transformation != desired) {
                    display.interpolationDelay = 0
                    display.transformation = desired
                }
            }
        }

        fun pulse(control: String, now: Long) {
            if (control in controls) controlStarts[control] = now
        }

        fun setMotionVisible(motion: String, visible: Boolean) {
            displays.zip(visuals).forEach { (display, visual) ->
                if (visual.part.motion == motion) display.isVisibleByDefault = visible
            }
        }

        /** Only the next lever/signal glows for the crusher; other stations retain target glow. */
        fun highlight(active: Boolean, control: String? = null) {
            val precise = controls.isNotEmpty()
            displays.zip(visuals).forEach { (display, visual) ->
                val part = visual.part
                val glowing = if (precise) {
                    active && visual.control == control && part.motion in ACTIONABLE_MOTIONS
                } else {
                    active
                }
                display.isGlowing = glowing
                display.glowColorOverride = if (glowing) ACTIVE_GLOW else IDLE_GLOW
            }
        }

        companion object {
            private val ACTIVE_GLOW = Color.fromRGB(255, 183, 65)
            private val IDLE_GLOW = Color.fromRGB(130, 130, 130)
            private val ACTIONABLE_MOTIONS = setOf("lever", "signal")
            private const val CONTROL_ANIMATION_MILLIS = 480f
        }
    }

    private var renderer: PaperPacketDisplays? = null

    private fun renderer() = renderer ?: PaperPacketDisplays(plugin).also { renderer = it }

    fun close() {
        renderer?.close()
        renderer = null
    }

    fun create(role: String, at: Location): Machine {
        val visuals = buildVisuals(role)
        val displays = ArrayList<PacketBlockDisplay>(visuals.size)
        try {
            visuals.forEach { visual ->
                val display = renderer().spawnBlock(at, visual.part.material.createBlockData()).apply {
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
        return Machine(body, displays, visuals, controls).also { it.render(0f, 0L) }
    }

    private fun buildVisuals(role: String): List<Visual> = buildList {
        fun model(kind: String, scale: Float, offset: Vector3f = Vector3f(), control: String? = null) {
            MineDisplayBlueprints.model(kind).forEach { add(Visual(it, scale, Vector3f(offset), control)) }
        }
        when (role) {
            // The factory crusher is 8x6x7 blocks; .6 keeps it inside the authored niche.
            "crusher" -> {
                model("factory_crusher", .6f)
                CRUSH_CONTROLS.forEachIndexed { index, control ->
                    model(
                        "mounted_console",
                        .32f,
                        Vector3f(CRUSH_CONTROL_X[index], CRUSH_CONTROL_Y, CRUSH_CONTROL_Z),
                        control,
                    )
                }
            }
            "furnace" -> model("furnace", .5f)
            "ore" -> model("inlet_hopper", .45f)
            "output" -> model("charge_hopper", .42f)
            "shipping" -> model("factory_conveyor", .30f)
            else -> error("Unknown ore workshop machine role: $role")
        }
    }

    private fun controlLocations(role: String, at: Location): Map<String, Location> =
        if (role != "crusher") emptyMap()
        else CRUSH_CONTROLS.mapIndexed { index, control ->
            control to at.clone().add(
                CRUSH_CONTROL_X[index].toDouble(),
                (CRUSH_CONTROL_Y + .48f).toDouble(),
                (CRUSH_CONTROL_Z + .08f).toDouble(),
            )
        }.toMap()

    companion object {
        val CRUSH_CONTROLS = listOf("crusher_feed", "crusher_drive", "crusher_release")
        private val CRUSH_CONTROL_X = floatArrayOf(-1.55f, 0f, 1.55f)
        private const val CRUSH_CONTROL_Y = .6f
        private const val CRUSH_CONTROL_Z = 1.9f
    }
}

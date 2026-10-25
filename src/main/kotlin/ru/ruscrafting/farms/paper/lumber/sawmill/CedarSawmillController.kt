package ru.ruscrafting.farms.paper.lumber.sawmill

import org.bukkit.World
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.SceneAudience
import ru.ruscrafting.farms.domain.lumber.LumberPhase
import ru.ruscrafting.farms.domain.lumber.LumberVisualView

/** The installed machine presents the worksite's stock and clock; it never manufactures a new round. */
internal class CedarSawmillController(world: World, displays: PaperPacketDisplays) : AutoCloseable {
    private val visuals = CedarSawmillDisplays(world, displays)

    fun render(view: LumberVisualView, audience: SceneAudience) {
        visuals.render(frame(view), view.state.sawRunning, audience)
    }

    override fun close() = visuals.close()

    companion object {
        const val WORLD_NAME = "rc_atelier_cedar_sawmill"
        const val PASS_TICKS = 500
        private val stock = CedarSawmillGeometry.parts.filter { it.motion.startsWith("timber-") }.mapTo(hashSetOf()) { it.id }

        fun frame(view: LumberVisualView): CedarSawmillAnimation.Frame {
            val state = view.state
            val loaded = state.phase in setOf(LumberPhase.SAWING, LumberPhase.COLLECTING_BOARDS)
            val pass = if (state.sawRunning) state.sawPasses.coerceIn(0, 1) else (state.sawPasses - 1).coerceIn(0, 1)
            val time = if (state.sawRunning) view.sawElapsedTicks.coerceIn(0, PASS_TICKS) / 20.0
                else if (state.sawPasses > 0) CedarSawmillAnimation.DURATION_SECONDS else 0.0
            val frame = CedarSawmillAnimation.sample(if (loaded) time else 0.0, if (loaded) pass else 0)
            return if (loaded) frame else frame.copy(contact = false, poses = frame.poses.map { pose ->
                if (pose.partId in stock) pose.copy(visible = false) else pose
            })
        }
    }
}

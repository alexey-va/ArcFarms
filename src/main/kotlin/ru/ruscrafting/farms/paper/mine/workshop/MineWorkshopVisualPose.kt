package ru.ruscrafting.farms.paper.mine.workshop

import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.mine.expedition.MineDisplayBlueprints

/**
 * Pure display pose math shared by the live packet renderer and the offline
 * composite validator/exporter.  Keeping path progression here prevents a
 * preview from silently losing per-piece offsets authored in [Visual.path].
 */
internal object MineWorkshopVisualPose {
    data class Pose(
        val center: Vector3f,
        val size: Vector3f,
        val rotation: Quaternionf,
    )

    fun pose(
        visual: MineWorkshopMachines.Visual,
        phase: Float,
        state: MineWorkshopVisualState,
    ): Pose {
        val localCenter = if (visual.animation == MineWorkshopMachines.Animation.NEEDLE) {
            Vector3f(visual.part.center).add(0f, state.temperature.coerceIn(0f, 1f), 0f)
        } else if (visual.path.isNotEmpty() && visual.animation in PATH_ANIMATIONS) {
            interpolate(visual.path, progress(visual.animation, state))
        } else {
            MineDisplayBlueprints.center(visual.part, phase)
        }
        val scaledCenter = Vector3f(localCenter).mul(visual.scale).add(visual.offset)
        val center = if (visual.yaw == 0f) {
            scaledCenter
        } else {
            Quaternionf().rotateY(visual.yaw).transform(scaledCenter)
        }
        val rotation = MineDisplayBlueprints.rotation(visual.part, phase)
        val orientedRotation = if (visual.yaw == 0f) {
            rotation
        } else {
            Quaternionf().rotateY(visual.yaw).mul(rotation)
        }
        return Pose(
            center = center,
            size = Vector3f(visual.part.size).mul(visual.scale),
            rotation = orientedRotation,
        )
    }

    private fun progress(
        animation: MineWorkshopMachines.Animation,
        state: MineWorkshopVisualState,
    ): Float = when (animation) {
        MineWorkshopMachines.Animation.CARGO -> state.transfer
        MineWorkshopMachines.Animation.MOLTEN -> MineWorkshopAnimation.hotProgress(state.pouring)
        MineWorkshopMachines.Animation.COOLING -> MineWorkshopAnimation.coolingProgress(state.pouring)
        else -> 0f
    }

    private fun interpolate(path: List<Vector3f>, progress: Float): Vector3f {
        if (path.isEmpty()) return Vector3f()
        if (path.size == 1) return Vector3f(path.single())
        val normalized = progress.coerceIn(0f, 1f) * (path.size - 1)
        val index = normalized.toInt().coerceAtMost(path.size - 2)
        return Vector3f(path[index]).lerp(path[index + 1], normalized - index)
    }

    private val PATH_ANIMATIONS = setOf(
        MineWorkshopMachines.Animation.CARGO,
        MineWorkshopMachines.Animation.MOLTEN,
        MineWorkshopMachines.Animation.COOLING,
    )
}

package ru.ruscrafting.farms.paper.mine.workshop

import ru.ruscrafting.farms.paper.worksite.WorksiteDisplayGeometryValidator
import kotlin.math.PI

/**
 * Pure geometry samples for the runtime workshop composites.  The existing
 * mine validator audits blueprint kinds, but it cannot see assembly scale,
 * yaw, offsets or one-way path poses added by [MineWorkshopMachines].
 */
internal object MineWorkshopModelValidation {
    data class Sample(
        val role: String,
        val phase: Float = 0f,
        val crushing: Boolean = false,
        val transfer: Float = -1f,
        val pouring: Float = -1f,
    )

    /** Samples used by both tests and the build-time validator hook. */
    val samples: List<Sample> = buildList {
        val static = listOf("ore", "crusher", "furnace", "output", "shipping")
        static.forEach { add(Sample(it)) }
        (0..4).forEach { step ->
            add(Sample("crusher", phase = (step * PI / 2).toFloat(), crushing = true))
        }
        (0..4).forEach { step ->
            add(Sample("crusher", phase = (step * PI / 2).toFloat(), transfer = step / 4f))
        }
        listOf(0f, .2f, .39f, .4f, .6f, .8f, 1f).forEach { pouring ->
            add(Sample("output", pouring = pouring))
        }
    }

    /** Runtime-equivalent cuboids for one authored composite pose. */
    fun boxes(sample: Sample): List<WorksiteDisplayGeometryValidator.Box> {
        val visuals = MineWorkshopMachines.previewVisuals(sample.role)
        val state = MineWorkshopVisualState(
            running = true,
            crushing = sample.crushing,
            transfer = sample.transfer,
            pouring = sample.pouring,
        )
        return visuals.mapIndexedNotNull { index, visual ->
            if (!visible(visual, sample)) return@mapIndexedNotNull null
            val phase = phase(visual, sample)
            val pose = MineWorkshopVisualPose.pose(visual, phase, state)
            WorksiteDisplayGeometryValidator.Box(
                "${sample.role}:$index:${visual.animation}:${visual.part.motion}:${visual.part.material}",
                pose.center,
                pose.size,
                pose.rotation,
            )
        }
    }

    /** Return de-duplicated face conflicts across every sampled runtime pose. */
    fun validate(): List<String> = buildList {
        samples.forEach { sample ->
            val seen = hashSetOf<String>()
            WorksiteDisplayGeometryValidator.conflicts(boxes(sample)).forEach { conflict ->
                val pair = "${conflict.first}/${conflict.firstFace} <-> ${conflict.second}/${conflict.secondFace}"
                if (seen.add(pair)) {
                    add("${sample.role} phase=${sample.phase} transfer=${sample.transfer} pouring=${sample.pouring} " +
                        "$pair area=${conflict.area} gap=${conflict.separation}")
                }
            }
        }
    }

    private fun visible(visual: MineWorkshopMachines.Visual, sample: Sample): Boolean {
        if (!visual.part.idleHidden) return true
        return when (visual.animation) {
            MineWorkshopMachines.Animation.FEED -> sample.crushing
            MineWorkshopMachines.Animation.CARGO -> sample.transfer >= 0f
            MineWorkshopMachines.Animation.MOLTEN ->
                sample.pouring >= 0f && MineWorkshopAnimation.hotVisible(sample.pouring)
            MineWorkshopMachines.Animation.COOLING ->
                sample.pouring >= 0f && MineWorkshopAnimation.coolingVisible(sample.pouring)
            else -> false
        }
    }

    private fun phase(visual: MineWorkshopMachines.Visual, sample: Sample): Float = when (visual.animation) {
        MineWorkshopMachines.Animation.ROLLERS,
        MineWorkshopMachines.Animation.FEED,
        MineWorkshopMachines.Animation.BELT -> sample.phase
        MineWorkshopMachines.Animation.NEEDLE -> 1.2f
        else -> 0f
    }

}

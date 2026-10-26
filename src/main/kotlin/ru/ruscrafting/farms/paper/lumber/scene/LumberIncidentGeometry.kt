package ru.ruscrafting.farms.paper.lumber.scene

import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.lumber.LumberAction
import ru.ruscrafting.farms.domain.lumber.LumberIncidentType
import ru.ruscrafting.farms.domain.lumber.LumberInput
import ru.ruscrafting.farms.domain.lumber.LumberVisualView
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Temporary incident props share the existing workpiece scene and audience. No terrain or native models. */
internal object LumberIncidentGeometry {
    fun sample(view: LumberVisualView): List<LumberSceneGeometry.Part> = buildList {
        val incident = view.state.incident ?: return@buildList
        when (incident.type) {
            LumberIncidentType.FEED_JAM -> {
                val origin = LumberSceneGeometry.vector(LumberSceneGeometry.incidentCrank())
                val angle = if (incident.crankOperator == null) 0.0 else view.crankRadians
                val rotation = Quaternionf().rotationY(-angle.toFloat())
                val action = if (incident.crankOperator == null) LumberInput(LumberAction.START_CRANK) else null
                fun part(id: String, x: Float, y: Float, z: Float, size: Vector3f, block: String,
                         turn: Boolean = false, input: LumberInput? = null) {
                    val at = Vector3f(x, y, z)
                    if (turn) at.rotate(rotation)
                    add(LumberSceneGeometry.Part("incident-crank-$id", at.add(origin), size,
                        if (turn) Quaternionf(rotation) else Quaternionf(), block = block, action = input))
                }
                part("base", 0f, .06f, 0f, Vector3f(1.05f, .12f, 1.05f), "minecraft:dark_oak_planks")
                part("spindle", 0f, .43f, 0f, Vector3f(.22f, .62f, .22f), "minecraft:polished_deepslate")
                part("drum", 0f, .48f, 0f, Vector3f(.58f, .50f, .58f), "minecraft:stripped_spruce_log[axis=y]", true)
                for (height in listOf(.24f, .70f))
                    part("collar-$height", 0f, height, 0f, Vector3f(.72f, .10f, .72f), "minecraft:cut_copper", true)
                part("arm", .55f, .89f, 0f, Vector3f(1.55f, .13f, .17f), "minecraft:stripped_spruce_log[axis=x]", true)
                part("grip", 1.23f, .89f, 0f, Vector3f(.22f, .22f, .48f), "minecraft:dark_oak_log[axis=z]", true, action)
                for (index in 0..7) {
                    val a = index * PI / 4
                    part("step-$index", (cos(a) * 1.35).toFloat(), .015f, (sin(a) * 1.35).toFloat(),
                        Vector3f(.18f, .03f, .18f), "minecraft:yellow_terracotta")
                }
                // The low cable follows the existing feed-rail line through the doorway.
                add(LumberSceneGeometry.Part("incident-crank-cable", Vector3f(origin.x, origin.y + .43f, 195.125f),
                    Vector3f(.07f, .07f, 7.25f), block = "minecraft:dark_oak_log[axis=z]"))
            }
            LumberIncidentType.SAWDUST_SMOLDER -> {
                val active = incident.hotspots?.targets.orEmpty().filter { it.status != ObjectiveTargetStatus.COMPLETED }
                val anchors = LumberSceneGeometry.smolderTargets()
                active.forEach { target ->
                    val index = target.id.substringAfterLast('_').toIntOrNull() ?: return@forEach
                    val at = anchors.getOrNull(index) ?: return@forEach
                    add(LumberSceneGeometry.Part("incident-smolder-$index", LumberSceneGeometry.vector(at).add(0f, -.04f, 0f),
                        Vector3f(.78f, .18f, .78f), block = "minecraft:magma_block",
                        action = LumberInput(LumberAction.EXTINGUISH_SAWDUST, index)))
                    for (side in listOf(-1, 1)) add(LumberSceneGeometry.Part("incident-sawdust-$index-$side",
                        LumberSceneGeometry.vector(at).add(side * .22f, 0f, .04f), Vector3f(.32f, .16f, .65f),
                        Quaternionf().rotationY(side * .18f), block = "minecraft:stripped_spruce_log[axis=z]"))
                }
            }
        }
    }
}

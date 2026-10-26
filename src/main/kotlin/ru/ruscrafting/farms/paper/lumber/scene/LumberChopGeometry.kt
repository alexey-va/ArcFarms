package ru.ruscrafting.farms.paper.lumber.scene

import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.lumber.LumberVisualView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Accepted-hit response shares the durable hit count and a transient, post-commit clock with the export. */
internal object LumberChopGeometry {
    const val HIT_TICKS = 14

    fun tree(view: LumberVisualView): List<LumberSceneGeometry.Part> = buildList {
        val tree = view.state.tree ?: return@buildList
        val root = tree.root
        val cutMaterial = cutMaterial(view)
        val ratio = (view.state.chopHits.toDouble() / view.requiredChopHits.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val age = view.chopHitAgeTicks.toDouble()
        val angle = if (age in 0.0..HIT_TICKS.toDouble())
            (.004 + ratio * .014) * sin(age * PI / 3.5) * exp(-age / 4.0) else 0.0
        val rotation = Quaternionf().rotationAxis(angle.toFloat(), tree.fallZ.toFloat(), 0f, -tree.fallX.toFloat())
        val hinge = Vector3f(root.x + .5f, root.y + 2f, root.z + .5f)
        for ((index, block) in tree.blocks.withIndex()) {
            val point = block.position
            val center = Vector3f(point.x + .5f, point.y + .5f, point.z + .5f)
            if (view.state.chopHits > 0 && point.x == root.x && point.z == root.z && point.y == root.y + 1) {
                fun piece(id: String, at: Vector3f, size: Vector3f, material: String = block.blockData) {
                    add(LumberSceneGeometry.Part("standing-$index-$id", at, size, block = material, highlight = true))
                }
                piece("lower", Vector3f(center.x, point.y + .29f, center.z), Vector3f(1f, .58f, 1f))
                piece("upper", Vector3f(center.x, point.y + .94f, center.z), Vector3f(1f, .12f, 1f))
                val maximumDepth = (.10 + .34 * ratio).toFloat()
                for (slice in 0..5) {
                    val depth = maximumDepth * (1 - abs((slice + .5f) / 3 - 1))
                    val at = Vector3f(center.x - tree.fallX * depth / 2, point.y + .605f + slice * .05f,
                        center.z - tree.fallZ * depth / 2)
                    val size = Vector3f(1 - abs(tree.fallX) * depth, .05f, 1 - abs(tree.fallZ) * depth)
                    piece("notch-$slice", at, size)
                    val cut = Vector3f(center.x + tree.fallX * (.502f - depth), at.y,
                        center.z + tree.fallZ * (.502f - depth))
                    piece("cut-$slice", cut, Vector3f(if (tree.fallX == 0) 1f else .008f, .05f,
                        if (tree.fallZ == 0) 1f else .008f), cutMaterial)
                }
            } else {
                val upper = point.y >= root.y + 2
                if (upper) center.sub(hinge).rotate(rotation).add(hinge)
                add(LumberSceneGeometry.Part("standing-$index", center, Vector3f(1f),
                    if (upper) Quaternionf(rotation) else Quaternionf(), block = block.blockData, highlight = true))
            }
        }
    }

    fun chips(view: LumberVisualView): List<LumberSceneGeometry.Part> = buildList {
        val tree = view.state.tree ?: return@buildList
        val age = view.chopHitAgeTicks
        if (view.state.chopHits == 0 || age !in 0 until HIT_TICKS) return@buildList
        val t = age.toFloat()
        for (index in 0..6) {
            val side = (index - 3) / 3f
            val fan = side * (PI * .75).toFloat()
            val directionX = tree.fallX * cos(fan) + tree.fallZ * sin(fan)
            val directionZ = tree.fallZ * cos(fan) - tree.fallX * sin(fan)
            // A broad burst clears the square stem and reads from the approach side too.
            val outward = .59f / maxOf(abs(directionX), abs(directionZ)) + t * (.035f + (index % 3) * .012f)
            val at = Vector3f(tree.root.x + .5f + directionX * outward,
                tree.root.y + 1.73f + t * (.035f + (index % 2) * .01f) - t * t * .006f,
                tree.root.z + .5f + directionZ * outward)
            val scale = (.045f + index % 3 * .012f) * (1 - t / HIT_TICKS)
            add(LumberSceneGeometry.Part("chop-chip-$index", at, Vector3f(scale * 1.8f, scale * .6f, scale),
                Quaternionf().rotationXYZ(t * .25f + index, t * .31f, side), cutMaterial(view)))
        }
    }

    private fun cutMaterial(view: LumberVisualView): String = view.state.tree?.let { tree ->
        tree.logs.firstOrNull { it.position == tree.root }?.blockData?.substringBefore('[')
            ?.replace("minecraft:", "minecraft:stripped_")?.plus("[axis=y]")
    } ?: "minecraft:stripped_oak_log[axis=y]"
}

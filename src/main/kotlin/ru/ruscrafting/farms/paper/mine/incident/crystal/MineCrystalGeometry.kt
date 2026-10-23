package ru.ruscrafting.farms.paper.mine.incident.crystal

import org.joml.Vector3d
import ru.ruscrafting.farms.domain.mine.crystal.MineCrystalPhase
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Shared live/preview samples. Positions are emission sites, not a simulation of native particle lifetime. */
internal object MineCrystalGeometry {
    const val CONVERGE_MILLIS = 1_400L
    const val DRILL_MILLIS = 3_600L
    const val EXTRACT_MILLIS = 1_800L
    const val EMISSION_MILLIS = 150L
    const val MAX_SAMPLES = 240
    data class Mote(val position: Vector3d, val color: Int, val size: Float)

    fun hub(nodes: List<Vector3d>): Vector3d = Vector3d(
        nodes.sumOf { it.x } / nodes.size,
        nodes.maxOf { it.y } + 1.65,
        nodes.sumOf { it.z } / nodes.size,
    )

    fun impact(contacts: List<Vector3d>, phase: MineCrystalPhase, phaseAge: Long): Vector3d {
        require(contacts.isNotEmpty())
        if (phase == MineCrystalPhase.EXTRACTING) return Vector3d(contacts.last())
        val travel = ((phaseAge - 500).coerceAtLeast(0) / 650.0).coerceAtMost((contacts.size - 1).toDouble())
        val index = travel.toInt()
        val u = travel - index
        return Vector3d(contacts[index]).lerp(contacts[minOf(index + 1, contacts.lastIndex)], u * u * (3 - 2 * u))
    }

    fun sample(nodes: List<Vector3d>, charged: Set<Int>, wall: Vector3d, phase: MineCrystalPhase,
        phaseAge: Long, now: Long): List<Mote> = buildList {
        if (nodes.size != 4) return@buildList
        val centre = hub(nodes)
        val t = now / 1_000.0
        val active = charged.sortedBy { kotlin.math.atan2(nodes[it].z - centre.z, nodes[it].x - centre.x) }
        // A small six-point crown above the native tip; no duplicate crossed crystal mesh.
        nodes.forEachIndexed { index, node ->
            repeat(6) { point ->
                val a = point * PI / 3 + t * .7
                add(Mote(Vector3d(node).add(cos(a) * .22, .08 + sin(t * 2 + index) * .04, sin(a) * .22),
                    if (index in charged) 0xF3DDFF else 0xBA74EA, if (index in charged) 1.15f else .75f))
            }
        }
        // A bright closed perimeter links every charged node without the visual clutter of diagonals.
        // Spend the fixed sample budget on four continuous edges instead of six sparse dotted ones.
        val gathering = phase == MineCrystalPhase.CONVERGING
        val perimeterFade = if (gathering) (1.0 - phaseAge / 350.0).coerceIn(0.0, 1.0) else 1.0
        if ((phase == MineCrystalPhase.CHARGING || gathering && perimeterFade > 0) && active.size >= 2) {
            val edges = active.zipWithNext() + if (active.size > 2) listOf(active.last() to active.first()) else emptyList()
            edges.forEach { (a, b) ->
            line(nodes[a], nodes[b], .35, if (gathering) 12 else 48) { p, u ->
                val crest = sin(u * PI) * .3
                add(Mote(p.add(0.0, crest, 0.0), if (gathering) 0xB483D5 else 0xD9A1FF,
                    if (gathering) (.35 + 1.65 * perimeterFade).toFloat() else 2.0f))
            }
        } }
        if (phase == MineCrystalPhase.CHARGING) return@buildList
        val convergence = if (gathering)
            (phaseAge.toDouble() / CONVERGE_MILLIS).coerceIn(0.0, 1.0) else 1.0
        nodes.forEach { node ->
            val end = Vector3d(node).lerp(centre, convergence)
            // While converging, the four inward-moving arms own the light budget. Their pale
            // bodies and bright heads separate from the native purple machinery without moving the hub.
            line(node, end, if (gathering) .28 else .4, if (gathering) 36 else 10) { p, u ->
                add(Mote(p, if (gathering) 0xF0DEFF else if (u > .8) 0xF5EFFF else 0xD9A1FF,
                    if (gathering) 3.1f else 1.25f))
            }
            if (gathering) add(Mote(Vector3d(end), 0xFFF8ED, 3.8f))
        }
        if (phase == MineCrystalPhase.CONVERGING) return@buildList
        val extract = phase == MineCrystalPhase.EXTRACTING
        val length = centre.distance(wall)
        val direction = Vector3d(wall).sub(centre).normalize()
        val side = Vector3d(direction.z, 0.0, -direction.x).normalize()
        val up = Vector3d(side).cross(direction).normalize()
        val reach = if (extract) 1.0 else (phaseAge / 350.0).coerceIn(0.0, 1.0)
        val end = Vector3d(centre).lerp(wall, reach)
        // A dense white axial body reads as one powerful beam from the floor. Reassign samples
        // from the slower shoulders to the core rather than raising the 240-mote emission cap.
        if (!extract) line(centre, end, .26, 72) { p, _ -> add(Mote(p, 0xFFF6FF, 3.2f)) }
        line(centre, end, if (extract) .42 else .7, if (extract) 48 else 36) { p, u ->
            val angle = u * length * 2.9 - t * 10
            val radius = if (extract) .22 else .48 + .03 * sin(t * 9)
            repeat(if (extract) 1 else 2) { strand ->
                val a = angle + strand * PI
                add(Mote(Vector3d(p).add(Vector3d(side).mul(cos(a) * radius)).add(Vector3d(up).mul(sin(a) * radius)),
                    if (extract) 0xFFD784 else 0xC986FF, if (extract) 1.35f else 2.0f))
            }
        }
        // Fracture rim stays on the actual contact plane rather than surrounding the entire scene.
        if (!extract && phaseAge >= 350) repeat(18) { i ->
            val a = i * PI / 9 + t * 2.6
            val radius = .5 + .12 * sin(t * 8 + i * .8)
            add(Mote(Vector3d(wall).add(Vector3d(side).mul(cos(a) * radius)).add(Vector3d(up).mul(sin(a) * radius)),
                if (i % 3 == 0) 0xFFF1B0 else 0xDAA0FF, 1.6f))
        }
    }

    private inline fun line(from: Vector3d, to: Vector3d, spacing: Double, limit: Int, emit: (Vector3d, Double) -> Unit) {
        val count = ceil(from.distance(to) / spacing).toInt().coerceIn(1, limit)
        for (i in 0..count) { val u = i.toDouble() / count; emit(Vector3d(from).lerp(to, u), u) }
    }
}

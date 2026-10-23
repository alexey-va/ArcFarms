package ru.ruscrafting.farms.paper.mine.incident.gas

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Twelve coherent rolling lobes, with a low bank and an open upper edge instead of a uniform noisy sphere. */
internal object MineGasCloudGeometry {
    data class Lobe(val x: Double, val y: Double, val z: Double, val spread: Double, val color: Int, val size: Float,
        val wisps: Boolean)

    fun sample(now: Long): List<Lobe> = (0 until 12).map { index ->
        val layer = index / 4
        val phase = index * PI / 2 + layer * .67
        val t = now / 1_000.0
        val angle = phase + t * (.15 + layer * .045)
        val radius = when (layer) { 0 -> 3.1; 1 -> 2.25; else -> 1.3 } + .32 * sin(t * .6 + phase)
        // The vent origin is the support block's top surface; keep the low bank above that plane.
        Lobe(cos(angle) * radius, when (layer) { 0 -> .35; 1 -> 1.05; else -> 1.95 } + .16 * sin(t * .8 + phase),
            sin(angle) * radius, if (layer == 0) .85 else .6,
            when (layer) { 0 -> 0x8A994F; 1 -> 0xA5B36D; else -> 0xBAC58A },
            if (layer == 1) 4.0f else 3.6f, layer == 2)
    }
}

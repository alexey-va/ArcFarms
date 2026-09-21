package ru.ruscrafting.farms.paper.worksite

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.util.Vector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Shared packet-free water spray rendering for worksite incidents.
 *
 * The caller remains responsible for access checks, cooldowns and deciding
 * whether particles are enabled.  This object only renders the same bounded
 * cone used by the farm fire hose, so mine experiments can reuse the visual
 * without importing farm state or settings.
 */
internal object WorksiteWaterJet {
    /** Returns targets intersected by the bounded forward spray, nearest first. */
    fun <K> hitTargets(
        targets: Iterable<Pair<K, Location>>,
        start: Location,
        direction: Vector,
        range: Double,
        radius: Double,
    ): List<K> {
        if (!range.isFinite() || !radius.isFinite() || range < 0.0 || radius < 0.0) return emptyList()
        if (!finite(direction) || direction.lengthSquared() == 0.0) return emptyList()
        val forward = direction.clone().normalize()
        return targets.mapNotNull { (key, location) ->
            if (location.world !== start.world) return@mapNotNull null
            val relative = location.toVector().subtract(start.toVector())
            val along = relative.dot(forward)
            if (along !in 0.0..range) return@mapNotNull null
            val closest = start.toVector().add(forward.clone().multiply(along))
            val distanceSquared = location.toVector().distanceSquared(closest)
            if (distanceSquared > radius * radius) null else Triple(key, along, distanceSquared)
        }.sortedWith(compareBy<Triple<K, Double, Double>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    fun renderJet(
        start: Location,
        direction: Vector,
        range: Double,
        step: Double,
        sideStreams: Int,
    ) {
        if (!range.isFinite() || !step.isFinite() || range < 0.0 || step <= 0.0 || sideStreams < 0) return
        if (!finite(direction) || direction.lengthSquared() == 0.0) return
        val world = start.world ?: return
        val forward = direction.clone().normalize()
        val reference = if (abs(forward.y) < 0.92) Vector(0.0, 1.0, 0.0) else Vector(1.0, 0.0, 0.0)
        val right = forward.clone().crossProduct(reference).normalize()
        val up = right.clone().crossProduct(forward).normalize()
        val streams = sideStreams.coerceAtMost(MAX_SIDE_STREAMS)
        // Keep the farm's original spacing while it remains bounded.  A tiny
        // step is deliberately resampled across the whole segment instead of
        // looping forever (the zero-length segment still emits one splash).
        val ratio = range / step
        val capped = ratio >= MAX_SAMPLES.toDouble()
        val expectedSamples = if (capped) MAX_SAMPLES else floor(ratio).toInt().coerceAtLeast(0) + 1
        val samples = expectedSamples.coerceIn(1, MAX_SAMPLES)
        repeat(samples) { sample ->
            val distance = if (capped && samples > 1) {
                range * sample.toDouble() / (samples - 1).toDouble()
            } else {
                (sample * step).coerceAtMost(range)
            }
            val center = start.clone().add(forward.clone().multiply(distance))
            world.spawnParticle(Particle.SPLASH, center, 1, 0.06, 0.06, 0.06, 0.02)
            if (sample % 2 == 0 && streams > 0) {
                val coneRadius = 0.12 + (distance / range.coerceAtLeast(1.0)).coerceIn(0.0, 1.0) * 0.82
                repeat(streams) { stream ->
                    val angle = (stream.toDouble() / streams * PI * 2.0) + sample * 0.47
                    val spray = center.clone()
                        .add(right.clone().multiply(cos(angle) * coneRadius))
                        .add(up.clone().multiply(sin(angle) * coneRadius))
                    world.spawnParticle(Particle.SPLASH, spray, 2, 0.1, 0.1, 0.1, 0.045)
                }
            }
        }
    }

    private fun finite(vector: Vector): Boolean =
        vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()

    private const val MAX_SAMPLES = 256
    private const val MAX_SIDE_STREAMS = 32
}

package ru.ruscrafting.farms.paper.farm.incident.route

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.paper.FarmRuntime
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Player-local route trail plus a wide, unmistakable delivery destination. */
internal object FarmFoodDeliveryRouteVisual {
    private val trailColor = Color.fromRGB(69, 200, 245)
    private val nextColor = Color.fromRGB(255, 200, 87)
    private val finishColor = Color.fromRGB(74, 232, 126)

    fun render(runtime: FarmRuntime, rider: Player, points: List<FarmPointPosition>) {
        val config = runtime.settings.routeDelivery
        val current = runtime.state.incidentProgress.coerceIn(1, points.size)
        val start = (current - 1).coerceAtLeast(0)
        val endExclusive = (start + config.trailLookaheadPoints).coerceAtMost(points.size)
        val dust = Particle.DustOptions(trailColor, config.trailParticleSize)
        var emitted = 0
        points.subList(start, endExclusive).zipWithNext().forEach { (from, to) ->
            if (emitted >= MAX_TRAIL_PARTICLES || from.world != rider.world.name || to.world != rider.world.name) return@forEach
            val origin = location(rider, from).add(0.0, config.trailHeight, 0.0)
            val end = location(rider, to).add(0.0, config.trailHeight, 0.0)
            val delta = end.toVector().subtract(origin.toVector())
            val distance = delta.length()
            if (distance <= 0.01) return@forEach
            val step = delta.normalize().multiply(config.trailSpacing)
            val cursor = origin.clone()
            repeat(ceil(distance / config.trailSpacing).toInt()) {
                if (emitted++ >= MAX_TRAIL_PARTICLES) return@repeat
                rider.spawnParticle(Particle.DUST, cursor, 1, 0.035, 0.025, 0.035, 0.0, dust)
                cursor.add(step)
            }
        }
        val next = points[(start + NEXT_MARKER_OFFSET).coerceAtMost(points.lastIndex)]
        renderColumn(rider, location(rider, next).add(0.0, config.trailHeight, 0.0), nextColor, 7, config.trailParticleSize + 0.2f)
        renderDestination(runtime, rider, points.last())
    }

    private fun renderDestination(runtime: FarmRuntime, rider: Player, point: FarmPointPosition) {
        if (rider.world.name != point.world) return
        val config = runtime.settings.routeDelivery
        val center = location(rider, point).add(0.0, config.trailHeight, 0.0)
        val samples = max(28, ceil(config.checkpointRadius * 6.0).toInt())
        val ring = Particle.DustOptions(finishColor, config.trailParticleSize + 0.25f)
        repeat(samples) { index ->
            val angle = index.toDouble() / samples * PI * 2.0
            rider.spawnParticle(
                Particle.DUST,
                center.clone().add(cos(angle) * config.checkpointRadius, 0.0, sin(angle) * config.checkpointRadius),
                1,
                0.03,
                0.03,
                0.03,
                0.0,
                ring,
            )
        }
        renderColumn(rider, center, finishColor, 10, config.trailParticleSize + 0.35f)
    }

    private fun renderColumn(
        rider: Player,
        base: Location,
        color: Color,
        layers: Int,
        size: Float,
    ) {
        val dust = Particle.DustOptions(color, size)
        repeat(layers) { layer ->
            rider.spawnParticle(
                Particle.DUST,
                base.clone().add(0.0, 0.55 + layer * 0.55, 0.0),
                1,
                0.04,
                0.04,
                0.04,
                0.0,
                dust,
            )
        }
    }

    private fun location(rider: Player, point: FarmPointPosition): Location =
        Location(rider.world, point.x, point.y, point.z, point.yaw, point.pitch)

    private const val MAX_TRAIL_PARTICLES = 128
    private const val NEXT_MARKER_OFFSET = 5
}

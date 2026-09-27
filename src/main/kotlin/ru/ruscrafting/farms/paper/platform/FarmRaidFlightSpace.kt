package ru.ruscrafting.farms.paper.platform

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Conservative collision envelope for the raid ghast and its rider deck. */
internal data class FarmRaidFlightEnvelope(
    val horizontalRadius: Double,
    val minimumYOffset: Double,
    val maximumYOffset: Double,
) {
    init {
        require(horizontalRadius.isFinite() && horizontalRadius > 0.0)
        require(minimumYOffset.isFinite() && maximumYOffset.isFinite() && minimumYOffset < maximumYOffset)
    }

    companion object {
        fun forRaid(maximumRiders: Int, seatSpacing: Double, seatYOffset: Double): FarmRaidFlightEnvelope {
            val seats = ru.ruscrafting.farms.domain.FarmRaidSeatPolicy.deck(
                maximumRiders,
                seatSpacing,
                seatYOffset,
            )
            val seatRadius = seats.maxOf { max(abs(it.x), abs(it.z)) + PLAYER_HALF_WIDTH }
            return FarmRaidFlightEnvelope(
                horizontalRadius = max(GHAST_HALF_WIDTH, seatRadius) + COLLISION_MARGIN,
                minimumYOffset = min(0.0, seatYOffset) - COLLISION_MARGIN,
                maximumYOffset = max(GHAST_HEIGHT, seatYOffset + PLAYER_HEIGHT) + COLLISION_MARGIN,
            )
        }

        private const val GHAST_HALF_WIDTH = 2.0
        private const val GHAST_HEIGHT = 4.0
        private const val PLAYER_HALF_WIDTH = 0.3
        private const val PLAYER_HEIGHT = 1.8
        private const val COLLISION_MARGIN = 0.1
    }
}

/** Bounded Paper collision queries used by the raid's local flight navigator. */
internal interface FarmRaidFlightSpace {
    fun isClearAt(world: World, center: Location, envelope: FarmRaidFlightEnvelope): Boolean

    fun isClearSegment(
        world: World,
        from: Location,
        to: Location,
        envelope: FarmRaidFlightEnvelope,
    ): Boolean
}

internal object PaperFarmRaidFlightSpace : FarmRaidFlightSpace {
    override fun isClearAt(world: World, center: Location, envelope: FarmRaidFlightEnvelope): Boolean {
        if (center.world !== world || !center.x.isFinite() || !center.y.isFinite() || !center.z.isFinite()) return false
        val minimumX = floor(center.x - envelope.horizontalRadius).toInt()
        val maximumX = floor(center.x + envelope.horizontalRadius - EDGE_EPSILON).toInt()
        val minimumY = floor(center.y + envelope.minimumYOffset).toInt()
        val maximumY = floor(center.y + envelope.maximumYOffset - EDGE_EPSILON).toInt()
        val minimumZ = floor(center.z - envelope.horizontalRadius).toInt()
        val maximumZ = floor(center.z + envelope.horizontalRadius - EDGE_EPSILON).toInt()
        if (minimumY < world.minHeight || maximumY >= world.maxHeight) return false

        for (x in minimumX..maximumX) {
            for (z in minimumZ..maximumZ) {
                val chunkX = x shr 4
                val chunkZ = z shr 4
                if (!world.isChunkLoaded(chunkX, chunkZ)) return false
                for (y in minimumY..maximumY) {
                    val block = world.getBlockAt(x, y, z)
                    if (block.type in HAZARD_BLOCKS || !block.isPassable) return false
                }
            }
        }
        return true
    }

    override fun isClearSegment(
        world: World,
        from: Location,
        to: Location,
        envelope: FarmRaidFlightEnvelope,
    ): Boolean {
        if (from.world !== world || to.world !== world) return false
        val distance = from.toVector().distance(to.toVector())
        if (!distance.isFinite() || distance > MAX_SEGMENT_DISTANCE) return false
        val steps = kotlin.math.ceil(distance / SEGMENT_SAMPLE_DISTANCE).toInt().coerceAtMost(MAX_SEGMENT_STEPS)
        for (step in 0..steps) {
            val fraction = if (steps == 0) 0.0 else step.toDouble() / steps
            val sample = from.clone().add(
                (to.x - from.x) * fraction,
                (to.y - from.y) * fraction,
                (to.z - from.z) * fraction,
            )
            if (!isClearAt(world, sample, envelope)) return false
        }
        return true
    }

    private const val EDGE_EPSILON = 1.0e-7
    private const val SEGMENT_SAMPLE_DISTANCE = 0.5
    private const val MAX_SEGMENT_STEPS = 32
    private const val MAX_SEGMENT_DISTANCE = SEGMENT_SAMPLE_DISTANCE * MAX_SEGMENT_STEPS
    private val HAZARD_BLOCKS = setOf(Material.LAVA, Material.FIRE, Material.SOUL_FIRE)
}

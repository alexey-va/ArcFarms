package ru.ruscrafting.farms.paper.farm.presentation

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.util.Vector
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.platform.FarmBlastDebrisVisuals
import java.util.concurrent.ThreadLocalRandom
import java.util.random.RandomGenerator
import kotlin.math.cos
import kotlin.math.sin

/** Bounded cosmetic ballistics. No entity, collision or block mutation belongs to this effect. */
internal class FarmBlastDebris(
    private val visuals: FarmBlastDebrisVisuals,
    private val random: RandomGenerator = ThreadLocalRandom.current(),
) {
    private data class Fragment(
        val visual: FarmBlastDebrisVisuals.Fragment,
        val position: Location,
        val velocity: Vector,
        val floorY: Double,
        var remainingTicks: Int,
    )
    private val byZone = mutableMapOf<String, ArrayDeque<Fragment>>()

    fun spawn(zoneId: String, center: Location, plots: List<FarmPlotPosition>, maximumBlocks: Int, lifetimeTicks: Int) {
        if (maximumBlocks <= 0) return
        val sources = plots.flatMap { plot ->
            val soil = plot.block() ?: return@flatMap emptyList()
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            buildList<BlockData> {
                if (!crop.type.isAir) add(crop.blockData)
                if (!soil.type.isAir) add(soil.blockData)
            }
        }.ifEmpty { listOf(Material.DIRT.createBlockData()) }
        val fragments = byZone.getOrPut(zoneId, ::ArrayDeque)
        val count = maximumBlocks.coerceAtMost(64)
        while (fragments.size + count > MAXIMUM_FRAGMENTS_PER_ZONE) fragments.removeFirst().visual.remove()
        repeat(count) { index ->
            val angle = 2.0 * Math.PI * index / count + random.nextDouble(-0.16, 0.16)
            val origin = center.clone().add(
                random.nextDouble(-0.25, 0.25), random.nextDouble(0.15, 0.4), random.nextDouble(-0.25, 0.25),
            )
            val horizontalSpeed = random.nextDouble(0.26, 0.46)
            val velocity = Vector(cos(angle) * horizontalSpeed, random.nextDouble(0.35, 0.55), sin(angle) * horizontalSpeed)
            val visual = visuals.spawn(origin, sources[index % sources.size])
            fragments.addLast(Fragment(visual, origin, velocity, center.y - 0.4, lifetimeTicks.coerceIn(1, 100)))
        }
    }

    fun tick(zoneId: String) {
        val fragments = byZone[zoneId] ?: return
        val iterator = fragments.iterator()
        while (iterator.hasNext()) {
            val fragment = iterator.next()
            fragment.remainingTicks--
            fragment.position.add(fragment.velocity)
            fragment.velocity.multiply(DRAG)
            fragment.velocity.y -= GRAVITY
            if (fragment.remainingTicks <= 0 || fragment.position.y <= fragment.floorY) {
                fragment.visual.remove()
                iterator.remove()
            } else {
                fragment.visual.move(fragment.position)
            }
        }
        if (fragments.isEmpty()) byZone.remove(zoneId)
    }

    fun activeCount(zoneId: String): Int = byZone[zoneId]?.size ?: 0

    fun clear(zoneId: String) {
        byZone.remove(zoneId)?.forEach { it.visual.remove() }
    }

    fun cleanup() {
        byZone.keys.toList().forEach(::clear)
        visuals.cleanup()
    }

    private companion object {
        const val MAXIMUM_FRAGMENTS_PER_ZONE = 192
        const val DRAG = 0.94
        const val GRAVITY = 0.06
    }
}

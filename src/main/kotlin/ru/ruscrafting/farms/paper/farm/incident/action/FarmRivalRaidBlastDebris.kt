package ru.ruscrafting.farms.paper.farm.incident.action

import org.bukkit.Location
import org.bukkit.entity.FallingBlock
import org.bukkit.util.Vector
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.paper.block
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.cos
import kotlin.math.sin

internal object FarmRivalRaidBlastDebris {
    fun spawn(center: Location, plots: List<FarmPlotPosition>, maximumBlocks: Int): List<FallingBlock> {
        val count = minOf(maximumBlocks, plots.size)
        if (count <= 0) return emptyList()
        val random = ThreadLocalRandom.current()
        return plots.take(count).mapIndexedNotNull { index, plot ->
            val soil = plot.block() ?: return@mapIndexedNotNull null
            val crop = soil.getRelative(org.bukkit.block.BlockFace.UP)
            val blockData = if (index % 3 == 0 && !crop.type.isAir) crop.blockData else soil.blockData
            val angle = 2.0 * Math.PI * index / count + random.nextDouble(-0.16, 0.16)
            val origin = center.clone().add(
                random.nextDouble(-0.35, 0.35),
                random.nextDouble(0.25, 0.65),
                random.nextDouble(-0.35, 0.35),
            )
            val horizontalSpeed = random.nextDouble(0.48, 0.86)
            soil.world.spawn(origin, FallingBlock::class.java) { debris ->
                debris.blockData = blockData
                debris.dropItem = false
                debris.cancelDrop = true
                debris.setHurtEntities(false)
                debris.velocity = Vector(
                    cos(angle) * horizontalSpeed,
                    random.nextDouble(0.72, 1.12),
                    sin(angle) * horizontalSpeed,
                )
            }
        }
    }
}

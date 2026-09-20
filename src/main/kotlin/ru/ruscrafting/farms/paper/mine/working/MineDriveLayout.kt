package ru.ruscrafting.farms.paper.mine.working

import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

/** Solid, journalled drilling ground. Bedrock ribs leave alternating wide bypasses. */
internal object MineDriveLayout {
    const val HALF_WIDTH = 8
    const val LENGTH = 44
    const val STRIDE = HALF_WIDTH * 2 + 1
    const val MAX_CELLS = STRIDE * (LENGTH + 1)
    const val CONTRIBUTION_BUDGET = 93 // Previous drive: 90 face blocks + 3 supports.
    private const val AIR = "minecraft:air"
    private const val BEDROCK = "minecraft:bedrock"

    fun enabled(placement: MineWorkingPlacement) = placement.geometryVersion >= 5
    fun id(side: Int, forward: Int) = forward * STRIDE + side + HALF_WIDTH
    fun side(id: Int) = id % STRIDE - HALF_WIDTH
    fun forward(id: Int) = id / STRIDE
    fun position(placement: MineWorkingPlacement, id: Int, up: Int = 1) = placement.position(side(id), up, forward(id))
    fun bedrock(side: Int, forward: Int): Boolean =
        (forward in 12..14 && side <= 1) || (forward in 26..28 && side >= -1)
    fun radius(forward: Int) = if (forward <= 1) 1 else if (forward == 2) 3 else HALF_WIDTH - 1
    fun driveable(side: Int, forward: Int) = forward in 1 until LENGTH && kotlin.math.abs(side) <= radius(forward) && !bedrock(side, forward)
    fun local(placement: MineWorkingPlacement, x: Double, z: Double): Pair<Double, Double> {
        val dx = x - placement.entrance.x - .5
        val dz = z - placement.entrance.z - .5
        return when (placement.direction) { 0 -> dx to dz; 1 -> dz to -dx; 2 -> -dx to -dz; else -> -dz to dx }
    }

    fun plan(placement: MineWorkingPlacement): MineWorkingPlan {
        val blocks = linkedMapOf<WorksitePosition, String>()
        val walkable = linkedSetOf<WorksitePosition>()
        for (f in 0..LENGTH) {
            val width = if (f <= 1) 2 else if (f == 2) 4 else HALF_WIDTH
            for (s in -width..width) for (up in 0..6) {
                val p = placement.position(s, up, f)
                val interior = kotlin.math.abs(s) < width && f < LENGTH && up in 1..(if (f <= 1) 3 else 4)
                val opening = interior && (f <= 3 || f >= LENGTH - 4)
                val noise = WorksiteCoherentNoise.sample(placement.layoutSeed, s * .3, up * .3, f * .24)
                blocks[p] = when {
                    interior && bedrock(s, f) -> BEDROCK
                    opening -> AIR
                    noise > .24 -> "minecraft:tuff"
                    noise < -.27 -> "minecraft:andesite"
                    else -> "minecraft:deepslate"
                }
                if (interior) walkable += p
            }
        }
        val excavation = (4 until LENGTH - 4).map { placement.position(0, 1, it) }
        return MineWorkingPlan(MineIncidentType.TUNNEL_DRIVE, placement, blocks, blocks.keys,
            blocks.keys - walkable, walkable, excavation, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyMap(), emptySet(), placement.entrance)
    }
}

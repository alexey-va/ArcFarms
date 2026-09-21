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
    /** Keep steering around ribs, but never face the lift or excavate backwards. */
    fun inwardHeading(direction: Int, heading: Float): Float {
        val base = direction * 90f
        val delta = ((heading - base + 540f) % 360f + 360f) % 360f - 180f
        return (base + delta.coerceIn(-65f, 65f) + 360f) % 360f
    }

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

    fun goalOres(plan: MineWorkingPlan): List<WorksitePosition> = plan.blocks.filterValues {
        it == "minecraft:deepslate_diamond_ore"
    }.keys.filter { p -> listOf(p.copy(x=p.x-1),p.copy(x=p.x+1),p.copy(y=p.y-1),p.copy(y=p.y+1),p.copy(z=p.z-1),p.copy(z=p.z+1))
        .any { plan.blocks[it] == AIR } }.take(32)

    private fun diamondChamber(placement: MineWorkingPlacement, blocks: MutableMap<WorksitePosition,String>, walkable: MutableSet<WorksitePosition>) {
        val air = linkedSetOf<WorksitePosition>()
        for (f in 34..LENGTH) for (s in -HALF_WIDTH..HALF_WIDTH) for (up in 0..9) {
            val p = placement.position(s,up,f)
            val n = WorksiteCoherentNoise.sample(placement.layoutSeed,s*.29,up*.35,f*.27)
            val shape = s*s/32.0 + (f-39.0)*(f-39.0)/20.0 + (up-3.5)*(up-3.5)/19.0
            val hollow = f < LENGTH && kotlin.math.abs(s) < HALF_WIDTH && up>=1 && (shape < 1.0+n*.65 || (kotlin.math.abs(s)<=2 && f in 37..40 && up in 1..4))
            blocks[p] = if(hollow) AIR else when { n>.24 -> "minecraft:tuff"; n<-.27 -> "minecraft:calcite"; else -> "minecraft:deepslate" }
            if(hollow) { air+=p;walkable+=p } else walkable.remove(p)
        }
        // Coherent veins on the cave shell. Ordinary mining remains protected: these are the discovery, not a new loot source.
        for(f in 35..LENGTH) for(s in -HALF_WIDTH..HALF_WIDTH) for(up in 0..8) {
            val p=placement.position(s,up,f)
            if(p in air) continue
            val exposed=listOf(p.copy(x=p.x-1),p.copy(x=p.x+1),p.copy(y=p.y-1),p.copy(y=p.y+1),p.copy(z=p.z-1),p.copy(z=p.z+1)).any { it in air }
            if(exposed && WorksiteCoherentNoise.sample(placement.layoutSeed,s*.53,up*.49,f*.43) > -.06)
                blocks[p]="minecraft:deepslate_diamond_ore"
        }
        // A subdued ambient source exposes the chamber silhouette without a forest of lamps.
        listOf(placement.position(-3,3,40),placement.position(3,3,41)).filter { it in air }.forEach {
            blocks[it]="minecraft:light[level=10,waterlogged=false]"
        }
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
        if (placement.geometryVersion >= 6) diamondChamber(placement, blocks, walkable)
        val excavation = (4 until LENGTH - 4).map { placement.position(0, 1, it) }.filter { blocks[it] != AIR }
        return MineWorkingPlan(MineIncidentType.TUNNEL_DRIVE, placement, blocks, blocks.keys,
            blocks.keys - walkable, walkable, excavation, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyMap(), emptySet(), placement.entrance)
    }
}

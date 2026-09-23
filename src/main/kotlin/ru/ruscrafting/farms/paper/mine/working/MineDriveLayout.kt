package ru.ruscrafting.farms.paper.mine.working

import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MineWorkingPlacement
import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise
import ru.ruscrafting.farms.domain.worksite.WorksitePosition

/** Solid, journalled drilling ground. Version 8 uses finite central masses with two bypasses. */
internal object MineDriveLayout {
    const val HALF_WIDTH = 16
    const val LENGTH = 44
    /** Rail extension owns a longer straight run so maintenance events have room to breathe. */
    const val RAIL_LENGTH = 50
    const val STRIDE = HALF_WIDTH * 2 + 1
    const val MAX_CELLS = STRIDE * (LENGTH + 1)
    const val MAX_RAIL_CELLS = STRIDE * (RAIL_LENGTH + 1)
    const val CONTRIBUTION_BUDGET = 93 // Previous drive: 90 face blocks + 3 supports.
    private const val AIR = "minecraft:air"
    private const val BEDROCK = "minecraft:bedrock"
    private const val CENTRAL_MASS_GEOMETRY_VERSION = 8

    fun enabled(placement: MineWorkingPlacement) = placement.geometryVersion >= 5
    fun rail(type: MineIncidentType, placement: MineWorkingPlacement) = type == MineIncidentType.RAIL_EXTENSION && placement.geometryVersion >= 9
    fun extendedRail(type: MineIncidentType, placement: MineWorkingPlacement) = rail(type, placement) && placement.geometryVersion >= 10
    fun length(rail: Boolean, version: Int = MineWorkingPlacement.CURRENT_GEOMETRY_VERSION) =
        if (rail && version >= 10) RAIL_LENGTH else LENGTH
    fun length(type: MineIncidentType, placement: MineWorkingPlacement) = length(rail(type, placement), placement.geometryVersion)
    fun machine(type: MineIncidentType, placement: MineWorkingPlacement) =
        (type == MineIncidentType.TUNNEL_DRIVE && enabled(placement)) || rail(type, placement)
    fun width(version: Int) = if(version>=7) HALF_WIDTH else 8
    private fun stride(version: Int) = width(version)*2+1
    fun id(side: Int, forward: Int, version: Int = MineWorkingPlacement.CURRENT_GEOMETRY_VERSION) = forward * stride(version) + side + width(version)
    fun side(id: Int, version: Int = MineWorkingPlacement.CURRENT_GEOMETRY_VERSION) = id % stride(version) - width(version)
    fun forward(id: Int, version: Int = MineWorkingPlacement.CURRENT_GEOMETRY_VERSION) = id / stride(version)
    fun position(placement: MineWorkingPlacement, id: Int, up: Int = 1) = placement.position(side(id,placement.geometryVersion), up, forward(id,placement.geometryVersion))
    /** Geometry 7 and earlier are persisted layouts; their one-sided ribs stay byte-for-byte addressable. */
    private fun legacyBedrock(side: Int, forward: Int): Boolean =
        (forward in 12..14 && side <= 1) || (forward in 26..28 && side >= -1)

    /** Geometry 8 keeps both bypasses usable by constraining each obstacle to a central island. */
    private fun currentBedrock(side: Int, forward: Int): Boolean =
        (forward in 12..14 && side in -2..2) || (forward in 26..28 && side in -2..2)

    fun bedrock(side: Int, forward: Int, version: Int = MineWorkingPlacement.CURRENT_GEOMETRY_VERSION): Boolean =
        if (version >= CENTRAL_MASS_GEOMETRY_VERSION) currentBedrock(side, forward)
        else legacyBedrock(side, forward)
    /** The rail machine gets a wider entry and cutter pocket for sleeper service clearances. */
    fun radius(forward: Int, version: Int = MineWorkingPlacement.CURRENT_GEOMETRY_VERSION, rail: Boolean = false) = when {
        rail && version >= 10 && forward <= 1 -> 2
        rail && version >= 10 && forward == 2 -> 4
        forward <= 1 -> 1
        forward == 2 -> 3
        else -> width(version) - 1
    }
    fun insideBoundary(side: Int, forward: Int, version: Int = MineWorkingPlacement.CURRENT_GEOMETRY_VERSION, rail: Boolean = false) =
        forward in 0 until length(rail,version) && kotlin.math.abs(side) <= radius(forward,version,rail)
    fun driveable(side: Int, forward: Int, version: Int = MineWorkingPlacement.CURRENT_GEOMETRY_VERSION, rail: Boolean = false) =
        forward in 1 until length(rail,version) && kotlin.math.abs(side) <= radius(forward,version,rail) && (rail || !bedrock(side, forward, version))
    /** Broad arrival area inside the discovery cavern, including approaches around either bedrock rib. */
    fun reached(side: Double, forward: Double, version: Int, rail: Boolean = false): Boolean {
        val center = length(rail,version) - 5.0
        return forward >= center - 4.0 &&
            (side*side/(if(version>=7) 100.0 else 30.25)+(forward-center)*(forward-center)/25.0)<=1.0
    }
    fun returnPoint(placement: MineWorkingPlacement)=placement.position(0,1,41)
    fun local(placement: MineWorkingPlacement, x: Double, z: Double): Pair<Double, Double> {
        val dx = x - placement.entrance.x - .5
        val dz = z - placement.entrance.z - .5
        return when (placement.direction) { 0 -> dx to dz; 1 -> dz to -dx; 2 -> -dx to -dz; else -> -dz to dx }
    }

    fun goalOres(plan: MineWorkingPlan): List<WorksitePosition> = plan.blocks.filterValues {
        it == "minecraft:deepslate_diamond_ore"
    }.keys.filter { p -> listOf(p.copy(x=p.x-1),p.copy(x=p.x+1),p.copy(y=p.y-1),p.copy(y=p.y+1),p.copy(z=p.z-1),p.copy(z=p.z+1))
        .any { plan.blocks[it] == AIR } }.take(32)

    private fun diamondChamber(placement: MineWorkingPlacement, blocks: MutableMap<WorksitePosition,String>, walkable: MutableSet<WorksitePosition>, rail: Boolean) {
        val air = linkedSetOf<WorksitePosition>()
        val halfWidth=width(placement.geometryVersion)
        val routeLength=length(rail, placement.geometryVersion)
        val chamberCenter=routeLength-5
        val extended = rail && placement.geometryVersion >= 10
        val maxUp=if (extended) 8 else 9
        for (f in 34..routeLength) for (s in -halfWidth..halfWidth) for (up in 0..maxUp) {
            val p = placement.position(s,up,f)
            val n = WorksiteCoherentNoise.sample(placement.layoutSeed,s*.29,up*.35,f*.27)
            val shape = s*s/(if(placement.geometryVersion>=7) 128.0 else 32.0) + (f-chamberCenter)*(f-chamberCenter)/20.0 + (up-3.5)*(up-3.5)/19.0
            val hollow = f < routeLength && kotlin.math.abs(s) < halfWidth && up>=1 &&
                (shape < 1.0+n*.65 || (kotlin.math.abs(s)<=2 && f in chamberCenter-2..chamberCenter+1 && up in 1..4))
            blocks[p] = if(hollow) AIR else when { n>.24 -> "minecraft:tuff"; n<-.27 -> "minecraft:calcite"; else -> "minecraft:deepslate" }
            if(hollow) { air+=p;walkable+=p } else walkable.remove(p)
        }
        // Coherent veins on the cave shell. Ordinary mining remains protected: these are the discovery, not a new loot source.
        for(f in 35..routeLength) for(s in -halfWidth..halfWidth) for(up in 0 until maxUp) {
            val p=placement.position(s,up,f)
            if(p in air) continue
            val exposed=listOf(p.copy(x=p.x-1),p.copy(x=p.x+1),p.copy(y=p.y-1),p.copy(y=p.y+1),p.copy(z=p.z-1),p.copy(z=p.z+1)).any { it in air }
            if(exposed && WorksiteCoherentNoise.sample(placement.layoutSeed,s*.53,up*.49,f*.43) > -.06)
                blocks[p]="minecraft:deepslate_diamond_ore"
        }
        // A subdued ambient source exposes the chamber silhouette without a forest of lamps.
        val lights=if(placement.geometryVersion>=7)
            listOf(-7 to chamberCenter-1,-3 to chamberCenter+1,3 to chamberCenter+1,7 to chamberCenter-1,0 to chamberCenter-3)
        else listOf(-3 to 40,3 to 41)
        lights.map { (s,f)->placement.position(s,3,f) }.filter { it in air }.forEach {
            blocks[it]=if(placement.geometryVersion>=7) "minecraft:light[level=13,waterlogged=false]" else "minecraft:light[level=10,waterlogged=false]"
        }
    }

    fun plan(placement: MineWorkingPlacement, type: MineIncidentType = MineIncidentType.TUNNEL_DRIVE): MineWorkingPlan {
        val rail = rail(type, placement)
        val routeLength=length(rail, placement.geometryVersion)
        val blocks = linkedMapOf<WorksitePosition, String>()
        val walkable = linkedSetOf<WorksitePosition>()
        val halfWidth=width(placement.geometryVersion)
        // Keep the rail shell under the existing 12k preparation cap while
        // the chamber itself still reaches the validated machine height.
        val extended = rail && placement.geometryVersion >= 10
        val maxUp=if (extended) 5 else 6
        for (f in 0..routeLength) {
            val width = when {
                extended && f <= 1 -> 3
                extended && f == 2 -> 5
                f <= 1 -> 2
                f == 2 -> 4
                else -> halfWidth
            }
            for (s in -width..width) for (up in 0..maxUp) {
                val p = placement.position(s, up, f)
                val interior = kotlin.math.abs(s) < width && f < routeLength && up in 1..(if (f <= 1) 3 else 4)
                val opening = interior && (f <= 3 || f >= routeLength - 4)
                val noise = WorksiteCoherentNoise.sample(placement.layoutSeed, s * .3, up * .3, f * .24)
                blocks[p] = when {
                    interior && !rail && bedrock(s, f, placement.geometryVersion) -> BEDROCK
                    opening -> AIR
                    noise > .24 -> "minecraft:tuff"
                    noise < -.27 -> "minecraft:andesite"
                    else -> "minecraft:deepslate"
                }
                if (interior) walkable += p
            }
        }
        if (placement.geometryVersion >= 6) diamondChamber(placement, blocks, walkable, rail)
        val excavation = (4 until routeLength - 4).map { placement.position(0, 1, it) }.filter { blocks[it] != AIR }
        return MineWorkingPlan(type, placement, blocks, blocks.keys,
            blocks.keys - walkable, walkable, excavation, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyMap(), emptySet(), placement.entrance)
    }
}

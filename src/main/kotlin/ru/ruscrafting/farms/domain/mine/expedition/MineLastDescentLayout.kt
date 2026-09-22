package ru.ruscrafting.farms.domain.mine.expedition

import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** Geometry v4: one continuous abyss, with three small structures attached to its walls. */
internal object MineLastDescentLayout {
    const val ENTRY_Y = 57
    private const val AIR = MineExpeditionBuilder.AIR
    private const val STONE = "minecraft:deepslate"
    private const val BEAM = "minecraft:polished_basalt[axis=y]"
    private const val COPPER = "minecraft:weathered_cut_copper"
    private const val STEEL = "minecraft:polished_andesite"
    private val stops = listOf(ENTRY_Y, 33, 9)

    fun build(seed: Long): MineExpeditionPlan {
        val b = MineExpeditionBuilder(MineExpeditionKind.LAST_DESCENT, seed,
            ExpeditionBounds(ExpeditionPoint(-33, 0, -29), ExpeditionPoint(33, 68, 32)))
        geology(b)
        water(b)
        // The approach reveals the whole void before the player steps onto the lift.
        pad(b, -6..6, 57, 3..27)
        pad(b, -23..-6, 57, 11..23)
        pad(b, -26..-3, 33, 3..23)
        pad(b, -6..4, 33, 3..12)
        pad(b, -21..21, 9, 5..28)
        pad(b, -4..4, 9, 3..5)

        b.station("entry", ExpeditionPoint(0, ENTRY_Y, 25))
        b.station("exit", ExpeditionPoint(0, ENTRY_Y, 25))
        listOf("lift_top", "lift_middle", "lift_bottom").zip(stops).forEach { (name, y) ->
            // Lift stations describe a moving deck, never a permanent floor across the shaft.
            b.stations[name] = ExpeditionPoint(0, y, 0)
        }
        val controls = linkedMapOf(
            "counterweight_0" to ExpeditionPoint(-13, 33, 7),
            "counterweight_1" to ExpeditionPoint(-20, 33, 8),
            "counterweight_2" to ExpeditionPoint(-13, 33, 14),
            "power_supply" to ExpeditionPoint(-20, 33, 17),
            "power_socket" to ExpeditionPoint(-7, 33, 14),
            "core_valve_0" to ExpeditionPoint(-14, 9, 14),
            "core_valve_1" to ExpeditionPoint(12, 9, 13),
            "core_valve_2" to ExpeditionPoint(5, 9, 17),
        )
        controls.forEach { (name, p) -> b.station(name, p) }
        b.stations["core_start"] = controls.getValue("core_valve_2")
        b.stations["descent_pump"] = ExpeditionPoint(0, 9, 23)
        b.route("main", listOf(b.spawnPoint(), ExpeditionPoint(0, 57, 4)))
        b.walk(listOf(ExpeditionPoint(0, 57, 15), ExpeditionPoint(-13, 57, 15)))
        controls.values.forEach { p ->
            // Landings join the front of the lift; every service loop remains three blocks wide.
            b.walk(listOf(ExpeditionPoint(0, p.y, 4), ExpeditionPoint(0, p.y, 10),
                ExpeditionPoint(p.x, p.y, 10), p))
        }
        b.route("lift", stops.map { ExpeditionPoint(0, it, 0) }, false)

        // Restore the finished deck palette after generic route/station reservation.
        finishDeck(b, -6..6, 57, 3..27)
        finishDeck(b, -23..-6, 57, 11..23)
        finishDeck(b, -26..-3, 33, 3..23)
        finishDeck(b, -6..4, 33, 3..12)
        finishDeck(b, -21..21, 9, 5..28)
        finishDeck(b, -4..4, 9, 3..5)
        structure(b)
        pipework(b)
        lighting(b)
        // Runtime projects the complete seven-by-five lift, floor through roof; keep all stops clear.
        for (x in -3..3) for (z in -2..2) for (y in 8..60) b.put(ExpeditionPoint(x,y,z), AIR)
        return b.finish()
    }

    private fun MineExpeditionBuilder.spawnPoint() = stations.getValue("entry")

    private fun geology(b: MineExpeditionBuilder) {
        for (x in -33..33) for (z in -29..32) for (y in 0..68) {
            val large = WorksiteCoherentNoise.sample(b.seed + 901, x * .07, y * .055, z * .07)
            val fine = WorksiteCoherentNoise.sample(b.seed + 907, x * .25, y * .2, z * .25)
            val bend = sin(y * .075) * 3.0
            val rx = 24.0 + large * 4.5 + sin(y * .14) * 2.5
            val rz = 21.0 + large * 3.5
            val shape = ((x - bend) / rx).pow(2) + ((z + 1.0) / rz).pow(2)
            val floor = 4.8 + WorksiteCoherentNoise.sample(b.seed + 919, x * .10, 0.0, z * .10) * 3.1
            val roof = 62.5 + large * 3.0 + fine
            val boundary = x in setOf(-33,33) || z in setOf(-29,32) || y in setOf(0,68)
            val open = !boundary && y > floor && y < roof && shape < 1.0 + fine * .10
            val material = when {
                y < 3 -> STONE
                large > .24 -> "minecraft:tuff"
                large < -.28 -> "minecraft:stone"
                (y + large * 8).toInt().mod(19) in 0..2 -> "minecraft:calcite"
                fine > .48 -> "minecraft:deepslate_copper_ore"
                y > 39 -> "minecraft:andesite"
                else -> STONE
            }
            b.put(ExpeditionPoint(x,y,z), if(open) AIR else material)
        }
        // A few volumetric hanging rock teeth; no repeated ceiling grid.
        listOf(Triple(-14,-7,8), Triple(11,-10,11), Triple(20,8,6), Triple(-6,15,5)).forEach { (cx,cz,len) ->
            for (y in 63-len..65) {
                val radius = ((y - (63-len)) / 3.5).coerceAtLeast(.4)
                for (x in cx-4..cx+4) for (z in cz-4..cz+4) {
                    if ((x-cx)*(x-cx)+(z-cz)*(z-cz) <= radius*radius) b.put(ExpeditionPoint(x,y,z), STONE)
                }
            }
        }
    }

    private fun water(b: MineExpeditionBuilder) {
        // A bounded basin occupies the unwalked far side of the chasm. Its banks and bottom are solid.
        val pool = linkedSetOf<ExpeditionPoint>()
        for (x in -22..20) for (z in -23..-6) {
            val n = WorksiteCoherentNoise.sample(b.seed + 933, x*.18, 0.0,z*.18)
            if ((x/22.0).pow(2) + ((z+14)/9.0).pow(2) < .91 + n*.1) pool += ExpeditionPoint(x,5,z)
        }
        pool.forEach { p ->
            b.put(p.offset(dy=-1), "minecraft:polished_deepslate")
            b.put(p, "minecraft:water[level=0]")
            listOf(p.offset(dx=1),p.offset(dx=-1),p.offset(dz=1),p.offset(dz=-1)).filter { it !in pool }
                .forEach { b.put(it, "minecraft:mossy_cobblestone") }
        }
        // One waterfall is seated in a rock niche; it drains into the enclosed lake.
        val x = -16; val z = -13
        for(y in 6..46) {
            b.put(ExpeditionPoint(x,y,z-1), STONE)
            b.put(ExpeditionPoint(x-1,y,z), STONE)
            b.put(ExpeditionPoint(x+1,y,z), STONE)
            b.put(ExpeditionPoint(x,y,z), "minecraft:water[level=8]")
        }
        for(xx in x-2..x+2) for(zz in z-2..z+1) for(y in 46..49)
            if(xx!=x || zz!=z || y>=48) b.put(ExpeditionPoint(xx,y,zz), STONE)
        b.put(ExpeditionPoint(x,47,z), "minecraft:water[level=0]")
    }

    private fun pad(b: MineExpeditionBuilder, xs: IntRange, y: Int, zs: IntRange) {
        for(x in xs) for(z in zs) {
            for(dy in 0..if(y==9) 13 else 7) b.put(ExpeditionPoint(x,y+dy,z), AIR)
            b.put(ExpeditionPoint(x,y-1,z), STEEL)
        }
    }

    private fun finishDeck(b: MineExpeditionBuilder, xs: IntRange, y: Int, zs: IntRange) {
        for(x in xs) for(z in zs) b.put(ExpeditionPoint(x,y-1,z), when {
            x==xs.first || x==xs.last || z==zs.first || z==zs.last -> STEEL
            y==9 -> if(z in 18..28) "minecraft:polished_deepslate" else "minecraft:tuff_bricks"
            x.mod(6)==0 -> "minecraft:oxidized_cut_copper"
            else -> "minecraft:spruce_planks"
        })
    }

    private fun structure(b: MineExpeditionBuilder) {
        // Twin rails and a headframe make the lift's travel legible over the whole 48-block drop.
        for(x in listOf(-5,5)) for(y in 6..63) {
            b.put(ExpeditionPoint(x,y,-3), BEAM)
            if(y.mod(6)==0) {
                b.put(ExpeditionPoint(x,y,-4), COPPER)
                for(z in -12..-5) b.put(ExpeditionPoint(x,y,z), "minecraft:polished_basalt[axis=z]")
            }
        }
        for(x in -6..6) for(z in -3..-2) b.put(ExpeditionPoint(x,63,z), COPPER)
        // Suspension rods terminate on the structures they actually support.
        listOf(Triple(-22,57,12),Triple(-22,57,22),Triple(-25,33,4),Triple(-25,33,22),Triple(-7,33,22),
            Triple(-6,57,4),Triple(6,57,4)).forEach { (x,y,z) ->
            for(yy in y-3..y-1) b.put(ExpeditionPoint(x,yy,z), COPPER)
            for(yy in y..64) b.put(ExpeditionPoint(x,yy,z), "minecraft:iron_chain[axis=y]")
            b.put(ExpeditionPoint(x,65,z), BEAM)
        }
        // Crossmembers continue into rock instead of stopping in mid-air.
        listOf(57 to listOf(11,23),33 to listOf(3,23),9 to listOf(6,27)).forEach { (y,zs) ->
            for(z in zs) for(x in -29..if(y==9) 27 else 6)
                b.put(ExpeditionPoint(x,y-2,z), "minecraft:polished_basalt[axis=x]")
        }
        // Low guardrails are derived from final deck neighbours; the three docking mouths stay open.
        for(y in stops) {
            val deck=b.blocks.filter { (p,m) -> p.y==y-1 && m in setOf(STEEL,"minecraft:spruce_planks",
                "minecraft:oxidized_cut_copper","minecraft:tuff_bricks","minecraft:polished_deepslate") }.keys
                .filter { it.x in -26..21 && it.z in 3..28 }.toSet()
            deck.forEach { floor ->
                val p=floor.offset(dy=1)
                val mouth=p.x in -3..3 && p.z==3
                val outer=listOf(floor.offset(dx=1),floor.offset(dx=-1),floor.offset(dz=1),floor.offset(dz=-1)).any { it !in deck }
                if(outer && !mouth && b.blocks[p]==AIR) {
                    b.put(p, "minecraft:spruce_fence")
                }
            }
        }
        resolveFences(b)
        // Winding-house silhouette and sheltered arrival, with a clear central doorway.
        for(x in listOf(-4,4)) for(y in 57..61) b.solid(ExpeditionPoint(x,y,24), BEAM)
        for(x in -4..4) b.solid(ExpeditionPoint(x,62,24), COPPER)
        for(x in -24..-8) for(z in 12..22) b.solid(ExpeditionPoint(x,64,z), "minecraft:dark_oak_slab[type=bottom]")
        for(x in listOf(-23,-9)) for(z in listOf(12,22)) for(y in 57..63)
            b.solid(ExpeditionPoint(x,y,z), "minecraft:stripped_dark_oak_log[axis=y]")
    }

    private fun resolveFences(b: MineExpeditionBuilder) {
        val fences=b.blocks.filterValues { it=="minecraft:spruce_fence" }.keys
        fences.forEach { p ->
            fun connects(dx:Int,dz:Int)=b.blocks[p.offset(dx=dx,dz=dz)]?.let { it.startsWith("minecraft:spruce_fence") ||
                it==BEAM || it==COPPER }==true
            b.put(p,"minecraft:spruce_fence[north=${connects(0,-1)},south=${connects(0,1)},west=${connects(-1,0)},east=${connects(1,0)},waterlogged=false]")
        }
    }

    private fun pipework(b: MineExpeditionBuilder) {
        // Lake intake reaches the lower pump; the outlet rises up the back wall to the working mine.
        for(z in -17..25) b.solid(ExpeditionPoint(-19,10,z), COPPER)
        for(x in -19..-7) b.solid(ExpeditionPoint(x,10,25), COPPER)
        for(y in 10..58) b.solid(ExpeditionPoint(19,y,25), COPPER)
        for(x in 7..19) b.solid(ExpeditionPoint(x,11,25), COPPER)
        for(y in 12..57 step 8) {
            b.solid(ExpeditionPoint(19,y,25), "minecraft:chiseled_copper")
            for(z in 26..30) b.solid(ExpeditionPoint(19,y,z), "minecraft:polished_basalt[axis=z]")
        }
        // Pipe at floor level branches to the inlet control without crossing the service aisle.
        for(z in 15..25) b.solid(ExpeditionPoint(-14,9,z), COPPER)
        for(x in -18..-14) b.solid(ExpeditionPoint(x,9,25), COPPER)
    }

    private fun lighting(b: MineExpeditionBuilder) {
        val lamps=listOf(ExpeditionPoint(-4,57,20),ExpeditionPoint(4,57,8),
            ExpeditionPoint(-24,33,12),ExpeditionPoint(-9,33,21),ExpeditionPoint(3,33,6),
            ExpeditionPoint(-19,9,7),ExpeditionPoint(19,9,7),ExpeditionPoint(-18,9,26),ExpeditionPoint(18,9,26))
        lamps.forEach { p ->
            b.put(p, STEEL)
            b.put(p.offset(dy=1), "minecraft:iron_bars[north=false,south=false,east=false,west=false]")
            b.put(p.offset(dy=2), "minecraft:lantern[hanging=false,waterlogged=false]")
        }
        // Recessed lift guide lamps give depth cues without filling the abyss with glowing cubes.
        for(y in 10..58 step 8) for(x in listOf(-5,5)) {
            b.put(ExpeditionPoint(x,y, -2), "minecraft:ochre_froglight[axis=z]")
            b.put(ExpeditionPoint(x,y+1,-2), "minecraft:deepslate_tile_slab[type=bottom]")
        }
        listOf(ExpeditionPoint(-14,8,-13),ExpeditionPoint(12,8,-13),ExpeditionPoint(-18,43,-11)).forEach {
            b.put(it,"minecraft:light[level=13]")
        }
        // The winding-house roof blocks borrowed light: light each actual repair surface locally.
        for(p in listOf(ExpeditionPoint(-13,35,7),ExpeditionPoint(-20,35,8),
            ExpeditionPoint(-13,35,14),ExpeditionPoint(-7,35,14))) b.put(p,"minecraft:light[level=14]")
    }
}

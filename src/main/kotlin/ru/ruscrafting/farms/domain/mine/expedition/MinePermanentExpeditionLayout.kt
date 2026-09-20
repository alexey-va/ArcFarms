package ru.ruscrafting.farms.domain.mine.expedition

import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise

/** Self-contained nearby caverns: the surrounding world may be air, never assumed to be stone. */
internal object MinePermanentExpeditionLayout {
    fun build(kind: MineExpeditionKind, seed: Long): MineExpeditionPlan = when (kind) {
        MineExpeditionKind.LAST_DESCENT -> descent(seed)
        MineExpeditionKind.DRILLING_ARK -> ark(seed)
        MineExpeditionKind.DEAD_FACTORY -> factory(seed)
    }

    private fun cavern(kind: MineExpeditionKind, seed: Long, x: Int, y: Int, z: Int): MineExpeditionBuilder {
        val b = MineExpeditionBuilder(kind, seed, ExpeditionBounds(ExpeditionPoint(-x, 0, -z), ExpeditionPoint(x, y, z)))
        for (xx in -x..x) for (zz in -z..z) {
            val noise = WorksiteCoherentNoise.sample(seed, xx * .17, 0.0, zz * .17)
            for (yy in 0..y) b.put(ExpeditionPoint(xx, yy, zz), when {
                yy < 3 -> "minecraft:deepslate"
                noise > .35 -> "minecraft:tuff"
                noise < -.32 -> "minecraft:andesite"
                (xx * 31 + yy * 17 + zz * 13).mod(83) == 0 -> "minecraft:iron_ore"
                else -> "minecraft:stone"
            })
        }
        return b
    }

    private fun descent(seed: Long): MineExpeditionPlan {
        val b = cavern(MineExpeditionKind.LAST_DESCENT, seed, 30, 35, 31)
        b.shaft(0, 0, 3, 27, 9, 9, 77L)
        listOf(5, 15, 25).forEach { y -> b.chamber(ExpeditionPoint(0, y, 3), 26, 4, 26, y) }
        b.station("entry", ExpeditionPoint(0, 25, 17))
        b.station("exit", ExpeditionPoint(0, 5, 17))
        listOf("lift_top" to 25, "lift_middle" to 15, "lift_bottom" to 5).forEach { (id, y) ->
            b.station(id, ExpeditionPoint(0, y, 0))
            b.walk(listOf(ExpeditionPoint(0, y, 0), ExpeditionPoint(0, y, 17)))
        }
        listOf(-5, 0, 5).forEachIndexed { i, z -> b.station("counterweight_$i", ExpeditionPoint(-12, 15, z)) }
        b.station("power_supply", ExpeditionPoint(12, 15, -5)); b.station("power_socket", ExpeditionPoint(12, 15, 5))
        listOf(-5, 0, 5).forEachIndexed { i, z -> b.station("core_valve_$i", ExpeditionPoint(-12, 5, z)) }
        b.station("core_start", ExpeditionPoint(0, 5, -12))
        b.stations.filterKeys { it !in setOf("entry", "exit") && !it.startsWith("lift_") }.values.toList().forEach {
            b.walk(listOf(ExpeditionPoint(0, it.y, 5), it))
        }
        b.route("lift", listOf(b.stations.getValue("lift_top"), b.stations.getValue("lift_middle"), b.stations.getValue("lift_bottom")), false)
        ring(b, ExpeditionPoint(0, 11, -13), 4, "minecraft:exposed_cut_copper", true)
        for (x in -3..3) b.solid(ExpeditionPoint(x, 11, -13), "minecraft:polished_andesite")
        b.solid(ExpeditionPoint(0, 11, -12), "minecraft:sea_lantern")
        for (x in listOf(-7, 7)) for (y in 5..13) b.solid(ExpeditionPoint(x, y, -13), "minecraft:deepslate_bricks")
        decorate(b)
        // Open front and uninterrupted shaft for the complete moving platform, including its floor.
        for (x in -3..3) for (z in -2..2) for (y in 4..27) b.put(ExpeditionPoint(x, y, z), MineExpeditionBuilder.AIR)
        return finish(b)
    }

    private fun ark(seed: Long): MineExpeditionPlan {
        val b = cavern(MineExpeditionKind.DRILLING_ARK, seed, 34, 24, 40)
        val start = ExpeditionPoint(0, 5, -13); val mid = ExpeditionPoint(0, 5, 0); val end = ExpeditionPoint(0, 5, 13)
        listOf(start, mid, end).forEach { b.chamber(it.offset(dy = 3), 30, 10, 17, 5) }
        b.station("entry", ExpeditionPoint(0, 5, -22)); b.station("exit", ExpeditionPoint(0, 5, 22))
        b.station("ark_start", start); b.station("ark_mid", mid); b.station("ark_end", end)
        b.station("fuel_supply", ExpeditionPoint(-16, 5, -13)); b.station("coolant_supply", ExpeditionPoint(16, 5, -13))
        listOf(ExpeditionPoint(-12, 5, -5), ExpeditionPoint(12, 5, -5), ExpeditionPoint(-12, 5, 9)).forEachIndexed { i, p -> b.station("jam_$i", p) }
        listOf(ExpeditionPoint(-12, 5, -9), ExpeditionPoint(12, 5, 9), ExpeditionPoint(-12, 5, 13)).forEachIndexed { i, p -> b.station("survey_$i", p) }
        b.station("branch_left", ExpeditionPoint(-12, 5, -7)); b.station("branch_right", ExpeditionPoint(12, 5, -7))
        val routes = listOf(-10 to "ark_left", 10 to "ark_right").map { (x, id) ->
            val route = listOf(start, ExpeditionPoint(x, 5, -9), ExpeditionPoint(x, 5, -4), mid,
                ExpeditionPoint(x, 5, 4), ExpeditionPoint(x, 5, 9), end)
            b.route(id, route); route
        }
        b.stations.values.toList().forEach { b.walk(listOf(ExpeditionPoint(0, 5, it.z), it)) }
        (routes + listOf(listOf(start, mid, end))).forEach { route -> route.zipWithNext().forEach { (from, to) ->
            b.line(from, to).forEach { center -> for (dx in -5..5) for (dz in -8..8) {
                for (dy in 0..8) b.reserveAir(center.offset(dx, dy, dz))
                for (dy in -3..-1) b.put(center.offset(dx, dy, dz), "minecraft:deepslate")
            } }
        } }
        for (z in listOf(-16, 0, 16)) {
            for (x in listOf(-20,20)) {
                for (y in 5..14) b.solid(ExpeditionPoint(x,y,z), "minecraft:stripped_spruce_log[axis=y]")
                b.light(ExpeditionPoint(x - x.compareTo(0),13,z))
            }
            for (x in -20..20) b.solid(ExpeditionPoint(x,14,z), "minecraft:stripped_spruce_log[axis=x]")
        }
        decorate(b)
        return finish(b)
    }

    private fun factory(seed: Long): MineExpeditionPlan {
        val b = cavern(MineExpeditionKind.DEAD_FACTORY, seed, 36, 28, 33)
        b.chamber(ExpeditionPoint(0,13,0),34,13,31,5)
        // Reserve the complete production hall before drawing its final floor after the routes.
        for(x in -33..33) for(z in -28..27) for(y in 5..20)
            b.put(ExpeditionPoint(x,y,z),MineExpeditionBuilder.AIR)
        b.station("entry",ExpeditionPoint(-5,5,24));b.station("exit",ExpeditionPoint(5,5,24))
        listOf(ExpeditionPoint(-27,5,-6),ExpeditionPoint(0,5,-18),ExpeditionPoint(27,5,-6)).forEachIndexed { i,p -> b.station("water_valve_$i",p) }
        mapOf("fuel_supply" to ExpeditionPoint(-25,5,7),"furnace_input" to ExpeditionPoint(-17,5,-6),
            "furnace_control" to ExpeditionPoint(-11,5,-12),"pour_control" to ExpeditionPoint(17,5,-6),
            "crane_control" to ExpeditionPoint(-8,5,8),"crane_load" to ExpeditionPoint(8,5,-6),"assembly_socket" to ExpeditionPoint(8,5,11)).forEach(b::station)
        b.route("main",listOf(b.stations.getValue("entry"),ExpeditionPoint(0,5,21),ExpeditionPoint(0,5,-16)))
        // Keep the axial floor palette intact; route reservation does not scatter new paving across bays.
        b.stations.values.toList().forEach { b.walk(listOf(ExpeditionPoint(0,5,it.z),it)) }
        // Matching technical pads include the full machine envelopes and their service fittings.
        // Light stone denotes the clear axial/cross aisles and the continuous perimeter of each pad.
        for(x in -33..33) for(z in -28..27) b.put(ExpeditionPoint(x,4,z),"minecraft:polished_andesite")
        for(side in listOf(-1,1)) for(zRange in listOf(-22..-3,3..22)) {
            for(xx in 5..33) for(z in zRange) {
                val border=xx==5 || xx==33 || z==zRange.first || z==zRange.last
                b.put(ExpeditionPoint(side*xx,4,z),if(border) "minecraft:polished_andesite" else "minecraft:polished_deepslate")
            }
        }
        for(z in -24..24 step 12) {
            for(x in listOf(-29,29)) {
                for(y in 5..20) b.solid(ExpeditionPoint(x,y,z),if(y%5==0) "minecraft:chiseled_tuff_bricks" else "minecraft:polished_basalt[axis=y]")
                for(dx in -1..1) for(dz in -1..1) b.solid(ExpeditionPoint(x+dx,5,z+dz),"minecraft:polished_blackstone_bricks")
            }
            for(x in -29..29) {
                b.solid(ExpeditionPoint(x,20,z),"minecraft:cut_copper")
                if(x%5==0) b.solid(ExpeditionPoint(x,19,z),"minecraft:iron_bars[north=true,south=true]")
            }
            // Pendant lamps hang from the bars beneath each roof truss in matching rows.
            for(x in -20..20 step 10) {
                b.solid(ExpeditionPoint(x,18,z),"minecraft:iron_chain[axis=y]")
                b.solid(ExpeditionPoint(x,17,z),"minecraft:lantern[hanging=true,waterlogged=false]")
            }
        }
        // Matching rear headers feed the two furnace towers. Water drive sits on the centreline.
        for(x in -25..25) b.solid(ExpeditionPoint(x,17,-23),"minecraft:exposed_cut_copper")
        for(x in listOf(-17,17)) for(z in -23..-12) b.solid(ExpeditionPoint(x,17,z),"minecraft:weathered_cut_copper")
        for(x in -7..7) for(z in -28..-15) b.put(ExpeditionPoint(x,4,z),
            if(x in listOf(-7,7)||z in listOf(-28,-15)) "minecraft:polished_andesite" else "minecraft:polished_deepslate")
        for(x in -6..6) b.put(ExpeditionPoint(x,5,-25),"minecraft:blue_stained_glass")
        // Twin gantry runways with a full-width crossmember above the clear aisle.
        for(x in listOf(-7,7)) {
            for(z in -20..20) b.solid(ExpeditionPoint(x,18,z),"minecraft:polished_andesite")
            for(z in listOf(-20,20)) for(y in 5..17) b.solid(ExpeditionPoint(x,y,z),"minecraft:cut_copper")
        }
        for(x in -25..25) b.solid(ExpeditionPoint(x,17,-6),"minecraft:polished_andesite")
        // Two matching glazed control cabins, outside the production bays and entry doors.
        for(side in listOf(-1,1)) for(xx in 21..27) for(z in 21..25) for(y in 5..10) {
            val x=side*xx
            if(xx in listOf(21,27)||z in listOf(21,25)||y==10) {
                val door=xx==21 && z==23 && y in 5..7
                if(!door) b.solid(ExpeditionPoint(x,y,z),when {
                    y==10 -> "minecraft:cut_copper_slab[type=bottom]"
                    y in 7..8 && xx !in listOf(21,27) -> "minecraft:gray_stained_glass"
                    else -> "minecraft:tuff_bricks"
                })
            }
        }
        for(x in listOf(-24,24)) b.put(ExpeditionPoint(x,9,23),"minecraft:light[level=12]")
        // Deliberately sparse visible lamps; fill light supports work surfaces without a glowing ceiling grid.
        for(x in -28..28 step 4) for(z in -24..24 step 4) {
            val p=ExpeditionPoint(x,7,z)
            if(b.blocks[p]==MineExpeditionBuilder.AIR) b.put(p,"minecraft:light[level=14]")
        }
        return finish(b)
    }

    private fun finish(b: MineExpeditionBuilder): MineExpeditionPlan {
        val min = b.bounds.min; val max = b.bounds.max
        b.blocks.keys.toList().filter { p -> p.x == min.x || p.x == max.x || p.y == min.y || p.y == max.y || p.z == min.z || p.z == max.z }
            .forEach { p -> if (b.blocks[p] == MineExpeditionBuilder.AIR) b.put(p, "minecraft:stone") }
        return b.finish()
    }

    private fun decorate(b: MineExpeditionBuilder) {
        val min = b.bounds.min; val max = b.bounds.max
        for (x in min.x + 3..max.x - 3 step 7) for (z in min.z + 3..max.z - 3 step 7) {
            b.stalactite(ExpeditionPoint(x, max.y - 8, z), 2)
        }
        b.walkingRoutes.flatten().distinct().filterIndexed { i, _ -> i % 12 == 0 }.forEach { p ->
            val side = p.offset(dx = 3)
            if (b.bounds.contains(side.offset(dy = 4))) {
                for (y in 0..3) b.solid(side.offset(dy = y), "minecraft:stripped_spruce_log[axis=y]")
                b.light(side.offset(dy = 4))
            }
        }
    }
}

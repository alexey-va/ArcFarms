package ru.ruscrafting.farms.domain.mine.expedition

import ru.ruscrafting.farms.domain.worksite.WorksiteCoherentNoise

/** Self-contained nearby caverns: the surrounding world may be air, never assumed to be stone. */
internal object MineCompactExpeditionLayout {
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
        val b = cavern(MineExpeditionKind.LAST_DESCENT, seed, 21, 29, 22)
        b.shaft(0, 0, 3, 27, 9, 9, 77L)
        listOf(5, 15, 25).forEach { y -> b.chamber(ExpeditionPoint(0, y, 3), 18, 4, 18, y) }
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
        val b = cavern(MineExpeditionKind.DRILLING_ARK, seed, 24, 18, 25)
        val start = ExpeditionPoint(0, 5, -13); val mid = ExpeditionPoint(0, 5, 0); val end = ExpeditionPoint(0, 5, 13)
        listOf(start, mid, end).forEach { b.chamber(it.offset(dy = 3), 21, 8, 11, 5) }
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
        val b = cavern(MineExpeditionKind.DEAD_FACTORY, seed, 24, 21, 20)
        b.chamber(ExpeditionPoint(0, 11, 0), 22, 9, 18, 5)
        b.station("entry", ExpeditionPoint(-17, 5, 14)); b.station("exit", ExpeditionPoint(17, 5, 14))
        listOf(-12, 0, 12).forEachIndexed { i, x -> b.station("water_valve_$i", ExpeditionPoint(x, 5, 6)) }
        mapOf("fuel_supply" to ExpeditionPoint(9, 5, 11), "furnace_input" to ExpeditionPoint(14, 5, 6),
            "furnace_control" to ExpeditionPoint(18, 5, 0), "pour_control" to ExpeditionPoint(14, 5, -7),
            "crane_control" to ExpeditionPoint(0, 5, -6), "assembly_socket" to ExpeditionPoint(0, 5, -14)).forEach(b::station)
        b.route("main", listOf(b.stations.getValue("entry"), ExpeditionPoint(0, 5, 10), b.stations.getValue("exit")))
        b.stations.values.toList().forEach { b.walk(listOf(ExpeditionPoint(0, 5, 6), it)) }
        // Wheel, foundry and overhead travelling crane are fixed landmarks, with open working decks below.
        ring(b, ExpeditionPoint(-15, 11, 0), 5, "minecraft:oxidized_cut_copper", true)
        for (x in 11..16) for (z in -3..2) for (y in 6..12) b.solid(ExpeditionPoint(x, y, z),
            if (y == 6) "minecraft:blast_furnace" else "minecraft:deepslate_bricks")
        for (x in -18..18) b.solid(ExpeditionPoint(x, 17, -6), "minecraft:polished_andesite")
        for (x in listOf(-18,18)) for (y in 5..17) b.solid(ExpeditionPoint(x,y,-6), "minecraft:cut_copper")
        for (x in 7..15) b.solid(ExpeditionPoint(x,4,-6), "minecraft:orange_stained_glass")
        decorate(b)
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

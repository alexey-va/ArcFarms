package ru.ruscrafting.farms.domain.mine.expedition

import kotlin.math.max

/** Scene topology. Material-heavy construction lives in [MineExpeditionStructures]. */
internal object MineExpeditionLayout {
    fun build(kind: MineExpeditionKind, seed: Long): MineExpeditionPlan = when (kind) {
        MineExpeditionKind.LAST_DESCENT -> descent(seed)
        MineExpeditionKind.DRILLING_ARK -> drillingArk(seed)
        MineExpeditionKind.DEAD_FACTORY -> deadFactory(seed)
    }

    private fun descent(seed: Long): MineExpeditionPlan {
        val bounds = ExpeditionBounds(ExpeditionPoint(-34, 0, -34), ExpeditionPoint(34, 59, 34))
        val builder = MineExpeditionBuilder(MineExpeditionKind.LAST_DESCENT, seed, bounds)
        builder.shaft(centerX = 0, centerZ = 0, minY = 0, maxY = 59, radiusX = 11, radiusZ = 12, salt = 0xD35C3E7L)
        listOf(
            ExpeditionPoint(0, 53, 0) to 12,
            ExpeditionPoint(0, 31, 0) to 14,
            ExpeditionPoint(0, 9, 0) to 15,
            ExpeditionPoint(0, 17, -9) to 12,
            // The engine is a rear, elevated landmark.  Its chamber starts at
            // the bottom dock floor and rises high enough for the full wheel,
            // instead of hiding the machine below the lift-bottom deck.
            ExpeditionPoint(0, 17, -18) to 14,
        ).forEach { (center, radius) ->
            val radiusY = if (center.z < 0) 10 else max(4, radius / 3)
            val floorY = if (center.z < 0) 9 else center.y
            builder.chamber(center, radius, radiusY, radius + 1, floorY = floorY)
        }

        val liftTop = ExpeditionPoint(0, 53, 0)
        val liftMiddle = ExpeditionPoint(0, 31, 0)
        val liftBottom = ExpeditionPoint(0, 9, 0)
        builder.station("entry", ExpeditionPoint(0, 53, 20))
        builder.station("lift_top", liftTop)
        builder.station("lift_middle", liftMiddle)
        builder.station("lift_bottom", liftBottom)
        builder.station("exit", ExpeditionPoint(0, 9, 20))

        // All three counterweights are deliberately on the broad middle dock.
        listOf(-5, 0, 5).forEachIndexed { index, z -> builder.station("counterweight_$index", ExpeditionPoint(-14, 31, z)) }
        builder.station("power_supply", ExpeditionPoint(14, 31, -6))
        builder.station("power_socket", ExpeditionPoint(14, 31, 6))
        listOf(-7, 0, 7).forEachIndexed { index, z -> builder.station("core_valve_$index", ExpeditionPoint(-13, 9, z)) }
        builder.station("core_start", ExpeditionPoint(0, 9, -14))

        // The moving deck occupies this exact vertical sweep. Its front (+Z)
        // remains empty all the way into the chamber; no rail is authored here.
        for (x in -3..3) for (z in -2..2) {
            builder.support(ExpeditionPoint(x, 52, z), "minecraft:deepslate")
            builder.support(ExpeditionPoint(x, 30, z), "minecraft:deepslate")
            builder.support(ExpeditionPoint(x, 8, z), "minecraft:deepslate")
            // The moving blueprint owns its floor at feet-1 and its body up
            // through feet+2. Carve the complete vertical sweep, while the
            // three stop floors above remain explicit dock supports.
            for (y in 8..55) builder.reserveAir(ExpeditionPoint(x, y, z))
        }
        listOf(53, 31, 9).forEach { y ->
            for (x in -3..3) for (z in 0..17) for (dy in 0..4) {
                builder.reserveAir(ExpeditionPoint(x, y + dy, z))
            }
        }

        builder.route("lift", listOf(liftTop, liftMiddle, liftBottom), walking = false)
        builder.walk(listOf(builder.stations.getValue("entry"), liftTop))
        builder.walk(listOf(liftMiddle, builder.stations.getValue("power_supply")))
        builder.walk(listOf(builder.stations.getValue("power_supply"), builder.stations.getValue("power_socket")))
        builder.walk(listOf(liftMiddle) + (0..2).map { builder.stations.getValue("counterweight_$it") })
        builder.walk(listOf(liftBottom, builder.stations.getValue("core_start")))
        builder.walk(listOf(builder.stations.getValue("core_start"), builder.stations.getValue("core_valve_0")))
        builder.walk(listOf(builder.stations.getValue("core_valve_0"), builder.stations.getValue("core_valve_1"), builder.stations.getValue("core_valve_2")))
        builder.walk(listOf(liftBottom, builder.stations.getValue("exit")))

        MineExpeditionStructures.decorateDescent(builder)
        // Landings join the moving deck at its edge. Fixed floors inside its
        // sweep would trap passengers when crossing the middle stop.
        for (x in -3..3) for (z in -2..2) for (y in 8..55) {
            builder.put(ExpeditionPoint(x, y, z), MineExpeditionBuilder.AIR)
        }
        return builder.finish()
    }

    private fun drillingArk(seed: Long): MineExpeditionPlan {
        val bounds = ExpeditionBounds(ExpeditionPoint(-48, 0, -32), ExpeditionPoint(48, 29, 32))
        val builder = MineExpeditionBuilder(MineExpeditionKind.DRILLING_ARK, seed, bounds)
        // The moving crawler faces +Z; both authored branches keep increasing Z
        // and only shift X at a dock or rejoin.
        val start = ExpeditionPoint(0, 13, -22)
        val mid = ExpeditionPoint(0, 13, 0)
        val end = ExpeditionPoint(0, 13, 22)
        val left = listOf(start, ExpeditionPoint(-16, 13, -17), ExpeditionPoint(-16, 13, -8), mid,
            ExpeditionPoint(-16, 13, 8), ExpeditionPoint(-16, 13, 17), end)
        val right = listOf(start, ExpeditionPoint(16, 13, -17), ExpeditionPoint(16, 13, -8), mid,
            ExpeditionPoint(16, 13, 8), ExpeditionPoint(16, 13, 17), end)
        listOf(start, mid, end, left[1], left[4], right[1], right[4]).forEach { point ->
            builder.chamber(point, if (point == mid) 12 else 8, 7, if (point == mid) 12 else 8, floorY = 13)
        }
        builder.station("entry", ExpeditionPoint(0, 13, -27))
        builder.station("ark_start", start)
        builder.station("ark_mid", mid)
        builder.station("ark_end", end)
        builder.station("exit", ExpeditionPoint(0, 13, 27))
        builder.station("fuel_supply", ExpeditionPoint(-16, 13, -19))
        builder.station("coolant_supply", ExpeditionPoint(16, 13, -19))
        listOf(ExpeditionPoint(-16, 13, -8), ExpeditionPoint(16, 13, -8), ExpeditionPoint(-16, 13, 8))
            .forEachIndexed { index, point -> builder.station("jam_$index", point) }
        listOf(ExpeditionPoint(-16, 13, -17), ExpeditionPoint(16, 13, 8), ExpeditionPoint(-16, 13, 17))
            .forEachIndexed { index, point -> builder.station("survey_$index", point) }
        builder.station("branch_left", ExpeditionPoint(-16, 13, -10))
        builder.station("branch_right", ExpeditionPoint(16, 13, -10))

        builder.route("ark_left", left)
        builder.route("ark_right", right)
        builder.walk(listOf(builder.stations.getValue("entry"), start))
        builder.walk(listOf(end, builder.stations.getValue("exit")))
        // ARK_FORK uses the direct central spine before a branch is chosen;
        // reserve that full sweep as well as every intermediate branch cell.
        reserveCrawlerEnvelope(builder, listOf(start, mid) + left + right)
        builder.walk(listOf(builder.stations.getValue("fuel_supply"), left[1]))
        builder.walk(listOf(builder.stations.getValue("coolant_supply"), right[1]))

        MineExpeditionStructures.decorateArk(builder, start, mid, end)
        return builder.finish()
    }

    private fun deadFactory(seed: Long): MineExpeditionPlan {
        val bounds = ExpeditionBounds(ExpeditionPoint(-34, 0, -28), ExpeditionPoint(34, 35, 31))
        val builder = MineExpeditionBuilder(MineExpeditionKind.DEAD_FACTORY, seed, bounds)
        listOf(
            ExpeditionPoint(-22, 20, 0) to Triple(12, 13, 14),
            ExpeditionPoint(0, 18, 0) to Triple(19, 15, 19),
            ExpeditionPoint(20, 20, 0) to Triple(13, 14, 14),
            ExpeditionPoint(0, 14, 19) to Triple(15, 8, 10),
            ExpeditionPoint(-26, 14, 20) to Triple(8, 7, 9),
            ExpeditionPoint(26, 14, 20) to Triple(8, 7, 9),
            ExpeditionPoint(7, 17, -17) to Triple(14, 10, 10),
        ).forEach { (center, radius) -> builder.chamber(center, radius.first, radius.second, radius.third, floorY = 11) }
        builder.station("entry", ExpeditionPoint(-29, 11, 22))
        builder.station("exit", ExpeditionPoint(29, 11, 22))
        listOf(-16, 0, 16).forEachIndexed { index, x -> builder.station("water_valve_$index", ExpeditionPoint(x, 11, 8)) }
        builder.station("fuel_supply", ExpeditionPoint(12, 11, 8))
        builder.station("furnace_input", ExpeditionPoint(20, 11, 7))
        builder.station("furnace_control", ExpeditionPoint(24, 11, 0))
        builder.station("pour_control", ExpeditionPoint(20, 11, -8))
        builder.station("crane_control", ExpeditionPoint(0, 11, -7))
        builder.station("assembly_socket", ExpeditionPoint(0, 11, -19))

        val main = listOf(
            builder.stations.getValue("entry"), ExpeditionPoint(-29, 11, 14), ExpeditionPoint(-22, 11, 8),
            ExpeditionPoint(-8, 11, 8), ExpeditionPoint(8, 11, 8), ExpeditionPoint(22, 11, 14),
            builder.stations.getValue("exit"),
        )
        builder.route("main", main)
        builder.walk(listOf(ExpeditionPoint(-8, 11, 8), ExpeditionPoint(0, 11, -7), builder.stations.getValue("crane_control")))
        builder.walk(listOf(ExpeditionPoint(8, 11, 8), builder.stations.getValue("furnace_input"), builder.stations.getValue("furnace_control")))
        builder.walk(listOf(builder.stations.getValue("furnace_control"), builder.stations.getValue("pour_control")))
        builder.walk(listOf(builder.stations.getValue("crane_control"), builder.stations.getValue("assembly_socket")))

        MineExpeditionStructures.decorateFactory(builder)
        return builder.finish()
    }

    private fun reserveCrawlerEnvelope(builder: MineExpeditionBuilder, route: List<ExpeditionPoint>) {
        val centers = mutableListOf<ExpeditionPoint>()
        route.zipWithNext().forEach { (from, to) ->
            val segment = builder.line(from, to)
            if (centers.isEmpty()) centers += segment else centers += segment.drop(1)
        }
        if (route.size == 1) centers += route
        centers.distinct().forEach { center ->
            for (dx in -5..5) for (dz in -8..8) {
                for (dy in 0..8) builder.reserveAir(center.offset(dx, dy, dz))
                for (dy in -3 until 0) builder.support(center.offset(dx, dy, dz), "minecraft:deepslate")
            }
        }
    }
}

package ru.ruscrafting.farms.domain.mine.expedition

import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed

/** Deliberate silhouettes and readable machine components for each scene. */
internal object MineExpeditionStructures {
    fun decorateDescent(builder: MineExpeditionBuilder) {
        listOf(
            ExpeditionPoint(0, 53, 0), ExpeditionPoint(0, 31, 0), ExpeditionPoint(0, 9, 0),
            ExpeditionPoint(0, 5, -14),
        ).forEach { center ->
            builder.strataAround(center, radiusX = 15, radiusY = 7, radiusZ = 15, salt = 0xD35E11L)
            supportArch(builder, center, radius = 14, height = 7)
        }

        // Vertical timber ribs and chain-and-lantern brackets make the shaft
        // legible from all three decks while staying outside the lift sweep.
        for (y in 10..51 step 6) {
            beam(builder, ExpeditionPoint(-14, y, -11), ExpeditionPoint(-14, y + 5, -11), "minecraft:spruce_log[axis=y]")
            beam(builder, ExpeditionPoint(14, y, -11), ExpeditionPoint(14, y + 5, -11), "minecraft:spruce_log[axis=y]")
            beam(builder, ExpeditionPoint(-14, y + 5, -11), ExpeditionPoint(14, y + 5, -11), "minecraft:spruce_log[axis=x]")
            builder.light(ExpeditionPoint(-12, y + 4, -10), "east")
            builder.light(ExpeditionPoint(12, y + 4, -10), "west")
        }
        listOf(12, 26, 40, 54).forEach { y ->
            builder.solid(ExpeditionPoint(-11, y, 11), "minecraft:iron_chain[axis=y]")
            builder.solid(ExpeditionPoint(-11, y - 1, 11), "minecraft:lantern[hanging=true,waterlogged=false]")
        }

        // Counterweights are intentional breakable rubble on the broad middle dock.
        listOf(-5, 0, 5).forEachIndexed { index, z ->
            val station = builder.stations.getValue("counterweight_$index")
            builder.solid(station.offset(dx = 2), "minecraft:gravel")
            builder.solid(station.offset(dx = 2, dy = 1), "minecraft:cobblestone")
            builder.solid(station.offset(dx = 2, dz = -1), "minecraft:iron_bars[east=false,north=true,south=true,west=false,waterlogged=false]")
        }
        builder.solid(builder.stations.getValue("power_supply").offset(dx = -2), "minecraft:barrel[facing=east,open=false]")
        builder.solid(builder.stations.getValue("power_socket").offset(dx = -2), "minecraft:redstone_block")
        builder.solid(builder.stations.getValue("power_socket").offset(dx = -2, dy = 1), "minecraft:lightning_rod[facing=up,waterlogged=false]")

        listOf(0, 1, 2).forEach { index ->
            val station = builder.stations.getValue("core_valve_$index")
            builder.solid(station.offset(dx = 2), "minecraft:copper_block")
            builder.solid(station.offset(dx = 2, dy = 1), "minecraft:lever[face=wall,facing=west,powered=false]")
            builder.solid(station.offset(dx = 2, dz = -1), "minecraft:iron_chain[axis=y]")
        }

        // The bottom engine is a large recognisable rear landmark, assembled
        // from a vertical wheel, piston-like spokes and a lit redstone core.
        // It sits above the lower dock so the player can read the silhouette
        // from the core_start approach instead of seeing it buried below the
        // lift deck.
        val engine = ExpeditionPoint(0, 18, -20)
        ring(builder, engine, radius = 8, block = "minecraft:copper_block", vertical = true)
        ring(builder, engine.offset(dz = 1), radius = 6, block = "minecraft:polished_blackstone", vertical = true)
        beam(builder, ExpeditionPoint(-8, 18, -20), ExpeditionPoint(8, 18, -20), "minecraft:copper_block")
        beam(builder, ExpeditionPoint(0, 10, -20), ExpeditionPoint(0, 26, -20), "minecraft:copper_block")
        listOf(-6, -3, 0, 3, 6).forEach { x ->
            builder.solid(ExpeditionPoint(x, 18, -20), "minecraft:iron_block")
            builder.solid(ExpeditionPoint(x, 19, -20), "minecraft:iron_chain[axis=y]")
        }
        // Keep the redstone core last: the cross braces must never overwrite
        // the material used by the geometry acceptance test and player view.
        builder.solid(engine, "minecraft:redstone_block")
        builder.solid(engine.offset(dy = 1), "minecraft:lantern[hanging=false,waterlogged=false]")

        // A contained waterfall gives the side wall a moving focal point. The
        // river is narrow and bordered, so it cannot flood a playable route.
        for (y in 9..45) {
            builder.air(ExpeditionPoint(25, y, 8))
            builder.put(ExpeditionPoint(25, y, 8), "minecraft:water[level=0]")
            builder.solid(ExpeditionPoint(24, y, 8), "minecraft:stone")
            builder.solid(ExpeditionPoint(26, y, 8), "minecraft:stone")
            if (y % 4 == 0) builder.solid(ExpeditionPoint(24, y, 7), "minecraft:mossy_cobblestone")
        }
        for (x in 23..27) for (z in 6..10) builder.solid(ExpeditionPoint(x, 8, z), "minecraft:stone")
        listOf(
            ExpeditionPoint(-8, 52, -10), ExpeditionPoint(8, 47, 10),
            ExpeditionPoint(-8, 25, 10), ExpeditionPoint(8, 3, -8),
        ).forEach { anchor -> builder.stalactite(anchor, 2 + feature(seed = builder.seed, point = anchor, modulo = 3), "minecraft:dripstone_block") }
        builder.outcrop(ExpeditionPoint(-12, 43, 6), 4, 'x', "minecraft:calcite")
        builder.outcrop(ExpeditionPoint(11, 22, -6), 3, 'z', "minecraft:tuff")
    }

    fun decorateArk(builder: MineExpeditionBuilder, start: ExpeditionPoint, mid: ExpeditionPoint, end: ExpeditionPoint) {
        listOf(start, mid, end, ExpeditionPoint(-16, 13, -8), ExpeditionPoint(16, 13, -8))
            .forEach { center ->
                builder.strataAround(center, radiusX = 12, radiusY = 8, radiusZ = 12, salt = 0xA4A57L)
                dockArch(builder, center)
            }
        listOf(-20, -10, 0, 10, 20).forEach { z ->
            val side = if (feature(builder.seed, ExpeditionPoint(2, 13, z), 2) == 0) -1 else 1
            val x = side * 8
            beam(builder, ExpeditionPoint(x, 13, z), ExpeditionPoint(x, 21, z), "minecraft:spruce_log[axis=y]")
            beam(builder, ExpeditionPoint(-8, 21, z), ExpeditionPoint(8, 21, z), "minecraft:spruce_log[axis=x]")
            builder.light(ExpeditionPoint(side * 7, 19, z), if (side < 0) "east" else "west")
        }

        // Branch docks carry survey flags and breakable jams outside the 9x9 crawler.
        listOf(
            ExpeditionPoint(-16, 13, -8), ExpeditionPoint(16, 13, -8), ExpeditionPoint(-16, 13, 8),
        ).forEachIndexed { index, point ->
            val side = if (point.x < 0) -1 else 1
            builder.solid(point.offset(dx = side * 6), "minecraft:gravel")
            builder.solid(point.offset(dx = side * 6, dy = 1), "minecraft:pointed_dripstone[vertical_direction=up,thickness=tip]" )
            val survey = builder.stations.getValue("survey_$index")
            builder.solid(survey.offset(dx = if (survey.x < 0) -6 else 6), "minecraft:calcite")
            builder.solid(survey.offset(dx = if (survey.x < 0) -6 else 6, dy = 1), "minecraft:amethyst_block")
        }
        val fuel = builder.stations.getValue("fuel_supply")
        val coolant = builder.stations.getValue("coolant_supply")
        builder.solid(fuel.offset(dx = -6), "minecraft:barrel[facing=east,open=false]")
        builder.solid(fuel.offset(dx = -6, dy = 1), "minecraft:iron_chain[axis=y]")
        builder.solid(coolant.offset(dx = 6), "minecraft:barrel[facing=west,open=false]")
        builder.solid(coolant.offset(dx = 6, dy = 1), "minecraft:water_cauldron[level=3]")

        // The static dock machinery frames the root-owned moving crawler; its
        // body and passenger deck remain in the explicitly reserved sweep.
        beam(builder, ExpeditionPoint(-24, 13, 2), ExpeditionPoint(-24, 21, 2), "minecraft:iron_block")
        beam(builder, ExpeditionPoint(24, 13, 2), ExpeditionPoint(24, 21, 2), "minecraft:iron_block")
        beam(builder, ExpeditionPoint(-24, 21, 2), ExpeditionPoint(24, 21, 2), "minecraft:iron_block")
        beam(builder, ExpeditionPoint(-24, 21, 2), ExpeditionPoint(-24, 21, 16), "minecraft:copper_block")
        beam(builder, ExpeditionPoint(24, 21, 2), ExpeditionPoint(24, 21, 16), "minecraft:copper_block")
        for (x in -20..20 step 4) builder.solid(ExpeditionPoint(x, 21, 10), "minecraft:iron_chain[axis=y]")
        for (x in -20..20 step 4) builder.solid(ExpeditionPoint(x, 20, 10), "minecraft:lantern[hanging=true,waterlogged=false]")

        // A high service bridge crosses the fork above the crawler envelope.
        // Its side rails are deliberately two blocks above the reserved roof.
        for (x in -14..14) builder.solid(ExpeditionPoint(x, 22, 0), "minecraft:spruce_planks")
        for (x in listOf(-14, 14)) for (y in 20..22) builder.solid(ExpeditionPoint(x, y, 0), "minecraft:spruce_log[axis=y]")
        for (x in -14..14 step 2) {
            builder.solid(ExpeditionPoint(x, 23, -1), "minecraft:spruce_fence[north=true,south=true]")
            builder.solid(ExpeditionPoint(x, 23, 1), "minecraft:spruce_fence[north=true,south=true]")
        }
        builder.light(ExpeditionPoint(-10, 22, -1), "east")
        builder.light(ExpeditionPoint(10, 22, 1), "west")

        // A huge drill crown beyond the end stop reads as the destination
        // landmark without colliding with the crawler's swept volume.
        // Keep the crown above the crawler roof so its silhouette remains a
        // real block landmark instead of being swallowed by the moving sweep.
        val crown = ExpeditionPoint(0, 24, 30)
        ring(builder, crown, 7, "minecraft:iron_block", vertical = false)
        ring(builder, crown.offset(dy = 1), 5, "minecraft:copper_block", vertical = false)
        builder.solid(crown, "minecraft:beacon")
        builder.solid(crown.offset(dy = -1), "minecraft:netherite_block")
        for (x in -6..6 step 3) builder.solid(ExpeditionPoint(x, 22, 30), "minecraft:deepslate_tiles")
        builder.outcrop(ExpeditionPoint(-24, 17, -2), 5, 'z', "minecraft:andesite")
        builder.outcrop(ExpeditionPoint(24, 16, 14), 4, 'x', "minecraft:tuff")
    }

    fun decorateFactory(builder: MineExpeditionBuilder) {
        val wheel = ExpeditionPoint(-22, 18, 0)
        ring(builder, wheel, 9, "minecraft:dark_oak_planks", vertical = true)
        ring(builder, wheel, 7, "minecraft:water[level=0]", vertical = true)
        builder.solid(wheel, "minecraft:dark_oak_log[axis=z]")
        builder.solid(wheel.offset(dx = -10), "minecraft:stone_bricks")
        builder.solid(wheel.offset(dx = 10), "minecraft:stone_bricks")
        beam(builder, ExpeditionPoint(-31, 11, 0), ExpeditionPoint(-31, 25, 0), "minecraft:stone_bricks")
        beam(builder, ExpeditionPoint(-13, 11, 0), ExpeditionPoint(-13, 25, 0), "minecraft:stone_bricks")
        beam(builder, ExpeditionPoint(-31, 25, 0), ExpeditionPoint(-13, 25, 0), "minecraft:dark_oak_log[axis=x]")
        for (y in 14..24 step 3) builder.light(ExpeditionPoint(-30, y, 5), "east")

        // Gantry, trolley and suspended chains above the central assembly route.
        beam(builder, ExpeditionPoint(-14, 11, -2), ExpeditionPoint(-14, 25, -2), "minecraft:iron_block")
        beam(builder, ExpeditionPoint(14, 11, -2), ExpeditionPoint(14, 25, -2), "minecraft:iron_block")
        beam(builder, ExpeditionPoint(-14, 25, -2), ExpeditionPoint(14, 25, -2), "minecraft:iron_block")
        beam(builder, ExpeditionPoint(-8, 24, -2), ExpeditionPoint(8, 24, -2), "minecraft:copper_block")
        listOf(-6, 0, 6).forEach { x ->
            beam(builder, ExpeditionPoint(x, 23, -2), ExpeditionPoint(x, 17, -2), "minecraft:iron_chain[axis=y]")
            builder.solid(ExpeditionPoint(x, 16, -2), "minecraft:iron_block")
        }
        builder.solid(builder.stations.getValue("crane_control").offset(dx = -2), "minecraft:lever[face=wall,facing=east,powered=false]")

        // Boiler and furnace are layered assemblies, not a single box.
        val boiler = ExpeditionPoint(20, 18, 1)
        ring(builder, boiler, 6, "minecraft:copper_block", vertical = true)
        builder.solid(boiler, "minecraft:water[level=0]")
        builder.solid(boiler.offset(dy = -7), "minecraft:blast_furnace[facing=west,lit=false]")
        builder.solid(boiler.offset(dx = -3, dy = -3), "minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]")
        builder.solid(boiler.offset(dx = 3, dy = -3), "minecraft:smoker[facing=west,lit=false]")
        builder.solid(boiler.offset(dy = 7), "minecraft:iron_bars[east=true,north=true,south=true,west=true,waterlogged=false]")
        beam(builder, ExpeditionPoint(20, 25, 1), ExpeditionPoint(20, 32, 1), "minecraft:polished_blackstone")
        builder.light(ExpeditionPoint(25, 16, 0), "west")

        // Suspended pipes run between the three houses and turn down at controls.
        beam(builder, ExpeditionPoint(-12, 27, 4), ExpeditionPoint(22, 27, 4), "minecraft:copper_block")
        beam(builder, ExpeditionPoint(-12, 26, 5), ExpeditionPoint(22, 26, 5), "minecraft:cut_copper")
        listOf(-8, 0, 10, 18).forEach { x ->
            beam(builder, ExpeditionPoint(x, 27, 4), ExpeditionPoint(x, 20, 4), "minecraft:iron_chain[axis=y]")
            builder.solid(ExpeditionPoint(x, 19, 4), "minecraft:lightning_rod[facing=up,waterlogged=false]")
        }

        // Mould rack and completed engine sit in the rear chamber.
        for (x in -8..8 step 4) {
            beam(builder, ExpeditionPoint(x, 11, -21), ExpeditionPoint(x, 16, -21), "minecraft:iron_bars[east=true,north=false,south=false,west=false,waterlogged=false]")
            builder.solid(ExpeditionPoint(x, 16, -21), "minecraft:iron_chain[axis=y]")
            builder.solid(ExpeditionPoint(x, 10, -21), "minecraft:polished_blackstone")
        }
        ring(builder, ExpeditionPoint(14, 16, -18), 5, "minecraft:iron_block", vertical = false)
        builder.solid(ExpeditionPoint(14, 16, -18), "minecraft:redstone_block")
        builder.solid(ExpeditionPoint(14, 17, -18), "minecraft:lantern[hanging=false,waterlogged=false]")
        beam(builder, ExpeditionPoint(8, 16, -18), ExpeditionPoint(20, 16, -18), "minecraft:copper_block")

        // Static molten channels: magma banks and tiny bounded lava pockets.
        for (x in 8..28) {
            val z = -7 + if (feature(builder.seed, ExpeditionPoint(x, 10, -7), 3) == 0) 0 else 1
            builder.solid(ExpeditionPoint(x, 10, z), "minecraft:magma_block")
            builder.solid(ExpeditionPoint(x, 10, z - 1), "minecraft:polished_blackstone")
            if (x % 5 == 0) blockIfClear(builder, ExpeditionPoint(x, 11, z), "minecraft:lava[level=0]")
        }
        builder.light(ExpeditionPoint(4, 15, 2), "west")
        builder.light(ExpeditionPoint(-4, 15, 2), "east")
        listOf(ExpeditionPoint(-28, 24, -6), ExpeditionPoint(-12, 28, 10), ExpeditionPoint(28, 29, -5))
            .forEach { builder.stalactite(it, 2 + feature(builder.seed, it, 3), "minecraft:dripstone_block") }
        builder.outcrop(ExpeditionPoint(-32, 14, 8), 5, 'z', "minecraft:andesite")
        builder.outcrop(ExpeditionPoint(30, 23, -2), 4, 'x', "minecraft:tuff")
    }

    private fun supportArch(builder: MineExpeditionBuilder, center: ExpeditionPoint, radius: Int, height: Int) {
        val side = radius + 1
        beam(builder, center.offset(dx = -side, dy = -1, dz = -side), center.offset(dx = -side, dy = height, dz = -side), "minecraft:spruce_log[axis=y]")
        beam(builder, center.offset(dx = side, dy = -1, dz = -side), center.offset(dx = side, dy = height, dz = -side), "minecraft:spruce_log[axis=y]")
        beam(builder, center.offset(dx = -side, dy = height, dz = -side), center.offset(dx = side, dy = height, dz = -side), "minecraft:spruce_log[axis=x]")
    }

    private fun dockArch(builder: MineExpeditionBuilder, center: ExpeditionPoint) {
        val side = 7
        beam(builder, center.offset(dx = -side, dy = -1), center.offset(dx = -side, dy = 8), "minecraft:spruce_log[axis=y]")
        beam(builder, center.offset(dx = side, dy = -1), center.offset(dx = side, dy = 8), "minecraft:spruce_log[axis=y]")
        beam(builder, center.offset(dx = -side, dy = 8), center.offset(dx = side, dy = 8), "minecraft:spruce_log[axis=x]")
        builder.light(center.offset(dx = -6, dy = 6), "east")
        builder.light(center.offset(dx = 6, dy = 6), "west")
    }

    private fun beam(builder: MineExpeditionBuilder, from: ExpeditionPoint, to: ExpeditionPoint, block: String) {
        val xStep = if (to.x >= from.x) 1 else -1
        val yStep = if (to.y >= from.y) 1 else -1
        val zStep = if (to.z >= from.z) 1 else -1
        var point = from
        blockIfClear(builder, point, block)
        while (point.x != to.x) {
            point = point.offset(dx = xStep)
            blockIfClear(builder, point, block)
        }
        while (point.y != to.y) {
            point = point.offset(dy = yStep)
            blockIfClear(builder, point, block)
        }
        while (point.z != to.z) {
            point = point.offset(dz = zStep)
            blockIfClear(builder, point, block)
        }
    }

    private fun feature(seed: Long, point: ExpeditionPoint, modulo: Int): Int =
        Math.floorMod(WorksiteDeterministicSeed.positionScore(seed, "expedition_feature", point.x, point.y, point.z), modulo.toLong()).toInt()
}

package ru.ruscrafting.farms.domain.mine.expedition

/** Physical machine assemblies, relative to the deck's central feet position, facing +Z. */
object MineExpeditionMachines {
    fun blocks(kind: MineExpeditionKind): Map<ExpeditionPoint, String> = when (kind) {
        MineExpeditionKind.LAST_DESCENT -> lift()
        MineExpeditionKind.DRILLING_ARK -> crawler()
        MineExpeditionKind.DEAD_FACTORY -> emptyMap()
    }

    private fun lift() = buildMap {
        for (x in -3..3) for (z in -2..2) {
            put(ExpeditionPoint(x, -1, z), block(if (x in -2..2) "spruce_planks" else "stripped_spruce_log[axis=z]"))
        }
        // +Z is the panoramic front. Its entire eye-level span stays open.
        for (z in -2..1) for (x in listOf(-3, 3)) {
            put(ExpeditionPoint(x, 0, z), block("spruce_fence[north=true,south=true]"))
        }
        for (x in -2..2) put(ExpeditionPoint(x, 0, -2), block("spruce_fence[east=true,west=true]"))
        for (x in listOf(-3, 3)) {
            put(ExpeditionPoint(x, 1, -2), block("stripped_spruce_log[axis=y]"))
            put(ExpeditionPoint(x, 2, -2), block("lantern[hanging=false]"))
        }
        put(ExpeditionPoint(0, 0, -2), block("chiseled_copper"))
    }

    private fun crawler() = buildMap {
        for (x in -4..4) for (z in -6..6) {
            put(ExpeditionPoint(x, -1, z), block(when {
                x == -4 || x == 4 -> "polished_blackstone"
                z == -6 || z == 6 -> "waxed_oxidized_cut_copper"
                x == -3 || x == 3 -> "waxed_copper_grate"
                else -> "waxed_exposed_cut_copper"
            }))
        }
        for (x in listOf(-4, 4)) for (z in -6..6) {
            put(ExpeditionPoint(x, -2, z), block(if (z % 3 == 0) "chiseled_polished_blackstone" else "polished_blackstone_bricks"))
        }
        for (x in -2..2) for (z in -3..1) {
            put(ExpeditionPoint(x, -2, z), block("deepslate_tiles"))
        }
        // Narrow boiler and drive shaft leave full side catwalks on both sides.
        for (x in -1..1) for (z in -3..0) for (y in 0..1) {
            put(ExpeditionPoint(x, y, z), block(if (y == 1) "waxed_exposed_cut_copper" else "waxed_weathered_copper"))
        }
        put(ExpeditionPoint(0, 0, -4), block("blast_furnace[facing=south,lit=true]"))
        put(ExpeditionPoint(0, 1, -4), block("chiseled_copper"))
        for (y in 2..5) put(ExpeditionPoint(0, y, -3), block("polished_deepslate_wall[east=none,north=none,south=none,west=none,up=true,waterlogged=false]"))
        put(ExpeditionPoint(0, 6, -3), block("lightning_rod[facing=up]"))
        for (x in listOf(-2, 2)) {
            for (z in 0..4) put(ExpeditionPoint(x, 0, z), block("waxed_oxidized_cut_copper_slab[type=bottom]"))
            put(ExpeditionPoint(x, 1, 1), block("copper_bulb[lit=true,powered=false]"))
            put(ExpeditionPoint(x, 0, -5), block("chiseled_copper"))
        }
        for (x in -2..2) for (y in 0..2) {
            if (x * x + (y - 1) * (y - 1) <= 5) put(ExpeditionPoint(x, y, 6), block("polished_andesite"))
        }
        for (x in -1..1) for (y in 0..2) put(ExpeditionPoint(x, y, 7), block("dripstone_block"))
        for (x in listOf(-4, 4)) for (z in listOf(-5, -1, 3, 5)) {
            put(ExpeditionPoint(x, 0, z), block("iron_bars[north=true,south=true]"))
        }
        for (x in listOf(-3, 3)) {
            put(ExpeditionPoint(x, 0, -6), block("stripped_dark_oak_log[axis=y]"))
            put(ExpeditionPoint(x, 1, -6), block("lantern[hanging=false]"))
        }
        put(ExpeditionPoint(-1, 0, 3), block("smithing_table"))
        put(ExpeditionPoint(1, 0, 3), block("chiseled_copper"))
    }

    private fun block(value: String) = "minecraft:$value"
}

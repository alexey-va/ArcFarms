package ru.ruscrafting.farms.domain.mine.expedition

/**
 * Canonical anchors for the permanent dead-factory production line.
 *
 * The geometry owns the blocks around these anchors while the paper layer owns
 * the display models. Keeping the map here lets progression code depend on
 * station ids without importing the layout implementation.
 */
object MineFactoryLine {
    val machines: Map<String, ExpeditionPoint> = linkedMapOf(
        "decor_crusher_left" to ExpeditionPoint(-22, 5, -6),
        "decor_conveyor_raw" to ExpeditionPoint(-14, 5, -6),
        "decor_furnace_left" to ExpeditionPoint(-7, 5, -6),
        "pour_control" to ExpeditionPoint(5, 5, -6),
        "decor_roller_table" to ExpeditionPoint(13, 5, -6),
        "assembly_socket" to ExpeditionPoint(22, 5, -6),
        "decor_pump_left" to ExpeditionPoint(-25, 5, -18),
        "decor_tank_left" to ExpeditionPoint(-17, 5, -18),
        "decor_waterwheel" to ExpeditionPoint(0, 5, -23),
    )

    /** All authored station anchors, including compatibility valve aliases. */
    val stations: Map<String, ExpeditionPoint> = linkedMapOf(
        "entry" to ExpeditionPoint(0, 5, 24),
        "exit" to ExpeditionPoint(0, 5, 24),
        "water_valve_0" to ExpeditionPoint(-27, 5, -6),
        "water_valve_1" to ExpeditionPoint(-25, 5, -15),
        "water_valve_2" to ExpeditionPoint(27, 5, -6),
        "fuel_supply" to ExpeditionPoint(-25, 5, 15),
        "crusher_feed" to ExpeditionPoint(-25, 5, -2),
        "control_crusher_left" to ExpeditionPoint(-20, 5, -2),
        "crushed_output" to ExpeditionPoint(-13, 5, -2),
        "furnace_input" to ExpeditionPoint(-10, 5, -2),
        "furnace_control" to ExpeditionPoint(-5, 5, -2),
        "pour_control" to machines.getValue("pour_control"),
        "pour_console" to ExpeditionPoint(7, 5, -4),
        "crane_control" to ExpeditionPoint(15, 5, -2),
        "crane_load" to ExpeditionPoint(17, 5, -6),
        "assembly_socket" to machines.getValue("assembly_socket"),
        "repair_supply_0" to ExpeditionPoint(-28, 5, 11),
        "repair_supply_1" to ExpeditionPoint(-22, 5, 16),
        "repair_supply_2" to ExpeditionPoint(-29, 5, 4),
        "crusher_repair" to ExpeditionPoint(-24, 5, -3),
    )

    /**
     * Resolve a station through the small in-place geometry migration used by
     * the connected factory.  The first geometry-v3 room placed the repair
     * socket one block into the hopper/light envelope.  Existing journals and
     * persisted plans must keep their authored map, while gameplay and the
     * furnishing editor need the clear aisle anchor.  A non-legacy/custom
     * point is returned unchanged, which also preserves explicit admin poses.
     */
    fun effectiveStation(plan: MineExpeditionPlan, id: String): ExpeditionPoint {
        val point = plan.stations.getValue(id)
        return if (id == "crusher_repair" && point == LEGACY_CRUSHER_REPAIR) {
            point.offset(dx = 1, dz = 1)
        } else {
            point
        }
    }

    /** Physical owner used by the furnishing editor for relative child poses. */
    val owners: Map<String, String> = mapOf(
        "water_valve_1" to "decor_pump_left",
        "crusher_feed" to "decor_crusher_left",
        "control_crusher_left" to "decor_crusher_left",
        "crushed_output" to "decor_conveyor_raw",
        "crusher_repair" to "decor_crusher_left",
        "furnace_input" to "decor_furnace_left",
        "furnace_control" to "decor_furnace_left",
        "pour_console" to "pour_control",
        "crane_control" to "decor_roller_table",
        "crane_load" to "decor_roller_table",
    )

    /**
     * Former geometry-v3 floor palette used by the already-built static scene.
     * A null result means the point was not part of that floor. This is used
     * only for an in-place, edit-preserving migration of existing static rooms.
     */
    fun formerFloorMaterial(point: ExpeditionPoint): String? {
        if (point.y != 4 || point.x !in -33..33 || point.z !in -28..27) return null
        var material = POLISHED_ANDESITE
        for (side in listOf(-1, 1)) for (zRange in listOf(-22..-3, 3..22)) {
            val xx = point.x * side
            if (xx in 5..33 && point.z in zRange) {
                val border = xx == 5 || xx == 33 || point.z == zRange.first || point.z == zRange.last
                material = if (border) POLISHED_ANDESITE else POLISHED_DEEPSLATE
            }
        }
        if (point.x in -7..7 && point.z in -28..-15) {
            material = if (point.x in listOf(-7, 7) || point.z in listOf(-28, -15)) {
                POLISHED_ANDESITE
            } else {
                POLISHED_DEEPSLATE
            }
        }
        return material
    }

    private const val POLISHED_ANDESITE = "minecraft:polished_andesite"
    private const val POLISHED_DEEPSLATE = "minecraft:polished_deepslate"
    private val LEGACY_CRUSHER_REPAIR = ExpeditionPoint(-24, 5, -3)
}

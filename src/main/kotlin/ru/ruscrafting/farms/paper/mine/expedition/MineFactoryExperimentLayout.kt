package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.util.Vector

/**
 * Shared fixture-relative poses for the optional factory experiments.
 *
 * The parent scene resolves the named anchor through its normal editor and
 * legacy migration path.  Keeping offsets here gives runtime markers and the
 * preview exporter one source of truth without adding stations to the durable
 * expedition plan.
 */
internal object MineFactoryExperimentLayout {
    data class Fixture(
        val id: String,
        val anchor: String,
        val offset: Vector,
        val model: String,
        val scale: Float = 0.7f,
        val yaw: Int = 0,
        val interactive: Boolean = true,
    ) {
        fun copyOffset() = offset.clone()
    }

    val rockJam = Fixture(
        id = "factory_experiment_rock_jam",
        anchor = "decor_crusher_left",
        // The crusher rolls are centred near local y=3.15.  The jam stays at
        // the front axial end so a player can pry it from the floor aisle.
        offset = Vector(0.0, 2.70, 1.45),
        model = "factory_jam_stone",
        scale = 1.0f,
    )

    val routeGate = Fixture(
        id = "factory_experiment_route_gate",
        anchor = "decor_conveyor_raw",
        // The gate pad rests on the conveyor's upper deck rather than below it.
        offset = Vector(-2.0, 1.24, 1.00),
        model = "factory_route_gate",
        scale = 0.64f,
    )

    val routeBin = Fixture(
        // Reuse the authored crushed-output hopper as the routing accumulator.
        // The colliding id makes the experiment target replace that furnishing
        // only while ROUTING is pending, so two bins cannot render together.
        id = "crushed_output",
        anchor = "crushed_output",
        offset = Vector(0.0, 0.0, 0.0),
        model = "charge_hopper",
        scale = 1.0f,
        interactive = false,
    )

    val hoseReel = Fixture(
        id = "factory_experiment_hose_reel",
        anchor = "decor_roller_table",
        offset = Vector(-2.20, 0.0, 2.00),
        model = "factory_hose_reel",
        scale = 0.64f,
    )

    val hoseNozzle = Fixture(
        id = "factory_experiment_hose_nozzle",
        anchor = "decor_roller_table",
        offset = Vector(-1.00, 0.0, 2.00),
        model = "factory_hose_nozzle",
        scale = 0.64f,
    )

    val hotBearing = Fixture(
        id = "factory_experiment_hot_bearing",
        anchor = "decor_roller_table",
        // Backplate meets the roller table's front rail; the core remains at
        // player height instead of sitting on the room floor.
        offset = Vector(0.0, 0.90, 1.55),
        model = "factory_hot_bearing",
        scale = 0.68f,
        interactive = false,
    )

    val craneControl = Fixture(
        id = "crane_control",
        anchor = "crane_control",
        offset = Vector(0.0, 0.0, 0.0),
        model = "mounted_console",
        scale = 1.0f,
    )

    val craneLanding = Fixture(
        // Replace the permanent casting rack for the manual-crane variant.
        // Keeping the canonical id suppresses the old rack in the furnishing
        // scope while this flat landing is active.
        id = "crane_load",
        anchor = "crane_load",
        // The roller deck is around local y=1.4; the pad starts at y=0.
        offset = Vector(0.0, 1.40, 0.0),
        model = "factory_crane_landing",
        scale = 0.72f,
    )

    val mouldSocket = Fixture(
        id = "factory_experiment_mould_socket",
        anchor = "assembly_socket",
        // Bench top is around local y=1.5; move the socket to its front edge.
        offset = Vector(0.0, 1.50, 1.50),
        model = "factory_mould_socket",
        scale = 0.58f,
        interactive = false,
    )

    private val moulds = listOf(
        // Floor stands sit outside the press envelope and face the player on +Z.
        Fixture("factory_experiment_mould_gear", "assembly_socket", Vector(-2.25, 0.0, 2.90), "factory_mould_gear", .58f),
        Fixture("factory_experiment_mould_plate", "assembly_socket", Vector(0.0, 0.0, 2.90), "factory_mould_plate", .58f),
        Fixture("factory_experiment_mould_rod", "assembly_socket", Vector(2.25, 0.0, 2.90), "factory_mould_rod", .58f),
    )

    fun mould(index: Int): Fixture = moulds[index.coerceIn(moulds.indices)]

    /** Preview/export consumers can enumerate every stable pose without state. */
    fun staticFixtures(): List<Fixture> = listOf(
        rockJam, routeGate, routeBin, hoseReel, hoseNozzle, hotBearing,
        craneControl, craneLanding, mouldSocket,
    ) + moulds
}

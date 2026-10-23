package ru.ruscrafting.farms.paper.mine.floorwork

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.MineFloorActivity
import ru.ruscrafting.farms.domain.MineIncidentType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Canonical machine geometry in blocks; the anchor is the verified platform surface. */
internal object MineFloorActivityGeometry {
    data class Part(
        val id: String,
        val material: Material,
        val center: Vector3f,
        val size: Vector3f,
        val rotation: Quaternionf = Quaternionf(),
        val control: String? = null,
        val visible: Boolean = true,
    )

    data class Pose(
        val progress: Int = 0,
        val pull: Float = 0f,
        val swing: Float = 0f,
        val press: Float = 0f,
        val drain: Float = 0f,
        val pipe: Vector3f? = null,
        val pipeYaw: Float = 0f,
        val returning: Float = 0f,
    )

    data class SweptBox(val min: Vector3f, val max: Vector3f)

    data class Bounds(val minX: Float, val maxX: Float, val minZ: Float, val maxZ: Float, val height: Float)

    val WINCH_BOUNDS = Bounds(-7.2f, 5.2f, -3.45f, 5f, 5.65f)
    val PUMP_BOUNDS = Bounds(-7.3f, 7f, -4.1f, 4.6f, 4.7f)
    const val PUMP_OPERATOR_X = -3.5f
    val PIPE_RACK = Vector3f(-6.25f, 1.1f, 2.4f)
    val PIPE_SOCKET = Vector3f(.3f, .95f, .15f)
    val DRAIN_OUTLET = Vector3f(3.2f, .8f, -1.7f)

    val WINCH_CONTROLS = linkedMapOf(
        "hook" to Vector3f(3f, 1.15f, -.73f),
        "rope" to Vector3f(MineFloorActivity.PULL_X.toFloat(), .95f, 1.25f),
        "swing" to Vector3f(.45f, 1.16f, 1.5f),
        "unload" to Vector3f(-4.5f, 1.65f, 3.4f),
    )
    val PUMP_CONTROLS = linkedMapOf(
        "pipe" to PIPE_RACK,
        "socket" to PIPE_SOCKET,
        "handle" to Vector3f(PUMP_OPERATOR_X, 1.68f, 1.086f),
        "valve" to Vector3f(2.3f, 1.22f, .55f),
    )

    fun bounds(type: MineIncidentType): Bounds = if (type == MineIncidentType.FREIGHT_WINCH) WINCH_BOUNDS else PUMP_BOUNDS

    fun controls(type: MineIncidentType): Map<String, Vector3f> =
        if (type == MineIncidentType.FREIGHT_WINCH) WINCH_CONTROLS else PUMP_CONTROLS

    private val winchSweep by lazy { sweep(MineIncidentType.FREIGHT_WINCH) }
    private val pumpSweep by lazy { sweep(MineIncidentType.DRAINAGE_PUMP) }

    /** Conservative per-part envelopes; computed once from the same poses sent to the client. */
    fun sweptBoxes(type: MineIncidentType): List<SweptBox> =
        if (type == MineIncidentType.FREIGHT_WINCH) winchSweep else pumpSweep

    private fun sweep(type: MineIncidentType): List<SweptBox> {
        val envelopes = linkedMapOf<String, SweptBox>()
        for (frame in 0..32) {
            val phase = frame / 32f
            val poses = if (type == MineIncidentType.FREIGHT_WINCH) listOf(
                Pose(pull = phase), Pose(progress = 2, pull = 1f, swing = phase), Pose(progress = 4, returning = phase),
            ) else listOf(Pose(press = phase), Pose(progress = 5, drain = phase))
            for (pose in poses) for (part in parts(type, pose).filter { it.visible }) {
                val box = envelopes.getOrPut(part.id) { SweptBox(Vector3f(Float.POSITIVE_INFINITY), Vector3f(Float.NEGATIVE_INFINITY)) }
                for (x in listOf(-.5f, .5f)) for (y in listOf(-.5f, .5f)) for (z in listOf(-.5f, .5f)) {
                    val corner = part.rotation.transform(Vector3f(x * part.size.x, y * part.size.y, z * part.size.z)).add(part.center)
                    box.min.min(corner); box.max.max(corner)
                }
            }
        }
        return envelopes.values.toList()
    }

    fun parts(type: MineIncidentType, pose: Pose): List<Part> =
        if (type == MineIncidentType.FREIGHT_WINCH) winch(pose) else pump(pose)

    private fun winch(pose: Pose): List<Part> = buildList {
        fun part(id: String, material: Material, x: Float, y: Float, z: Float,
                 sx: Float, sy: Float, sz: Float, control: String? = null,
                 rotation: Quaternionf = Quaternionf(), visible: Boolean = true) {
            add(Part(id, material, Vector3f(x, y, z), Vector3f(sx, sy, sz), rotation, control, visible))
        }
        // An eleven-block bridge spans two separate foundations, leaving the middle open.
        for ((side, x) in listOf("west" to -6.25f, "east" to 4.5f)) {
            part("foot_$side", Material.POLISHED_DEEPSLATE, x, .2f, -1.7f, 1.4f, .4f, 3.35f)
            for ((end, z) in listOf("back" to -2.8f, "front" to -.6f)) {
                part("leg_${side}_$end", Material.STRIPPED_DARK_OAK_LOG, x, 2.72f, z, .58f, 4.64f, .58f)
                part("collar_${side}_$end", Material.WAXED_CUT_COPPER, x, 1f, z, .7f, .4f, .7f)
            }
            part("crosshead_$side", Material.WAXED_CUT_COPPER, x, 5.02f, -1.7f, .9f, .5f, 3.4f)
        }
        part("bridge_lower", Material.POLISHED_BLACKSTONE, -.875f, 5.1f, -1.7f, 11.6f, .22f, .85f)
        part("bridge_web", Material.WAXED_CUT_COPPER, -.875f, 5.37f, -1.7f, 11.6f, .32f, .3f)
        part("bridge_upper", Material.POLISHED_BLACKSTONE, -.875f, 5.6f, -1.7f, 11.6f, .1f, .85f)
        for (x in listOf(-4.5f, -1f, 2.5f)) {
            part("bridge_plate_$x", Material.WAXED_CUT_COPPER, x, 5.36f, -1.7f, .18f, .42f, .95f)
        }
        // Rear winding drum is visibly coupled to the overhead hoist and the operator's rope.
        part("drive_bed", Material.POLISHED_DEEPSLATE, -1.65f, .25f, -.15f, 2.2f, .5f, 1.55f)
        part("drive_axle", Material.IRON_BLOCK, -1.65f, 1.05f, -.15f, 2f, .16f, .16f)
        for (x in listOf(-2.45f, -.85f)) {
            part("drive_bearing_$x", Material.WAXED_CUT_COPPER, x, .9f, -.15f, .24f, .8f, .72f)
        }
        val returning = pose.returning.coerceIn(0f, 1f)
        val lifted = (if (pose.progress >= 2) 1f else pose.pull.coerceIn(0f, 1f)) * (1f - returning)
        part("drive_drum", Material.STRIPPED_SPRUCE_LOG, -1.65f, 1.05f, -.15f, 1.25f, .68f, .68f,
            rotation = Quaternionf().rotationX(lifted * (PI * 4).toFloat()))
        part("drive_chain_riser", Material.POLISHED_BLACKSTONE, -.85f, 3f, -.15f, .1f, 3.8f, .1f)
        part("drive_chain_top", Material.POLISHED_BLACKSTONE, -.85f, 4.9f, -.5f, .08f, .08f, .7f)
        // The haul line runs in front of the trolley envelope and enters its moving coupling.
        part("drive_haul_line", Material.POLISHED_BLACKSTONE, -.875f, 4.9f, -.85f, 10.75f, .07f, .07f)
        part("rope_fairlead", Material.WAXED_CUT_COPPER, -1.65f, .8f, .88f, .6f, .4f, .24f)
        val pullDistance = lifted * MineFloorActivity.PULL_DISTANCE.toFloat()
        part("pull_rope", Material.BROWN_TERRACOTTA, -1.65f, .95f, .88f + (.37f + pullDistance) / 2,
            .07f, .07f, .37f + pullDistance)
        part("pull_handle", Material.WAXED_CUT_COPPER, -1.65f, .95f, 1.25f + pullDistance,
            .75f, .16f, .2f, "rope")
        for (x in listOf(-2.42f, -.88f)) {
            part("pull_lane_$x", Material.WAXED_CUT_COPPER, x, .025f, 3.1f, .07f, .05f, 3.8f)
        }
        part("console_foot", Material.POLISHED_DEEPSLATE, .45f, .16f, 1.3f, .9f, .32f, .95f)
        part("console_body", Material.WAXED_CUT_COPPER, .45f, .65f, 1.3f, .65f, .66f, .55f)
        val leverRotation = Quaternionf().rotationX(pose.swing.coerceIn(0f, 1f) * -.55f)
        part("dispatch_lever", Material.RED_TERRACOTTA, .45f, 1.16f, 1.5f, .18f, .5f, .18f, "swing", leverRotation)
        val leverGrip = leverRotation.transform(Vector3f(0f, .25f, 0f)).add(.45f, 1.16f, 1.5f)
        part("dispatch_grip", Material.POLISHED_BLACKSTONE, leverGrip.x, leverGrip.y, leverGrip.z, .48f, .15f, .22f, "swing")

        // Source cradle and the perpendicular roller receiver form a complete material route.
        part("stock_pallet", Material.SPRUCE_PLANKS, 3f, .15f, -1.7f, 2.2f, .3f, 2f)
        for (z in listOf(-2.6f, -.8f)) part("stock_rail_$z", Material.POLISHED_BLACKSTONE, 3f, .24f, z, 2.3f, .18f, .12f)
        part("receiver", Material.POLISHED_DEEPSLATE, -4.5f, .3f, .7f, 2.3f, .6f, 5.7f)
        for (x in listOf(-5.52f, -3.48f)) part("receiver_frame_$x", Material.WAXED_CUT_COPPER, x, .81f, .7f, .16f, .38f, 5.7f)
        for (i in 0..7) part("receiver_roll_$i", Material.STRIPPED_SPRUCE_LOG, -4.5f, .85f, -1.7f + i * .65f,
            1.88f, .3f, .32f)
        part("receiver_stop", Material.WAXED_CUT_COPPER, -4.5f, 1.03f, 3.42f, 2.1f, .18f, .16f)

        val route = if (pose.progress >= 3) 1f else pose.swing.coerceIn(0f, 1f)
        val travel = (route / .5f).coerceIn(0f, 1f)
        val lower = ((route - .5f) / .2f).coerceIn(0f, 1f)
        val roll = ((route - .7f) / .3f).coerceIn(0f, 1f)
        val trolleyX = 3f - 7.5f * travel * (1f - returning)
        val cargoX = 3f - 7.5f * travel
        val cargoZ = -1.7f + 4.1f * roll
        val bottom = .3f + 2.25f * lifted - 1.55f * lower
        val hoistBottom = if (returning > 0f) 3.55f - 1.6f * ((returning - .6f) / .4f).coerceIn(0f, 1f)
            else bottom + 1.65f + .9f * (roll / .25f).coerceIn(0f, 1f)
        part("trolley", Material.WAXED_CUT_COPPER, trolleyX, 4.8f, -1.7f, 1.65f, .4f, 1.45f)
        part("trolley_drive", Material.IRON_BLOCK, trolleyX, 4.8f, -.9f, .38f, .28f, .22f)
        for (z in listOf(-2.18f, -1.22f)) part("trolley_wheel_$z", Material.POLISHED_BLACKSTONE,
            trolleyX, 5f, z, 1.2f, .3f, .18f)
        for (x in listOf(-.6f, .6f)) part("cable_$x", Material.IRON_BLOCK, trolleyX + x, (4.55f + hoistBottom) / 2, -1.7f,
            .06f, (4.55f - hoistBottom).coerceAtLeast(.06f), .06f)
        part("spreader", Material.POLISHED_BLACKSTONE, trolleyX, hoistBottom, -1.7f, 1.65f, .2f, .3f)
        for (x in listOf(-.6f, .6f)) part("hoist_hook_$x", Material.IRON_BLOCK,
            trolleyX + x, hoistBottom - .12f, -1.7f, .18f, .24f, .24f)
        val visible = returning == 0f
        part("crate", Material.BARREL, cargoX, bottom + .7f, cargoZ, 1.8f, 1.4f, 1.7f,
            if (pose.progress >= 3) "unload" else null, visible = visible)
        for (offset in listOf(-.62f, .62f)) part("cargo_band_$offset", Material.WAXED_CUT_COPPER,
            cargoX + offset, bottom + .7f, cargoZ, .12f, 1.44f, 1.74f, visible = visible)
        part("cargo_latch", Material.IRON_BLOCK, cargoX, bottom + .85f, cargoZ + .97f,
            .42f, .36f, .2f, if (pose.progress == 0) "hook" else null, visible = visible)
    }

    private fun pump(pose: Pose): List<Part> = buildList {
        fun part(id: String, material: Material, x: Float, y: Float, z: Float,
                 sx: Float, sy: Float, sz: Float, control: String? = null,
                 rotation: Quaternionf = Quaternionf()) {
            add(Part(id, material, Vector3f(x, y, z), Vector3f(sx, sy, sz), rotation, control))
        }
        // Two pressure vessels, one supported beam engine and a large open sump: no isolated decorative wheel.
        part("engine_base", Material.POLISHED_DEEPSLATE, -3.5f, .22f, -1.1f, 4.9f, .44f, 3.8f)
        part("pump_body", Material.WAXED_CUT_COPPER, -3.5f, 1.03f, -.5f, 1.55f, 1.18f, 1.4f)
        for ((i, x) in listOf(-5.8f, -1.2f).withIndex()) {
            part("tank_foot_$i", Material.POLISHED_DEEPSLATE, x, .25f, -2.7f, 2.5f, .5f, 2.6f)
            part("tank_body_$i", Material.WAXED_OXIDIZED_COPPER, x, 2.25f, -2.7f, 2.05f, 3.5f, 2.15f)
            for (y in listOf(.75f, 3.75f)) part("tank_band_${i}_$y", Material.WAXED_CUT_COPPER,
                x, y, -2.7f, 2.17f, .18f, 2.27f)
            part("tank_cap_$i", Material.WAXED_CUT_COPPER, x, 4.17f, -2.7f, 1.4f, .34f, 1.45f)
            part("tank_vent_$i", Material.POLISHED_BLACKSTONE, x, 4.52f, -2.7f, .42f, .36f, .42f)
            part("tank_connection_$i", Material.WAXED_CUT_COPPER, x, .95f, -1.17f, .32f, .32f, 1f)
        }
        part("tank_manifold", Material.WAXED_CUT_COPPER, -3.5f, .95f, -.67f, 4.6f, .38f, .38f)
        for (x in listOf(-4.25f, -2.75f)) part("beam_support_$x", Material.POLISHED_BLACKSTONE,
            x, 1.84f, -.8f, .22f, 2.8f, .28f)
        part("beam_axle", Material.IRON_BLOCK, -3.5f, 3.22f, -.8f, 1.9f, .2f, .2f)
        val angle = -.12f + .3f * pose.press.coerceIn(0f, 1f)
        val pivot = Vector3f(-3.5f, 3.22f, -.8f)
        val rotation = Quaternionf().rotationX(angle)
        val handleCenter = rotation.transform(Vector3f(0f, 0f, .3f)).add(pivot)
        part("beam", Material.STRIPPED_DARK_OAK_LOG, handleCenter.x, handleCenter.y, handleCenter.z,
            .4f, .4f, 3.2f, rotation = rotation)
        val front = rotation.transform(Vector3f(0f, 0f, 1.9f)).add(pivot)
        val grip = Vector3f(PUMP_OPERATOR_X, 1.68f - pose.press * .5f, front.z)
        part("handle_link", Material.IRON_BLOCK, PUMP_OPERATOR_X, (front.y + grip.y) / 2, front.z,
            .14f, front.y - grip.y, .14f)
        part("grip", Material.WAXED_CUT_COPPER, grip.x, grip.y, grip.z, .95f, .18f, .22f, "handle")
        val back = rotation.transform(Vector3f(0f, 0f, -1.3f)).add(pivot)
        part("piston", Material.IRON_BLOCK, -3.5f, (back.y + 1.62f) / 2, back.z,
            .22f, back.y - 1.62f, .22f)
        part("rear_cylinder", Material.WAXED_CUT_COPPER, -3.5f, 1.25f, -2.1f, .8f, 1.62f, .8f)

        // The removable section is on the forward manifold, so fitting it never requires walking through a vessel.
        part("pump_outlet", Material.WAXED_CUT_COPPER, -1.58f, .95f, .15f, 2.18f, .4f, .4f)
        part("inlet_fixed", Material.WAXED_OXIDIZED_COPPER, 1.76f, .95f, .15f, 1.42f, .4f, .4f)
        for ((id, x) in listOf("a" to -.43f, "b" to 1.03f)) part("flange_$id", Material.IRON_BLOCK,
            x, .95f, .15f, .12f, .64f, .64f, "socket")
        val pipe = pose.pipe ?: if (pose.progress > 0) PIPE_SOCKET else PIPE_RACK
        part("repair_pipe", Material.WAXED_CUT_COPPER, pipe.x, pipe.y, pipe.z, 1.34f, .4f, .4f,
            if (pose.progress == 0) "pipe" else null, Quaternionf().rotationY(pose.pipeYaw))
        part("pipe_rack", Material.SPRUCE_PLANKS, -6.25f, .35f, 2.4f, 2f, .7f, 1.1f)
        for (x in listOf(-6.95f, -5.55f)) part("rack_cradle_$x", Material.POLISHED_BLACKSTONE,
            x, .8f, 2.4f, .15f, .2f, .75f)
        part("valve_elbow", Material.WAXED_OXIDIZED_COPPER, 2.3f, .95f, -.55f, .46f, .46f, 1.4f)
        part("valve_stem", Material.IRON_BLOCK, 2.3f, 1.08f, .55f, .12f, .4f, .12f)
        part("valve_bracket", Material.WAXED_CUT_COPPER, 2.3f, .95f, .4f, .48f, .48f, .7f)
        val valveRotation = Quaternionf().rotationY(pose.drain.coerceAtMost(.2f) * 7.85f)
        part("valve", Material.RED_TERRACOTTA, 2.3f, 1.22f, .55f, 1f, .16f, .2f, "valve", valveRotation)
        part("valve_cross", Material.RED_TERRACOTTA, 2.3f, 1.22f, .55f, .2f, .16f, 1f, "valve", valveRotation)
        part("valve_hub", Material.IRON_BLOCK, 2.3f, 1.22f, .55f, .32f, .24f, .32f, "valve", valveRotation)

        part("basin_floor", Material.POLISHED_DEEPSLATE, 4.5f, .14f, -1.7f, 4.9f, .28f, 3.9f)
        for (x in listOf(2.16f, 6.84f)) part("basin_x$x", Material.DEEPSLATE_TILES,
            x, .88f, -1.7f, .22f, 1.2f, 3.9f)
        for (z in listOf(-3.54f, .14f)) part("basin_z$z", Material.DEEPSLATE_TILES,
            4.5f, .88f, z, 4.46f, 1.2f, .22f)
        for (x in listOf(2.16f, 6.84f)) part("basin_rim_$x", Material.WAXED_CUT_COPPER,
            x, 1.53f, -1.7f, .3f, .1f, 4.02f)
        val waterHeight = 1.05f * (1f - pose.drain).coerceIn(0f, 1f) + .035f
        part("water", Material.BLUE_STAINED_GLASS, 4.5f, .28f + waterHeight / 2, -1.7f, 4.44f, waterHeight, 3.44f)
        // Intake joins the manifold through the west wall and remains below the filled surface.
        part("suction_pipe", Material.WAXED_OXIDIZED_COPPER, 2.75f, .8f, -1.7f, 1.3f, .35f, .35f)
        part("suction_elbow", Material.WAXED_OXIDIZED_COPPER, 2.3f, .875f, -1.3f, .4f, .5f, .8f)
        part("intake", Material.POLISHED_BLACKSTONE, 3.25f, .65f, -1.7f, .48f, .6f, .48f)
        part("gauge_mount", Material.WAXED_CUT_COPPER, -1.2f, 1.9f, -1.43f, 1f, 1f, .35f)
        part("gauge", Material.QUARTZ_BLOCK, -1.2f, 1.9f, -1.2f, .8f, .8f, .1f)
        val pressure = (pose.progress - 1).coerceIn(0, 4) / 4f
        val needle = pressure * 1.8f - .9f
        part("needle", Material.RED_TERRACOTTA, -1.2f + .22f * sin(needle), 1.9f + .22f * cos(needle), -1.12f,
            .06f, .48f, .04f, rotation = Quaternionf().rotationZ(-needle))
        for (x in listOf(-4.2f, -2.8f)) part("operator_lane_$x", Material.WAXED_CUT_COPPER,
            x, .025f, 2.1f, .06f, .05f, 1.8f)
    }
}

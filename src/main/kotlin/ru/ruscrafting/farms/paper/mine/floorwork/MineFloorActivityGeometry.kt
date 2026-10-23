package ru.ruscrafting.farms.paper.mine.floorwork

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.MineFloorActivity
import ru.ruscrafting.farms.domain.MineIncidentType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Canonical, source-exportable poses. The anchor is the verified top of the platform. */
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

    val WINCH_CONTROLS = linkedMapOf(
        "hook" to Vector3f(2.2f, .95f, 0f),
        "rope" to Vector3f(-1.65f, .85f, 1.25f),
        "swing" to Vector3f(-.65f, 1.1f, .65f),
        "unload" to Vector3f(0f, 1.15f, 2.2f),
    )
    val PUMP_CONTROLS = linkedMapOf(
        "pipe" to Vector3f(-2.2f, .8f, 1.2f),
        "socket" to Vector3f(1.2f, .6f, .3f),
        "handle" to Vector3f(0f, 1.65f, 1.25f),
        "valve" to Vector3f(2.0f, 1.0f, .3f),
    )

    fun controls(type: MineIncidentType): Map<String, Vector3f> =
        if (type == MineIncidentType.FREIGHT_WINCH) WINCH_CONTROLS else PUMP_CONTROLS

    fun parts(type: MineIncidentType, pose: Pose): List<Part> =
        if (type == MineIncidentType.FREIGHT_WINCH) winch(pose) else pump(pose)

    private fun winch(pose: Pose): List<Part> = buildList {
        fun part(id: String, material: Material, x: Float, y: Float, z: Float,
                 sx: Float, sy: Float, sz: Float, control: String? = null,
                 rotation: Quaternionf = Quaternionf()) {
            add(Part(id, material, Vector3f(x, y, z), Vector3f(sx, sy, sz), rotation, control))
        }
        part("foundation", Material.POLISHED_DEEPSLATE, 0f, .12f, 0f, 1.35f, .24f, 1.35f)
        part("column", Material.STRIPPED_DARK_OAK_LOG, 0f, 1.65f, 0f, .45f, 2.85f, .45f)
        part("collar", Material.CUT_COPPER, 0f, 2.95f, 0f, .7f, .28f, .7f)
        val swing = if (pose.progress >= 3) 1f - pose.returning.coerceIn(0f, 1f) else pose.swing.coerceIn(0f, 1f)
        val angle = swing * (PI / 2).toFloat()
        val rotation = Quaternionf().rotationY(-angle)
        val x = 2.2f * cos(angle); val z = 2.2f * sin(angle)
        part("boom", Material.WAXED_CUT_COPPER, x / 2, 3.24f, z / 2, 2.65f, .28f, .3f, rotation = rotation)
        part("counterweight", Material.POLISHED_BLACKSTONE, -cos(angle) * .6f, 3.17f, -sin(angle) * .6f,
            .6f, .5f, .6f, rotation = rotation)
        val rewind = 1f - pose.returning.coerceIn(0f, 1f)
        val raised = (if (pose.progress >= 2) 1f else pose.pull.coerceIn(0f, 1f)) * rewind
        // Lower onto the receiving plinth only at the end of the swing.
        val lowering = ((swing - .7f) / .3f).coerceIn(0f, 1f)
        val bottom = .12f + raised * 1.45f - lowering * .92f
        val top = bottom + .85f
        val cableBottom = top + .1f
        part("cable", Material.IRON_BLOCK, x, (3.08f + cableBottom) / 2, z,
            .045f, (3.08f - cableBottom).coerceAtLeast(.06f), .045f)
        part("hook", Material.IRON_BLOCK, x, top + .045f, z, .3f, .12f, .24f, "hook")
        part("stock_pallet", Material.SPRUCE_PLANKS, 2.2f, .06f, 0f, 1.15f, .12f, 1.15f)
        part("crate", Material.BARREL, x, bottom + .425f, z, 1.05f, .85f, 1.05f,
            if (pose.progress >= 3) "unload" else "hook", rotation)
        for (side in listOf(-.35f, .35f)) {
            part("strap_$side", Material.STRIPPED_DARK_OAK_LOG, x + cos(angle) * side, bottom + .43f,
                z + sin(angle) * side, .1f, .89f, 1.08f, rotation = rotation)
        }
        part("receiver", Material.SPRUCE_PLANKS, 0f, .325f, 2.2f, 1.35f, .65f, 1.35f)
        part("receiver_rim_l", Material.CUT_COPPER, -.7f, .7f, 2.2f, .1f, .1f, 1.45f)
        part("receiver_rim_r", Material.CUT_COPPER, .7f, .7f, 2.2f, .1f, .1f, 1.45f)
        part("capstan_base", Material.POLISHED_DEEPSLATE, -1.65f, .12f, .85f, .85f, .24f, .85f)
        part("capstan_drum", Material.SPRUCE_LOG, -1.65f, .56f, .85f, .55f, .65f, .55f,
            rotation = Quaternionf().rotationY(raised * (PI * 4).toFloat()))
        part("pull_handle", Material.CUT_COPPER, -1.65f, .85f, 1.25f + pose.pull * rewind * MineFloorActivity.PULL_DISTANCE.toFloat(),
            .55f, .12f, .18f, "rope")
        val ropeLength = .4f + pose.pull * rewind * MineFloorActivity.PULL_DISTANCE.toFloat()
        part("pull_rope", Material.BROWN_TERRACOTTA, -1.65f, .8f, .9f + ropeLength / 2,
            .055f, .055f, ropeLength)
        part("swing_mount", Material.CUT_COPPER, -.65f, .8f, .65f, .38f, .25f, .38f)
        part("swing_lever", Material.IRON_BLOCK, -.65f, 1.08f, .65f, .1f, .55f, .1f, "swing",
            Quaternionf().rotationX(swing * .6f))
        // A thin physical guide makes the safe pulling lane readable without a particle carpet.
        for (side in listOf(-.77f, .77f)) {
            part("lane_$side", Material.CUT_COPPER, -1.65f + side, .025f, 3.05f, .06f, .05f, 3.75f)
        }
        if (pose.returning > 0f) for (i in indices) {
            if (get(i).id == "crate" || get(i).id.startsWith("strap_")) set(i, get(i).copy(visible = false))
        }
    }

    private fun pump(pose: Pose): List<Part> = buildList {
        fun part(id: String, material: Material, x: Float, y: Float, z: Float,
                 sx: Float, sy: Float, sz: Float, control: String? = null,
                 rotation: Quaternionf = Quaternionf()) {
            add(Part(id, material, Vector3f(x, y, z), Vector3f(sx, sy, sz), rotation, control))
        }
        part("base", Material.POLISHED_DEEPSLATE, 0f, .12f, 0f, 1.5f, .24f, 1.5f)
        part("pump_body", Material.WAXED_CUT_COPPER, 0f, .75f, 0f, .85f, 1.05f, .85f)
        val handleAngle = .26f * pose.press
        val rodTop = 1.67f - sin(handleAngle) * .25f
        part("piston", Material.IRON_BLOCK, 0f, (1.15f + rodTop) / 2, .15f, .2f, rodTop - 1.15f, .2f)
        part("hinge", Material.POLISHED_BLACKSTONE, 0f, 1.75f, -.1f, .3f, .24f, .3f)
        part("rear_support", Material.WAXED_CUT_COPPER, 0f, 1.45f, -.1f, .18f, .5f, .18f)
        part("handle", Material.STRIPPED_DARK_OAK_LOG, 0f, 1.75f - sin(handleAngle) * .7f, -.1f + cos(handleAngle) * .7f,
            .16f, .16f, 1.4f, "handle", Quaternionf().rotationX(handleAngle))
        part("grip", Material.CUT_COPPER, 0f, 1.75f - sin(handleAngle) * 1.4f, -.1f + cos(handleAngle) * 1.4f,
            .75f, .18f, .18f, "handle")
        part("inlet", Material.WAXED_OXIDIZED_COPPER, -.6f, .6f, 0f, .4f, .3f, .3f)
        part("outlet", Material.WAXED_CUT_COPPER, .65f, .6f, .3f, .5f, .3f, .3f)
        part("flange_a", Material.IRON_BLOCK, .92f, .6f, .3f, .1f, .5f, .5f, "socket")
        part("flange_b", Material.IRON_BLOCK, 1.65f, .6f, .3f, .1f, .5f, .5f, "socket")
        val pipe = pose.pipe ?: if (pose.progress > 0) Vector3f(1.28f, .6f, .3f) else Vector3f(-2.2f, .8f, 1.2f)
        part("repair_pipe", Material.CUT_COPPER, pipe.x, pipe.y, pipe.z, .66f, .32f, .32f,
            if (pose.progress == 0) "pipe" else null, Quaternionf().rotationY(pose.pipeYaw))
        part("pipe_rack", Material.SPRUCE_PLANKS, -2.2f, .3f, 1.2f, 1.15f, .6f, .65f)
        part("valve_pipe", Material.WAXED_OXIDIZED_COPPER, 1.95f, .6f, .3f, .5f, .3f, .3f)
        part("valve_stem", Material.IRON_BLOCK, 2f, .83f, .3f, .08f, .3f, .08f)
        part("valve", Material.RED_TERRACOTTA, 2f, 1f, .3f, .65f, .1f, .15f, "valve",
            Quaternionf().rotationY(pose.drain.coerceAtMost(.2f) * 7.85f))
        part("basin_floor", Material.POLISHED_DEEPSLATE, 2.7f, .1f, -.05f, 1.2f, .2f, 1.45f)
        for (side in listOf(-.61f, .61f)) part("basin_x$side", Material.DEEPSLATE_TILES,
            2.7f + side, .4f, -.05f, .12f, .6f, 1.57f)
        for (side in listOf(-.78f, .78f)) part("basin_z$side", Material.DEEPSLATE_TILES,
            2.7f, .4f, -.05f + side, 1.15f, .6f, .12f)
        val waterHeight = .4f * (1f - pose.drain).coerceIn(0f, 1f) + .035f
        part("water", Material.BLUE_STAINED_GLASS, 2.7f, .2f + waterHeight / 2, -.05f,
            1.08f, waterHeight, 1.33f)
        part("gauge", Material.QUARTZ_BLOCK, 0f, 1.02f, .48f, .52f, .48f, .1f)
        val pressure = ((pose.progress - 1).coerceIn(0, 4) / 4f)
        val needle = (pressure * 1.8f - .9f)
        part("needle", Material.RED_TERRACOTTA, .12f * sin(needle), 1.02f + .12f * cos(needle), .55f,
            .045f, .28f, .035f, rotation = Quaternionf().rotationZ(-needle))
    }
}

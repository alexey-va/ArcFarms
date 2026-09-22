package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The Dead Factory's inline-six emergency generator.  The model is deliberately
 * authored as an open service machine: the +X side is a service cutaway while
 * the -X side retains the heavy crankcase and guard plates.
 *
 * All coordinates are local to the generator anchor.  The skid stays inside
 * x=[-2.7,2.7], z=[-7.8,7.8], y=[0,7.5].
 */
internal object MineDieselGeneratorModel {
    const val kind = "factory_diesel_generator"

    private val cylinderZ = floatArrayOf(-2.75f, -1.65f, -.55f, .55f, 1.65f, 2.75f)
    val combustionPoints = cylinderZ.map { Vector3f(.16f, 4.94f, it) }
    val exhaustMouth = Vector3f(-2.08f, 7.42f, 4.18f)
    private val crankPhases = floatArrayOf(0f, (2f * PI / 3f).toFloat(), (4f * PI / 3f).toFloat(),
        (4f * PI / 3f).toFloat(), (2f * PI / 3f).toFloat(), 0f)

    fun model(): List<MineDisplayBlueprints.Part> = buildList {
        fun box(
            material: Material,
            x: Float, y: Float, z: Float,
            width: Float, height: Float, depth: Float,
            angle: Float = 0f,
            moving: Boolean = false,
            pivot: Vector3f = Vector3f(),
            motion: String = "fixed",
        ) {
            add(MineDisplayBlueprints.Part(material, Vector3f(x, y, z), Vector3f(width, height, depth),
                angle, moving, pivot, motion))
        }

        fun tube(material: Material, a: Vector3f, b: Vector3f, diameter: Float = .2f) {
            val dx = abs(b.x - a.x)
            val dy = abs(b.y - a.y)
            val dz = abs(b.z - a.z)
            val center = Vector3f(a).add(b).mul(.5f)
            // Straight runs meet the larger elbow collars face to face.
            when {
                dx >= dy && dx >= dz -> box(material, center.x, center.y, center.z,
                    max(.04f, dx), diameter, diameter)
                dy >= dx && dy >= dz -> box(material, center.x, center.y, center.z,
                    diameter, max(.04f, dy), diameter)
                else -> box(material, center.x, center.y, center.z,
                    diameter, diameter, max(.04f, dz))
            }
        }

        fun elbow(material: Material, point: Vector3f, diameter: Float = .3f) =
            box(material, point.x, point.y, point.z, diameter, diameter, diameter)

        fun hose(material: Material, points: List<Vector3f>, diameter: Float = .2f) {
            val route = points.filterIndexed { index, point ->
                if (index == 0 || index == points.lastIndex) true else {
                    val incoming = Vector3f(point).sub(points[index - 1])
                    val outgoing = Vector3f(points[index + 1]).sub(point)
                    incoming.dot(outgoing) <= 0f || Vector3f(incoming).cross(outgoing).lengthSquared() > .000001f
                }
            }
            val collarRadius = diameter * .65f
            fun inset(from: Vector3f, toward: Vector3f): Vector3f {
                val result = Vector3f(from)
                val dx = toward.x - from.x
                val dy = toward.y - from.y
                val dz = toward.z - from.z
                when {
                    abs(dx) >= abs(dy) && abs(dx) >= abs(dz) -> result.x += if (dx < 0f) -collarRadius else collarRadius
                    abs(dy) >= abs(dx) && abs(dy) >= abs(dz) -> result.y += if (dy < 0f) -collarRadius else collarRadius
                    else -> result.z += if (dz < 0f) -collarRadius else collarRadius
                }
                return result
            }
            route.zipWithNext().forEachIndexed { index, (a, b) ->
                val start = if (index > 0) inset(a, b) else a
                val end = if (index + 1 < route.lastIndex) inset(b, a) else b
                tube(material, start, end, diameter)
            }
            route.drop(1).dropLast(1).forEach { elbow(material, it, diameter * 1.3f) }
        }

        /** A segmented disk with its axis along local Z. */
        fun ringZ(
            material: Material,
            cx: Float, cy: Float, cz: Float,
            radius: Float, depth: Float,
            segments: Int = 16,
            motion: String = "fixed",
            pivot: Vector3f = Vector3f(cx, cy, cz),
            phase: Float = 0f,
        ) {
            val moving = motion != "fixed"
            val rim = min(.24f, max(.12f, radius * .11f))
            val step = 2f * PI.toFloat() / segments
            val tangent = max(.08f, 2f * (radius - rim / 2f) * tan(step / 2f) - .045f)
            repeat(segments) { index ->
                val a = phase + index * step
                box(material, cx + cos(a) * radius, cy + sin(a) * radius, cz,
                    rim, tangent, depth, a, moving, pivot, motion)
            }
        }

        fun spokesZ(
            material: Material,
            cx: Float, cy: Float, cz: Float,
            radius: Float, inner: Float, depth: Float,
            count: Int = 6,
            motion: String = "fixed",
            pivot: Vector3f = Vector3f(cx, cy, cz),
            phase: Float = 0f,
        ) {
            val moving = motion != "fixed"
            repeat(count) { index ->
                val a = phase + index * 2f * PI.toFloat() / count
                val mid = (radius + inner) / 2f
                box(material, cx + cos(a) * mid, cy + sin(a) * mid, cz,
                    radius - inner, .16f, depth, a, moving, pivot, motion)
            }
            box(Material.IRON_BLOCK, cx, cy, cz, inner * 2f, inner * 2f, depth * 1.7f,
                moving = moving, pivot = pivot, motion = motion)
        }

        fun arcZ(material: Material, cx: Float, cy: Float, cz: Float, radius: Float,
                 start: Float, end: Float, depth: Float, segments: Int = 8) {
            val rim = min(.16f, max(.1f, radius * .12f))
            val step = (end - start) / segments
            val tangent = max(.08f, 2f * (radius - rim / 2f) * tan(abs(step) / 2f) - .035f)
            repeat(segments) { index ->
                val a = start + (index + .5f) * step
                box(material, cx + cos(a) * radius, cy + sin(a) * radius, cz,
                    rim, tangent, depth, a)
            }
        }

        /**
         * The service side is intentionally open.  These six segments are the
         * rear half of a liner, leaving the +X piston path readable while the
         * -X side still reads as a continuous steel barrel.
         */
        fun halfLinerY(material: Material, cx: Float, cy: Float, cz: Float,
                       radius: Float, height: Float, segments: Int = 6) {
            val step = PI.toFloat() / segments
            val side = max(.12f, radius * .30f)
            repeat(segments) { index ->
                val a = PI.toFloat() / 2f + (index + .5f) * step
                box(material, cx + cos(a) * radius, cy, cz + sin(a) * radius,
                    side, height, side)
            }
        }

        // --- skid, mounts, and service guards ---------------------------------
        box(Material.POLISHED_DEEPSLATE, 0f, .16f, 0f, 5.28f, .32f, 15.35f)
        for (x in listOf(-2.28f, 2.28f)) {
            box(Material.POLISHED_BASALT, x, .52f, 0f, .34f, .5f, 14.65f)
            for (z in listOf(-6.65f, 6.65f)) {
                box(Material.CUT_COPPER, x, .43f, z, .72f, .18f, .72f)
                box(Material.IRON_BLOCK, x, .58f, z, .22f, .12f, .22f)
            }
        }
        for (z in listOf(-5.4f, -2.7f, 0f, 2.7f, 5.4f)) {
            box(Material.POLISHED_ANDESITE, 0f, .55f, z, 4.42f, .18f, .24f)
            box(Material.WEATHERED_CUT_COPPER, 0f, .69f, z, 3.62f, .1f, .16f)
        }
        // The +X guard is low enough to leave the pistons visible from the aisle.
        for (z in listOf(-3.8f, -1.9f, 0f, 1.9f, 3.8f)) {
            box(Material.POLISHED_BASALT, 2.43f, 1.12f, z, .18f, .92f, .16f)
            box(Material.YELLOW_TERRACOTTA, 2.43f, 1.62f, z, .28f, .12f, .22f)
        }

        // --- radiator pack and fan at the -Z end -------------------------------
        box(Material.BLACK_CONCRETE, 0f, 3.48f, -6.82f, 4.14f, 4.38f, .28f)
        for (x in listOf(-2.16f, 2.16f))
            box(Material.POLISHED_BASALT, x, 3.48f, -6.99f, .3f, 4.82f, .48f)
        for (y in listOf(1.15f, 5.81f))
            box(Material.POLISHED_BASALT, 0f, y, -6.99f, 4.0f, .28f, .48f)
        for (index in 0..16) {
            val y = 1.46f + index * .255f
            box(if (index % 2 == 0) Material.POLISHED_ANDESITE else Material.EXPOSED_CUT_COPPER,
                0f, y, -7.18f, 3.76f, .095f, .12f)
        }
        for (x in listOf(-1.72f, -.86f, 0f, .86f, 1.72f))
            box(Material.CUT_COPPER, x, 3.48f, -7.22f, .09f, 4.05f, .1f)
        ringZ(Material.POLISHED_ANDESITE, 0f, 3.48f, -7.28f, 1.76f, .14f,
            segments = 16)
        ringZ(Material.IRON_BLOCK, 0f, 3.48f, -7.38f, 1.52f, .12f,
            segments = 12, motion = "rotate")
        spokesZ(Material.POLISHED_BASALT, 0f, 3.48f, -7.39f, 1.43f, .28f, .11f,
            count = 6, motion = "rotate", pivot = Vector3f(0f, 3.48f, -7.39f))
        repeat(6) { index ->
            val a = index * PI.toFloat() / 3f
            box(Material.POLISHED_ANDESITE, cos(a) * .96f, 3.48f + sin(a) * .96f, -7.47f,
                .77f, .36f, .1f, a + .24f, moving = true,
                pivot = Vector3f(0f, 3.48f, -7.47f), motion = "rotate")
        }
        for (x in listOf(-1.98f, 1.98f))
            box(Material.YELLOW_CONCRETE, x, 5.92f, -7.27f, .34f, .18f, .08f)
        box(Material.RED_CONCRETE, 1.82f, 5.52f, -7.3f, .18f, .18f, .08f, motion = "signal")
        box(Material.WEATHERED_CUT_COPPER, 0f, 6.14f, -6.84f, 3.5f, .3f, .7f)
        box(Material.POLISHED_BASALT, 0f, .98f, -6.78f, 3.55f, .28f, .55f)

        // Coolant ports touch the lower and upper radiator tanks.  The black
        // runs are hoses; the copper collars are the visible port flanges.
        hose(Material.BLACK_CONCRETE, listOf(
            Vector3f(-1.45f, 1.7f, -6.55f), Vector3f(-1.45f, 1.7f, -4.15f),
            Vector3f(-1.45f, 2.15f, -4.15f), Vector3f(-1.45f, 2.15f, -3.55f)), .25f)
        hose(Material.BLACK_CONCRETE, listOf(
            Vector3f(1.25f, 5.35f, -6.55f), Vector3f(1.25f, 5.35f, -4.4f),
            Vector3f(2.15f, 5.35f, -4.4f), Vector3f(2.15f, 5.35f, -3.1f),
            Vector3f(.6f, 5.35f, -3.1f)), .25f)
        for (x in listOf(-1.45f, 1.25f))
            box(Material.CUT_COPPER, x, if (x < 0f) 1.7f else 5.35f, -6.55f, .46f, .46f, .3f)

        // Accessory drive: the crank turns a front pulley, a stepped belt run
        // climbs to the fan-height idler, and the idler's shaft enters the
        // radiator hub.  Every run is axis-aligned so each collar is a real
        // contact point instead of a diagonal display box.
        ringZ(Material.POLISHED_ANDESITE, 0f, 1.8f, -4.05f, .72f, .18f,
            segments = 12, motion = "rotate", pivot = Vector3f(0f, 1.8f, -4.05f))
        spokesZ(Material.POLISHED_BASALT, 0f, 1.8f, -4.05f, .64f, .25f, .16f,
            count = 6, motion = "rotate", pivot = Vector3f(0f, 1.8f, -4.05f))
        ringZ(Material.POLISHED_ANDESITE, 0f, 3.48f, -4.05f, .62f, .18f,
            segments = 12, motion = "rotate", pivot = Vector3f(0f, 3.48f, -4.05f))
        spokesZ(Material.POLISHED_BASALT, 0f, 3.48f, -4.05f, .54f, .22f, .16f,
            count = 6, motion = "rotate", pivot = Vector3f(0f, 3.48f, -4.05f))
        tube(Material.IRON_BLOCK, Vector3f(0f, 1.8f, -3.55f), Vector3f(0f, 1.8f, -4.05f), .24f)
        tube(Material.IRON_BLOCK, Vector3f(0f, 3.48f, -4.05f), Vector3f(0f, 3.48f, -7.28f), .2f)
        // A closed, same-plane belt: lower pulley gets its bottom arc and the
        // fan-height idler gets the top arc; straight tangents join both sides.
        arcZ(Material.BLACK_CONCRETE, 0f, 1.8f, -4.18f, .78f, PI.toFloat(), 2f * PI.toFloat(), .1f)
        arcZ(Material.BLACK_CONCRETE, 0f, 3.48f, -4.18f, .68f, 0f, PI.toFloat(), .1f)
        for (x in listOf(-.65f, .65f)) {
            box(Material.BLACK_CONCRETE, x, 2.64f, -4.18f, .12f, 1.48f, .1f)
        }

        // --- lower crankcase, open service side, and crankshaft ----------------
        // Keep the sump below the crank centerline; the service-side upper
        // crankcase remains open so the moving webs and journals are visible.
        box(Material.POLISHED_BLACKSTONE, 0f, .78f, 0f, 3.72f, .72f, 7.12f)
        box(Material.DEEPSLATE_BRICKS, -.9f, 1.35f, 0f, 1.55f, .42f, 6.78f)
        box(Material.POLISHED_BASALT, -1.62f, 3.72f, 0f, .46f, 2.8f, 6.7f)
        box(Material.POLISHED_BASALT, -1.89f, 3.7f, 0f, .18f, 3.2f, 6.5f)
        // Six bolted inspection covers keep the intact side legible as an
        // engine casting; the opposite side deliberately exposes the linkage.
        for (z in cylinderZ) {
            box(Material.WEATHERED_CUT_COPPER, -2.025f, 3.7f, z, .09f, 1.85f, .83f)
            box(Material.POLISHED_ANDESITE, -2.09f, 3.7f, z, .04f, 1.48f, .65f)
            for (y in listOf(2.91f, 4.49f)) for (dz in listOf(-.32f, .32f))
                box(Material.IRON_BLOCK, -2.12f, y, z + dz, .08f, .1f, .1f)
            box(Material.POLISHED_BASALT, -2.15f, 3.7f, z, .1f, .12f, .28f)
        }
        for (z in listOf(-3.42f, 3.42f)) {
            box(Material.DEEPSLATE_BRICKS, 0f, 3.06f, z, 3.55f, 2.22f, .32f)
            box(Material.CUT_COPPER, 0f, 4.15f, z, 3.72f, .18f, .16f)
        }
        // End bearings and seven short journals leave a visible crank line.
        for (z in listOf(-3.28f, -2.20f, -1.10f, 0f, 1.10f, 2.20f, 3.28f))
            box(Material.IRON_BLOCK, 0f, 1.8f, z, .3f, .3f, .72f)
        for (z in listOf(-3.05f, -1.95f, -.85f, .85f, 1.95f, 3.05f))
            box(Material.POLISHED_ANDESITE, 0f, 1.8f, z, .18f, .18f, .34f)

        // Each cylinder owns two crank webs, one pin, one piston crown and one
        // rod.  Piston and rod centers are intentionally local offsets from the
        // crank pivot; the parent presentation computes the live pin positions.
        cylinderZ.forEachIndexed { index, z ->
            val phase = crankPhases[index]
            val radius = .45f
            val webX = -radius * sin(phase)
            val webY = 1.8f + radius * cos(phase)
            val pivot = Vector3f(0f, 1.8f, z)
            for (webZ in listOf(z - .22f, z + .22f)) {
                box(Material.POLISHED_ANDESITE, webX, webY, webZ, .2f, .72f, .14f,
                    phase, moving = true, pivot = pivot, motion = "rotate")
            }
            box(Material.CUT_COPPER, webX, webY, z, .28f, .28f, .3f,
                phase, moving = true, pivot = pivot, motion = "rotate")
            box(Material.POLISHED_ANDESITE, 0f, 0f, 0f, .64f, .28f, .68f,
                moving = true, pivot = pivot, motion = "diesel_piston_$index")
            box(Material.CUT_COPPER, 0f, -.18f, 0f, .5f, .1f, .54f,
                moving = true, pivot = pivot, motion = "diesel_piston_$index")
            box(Material.IRON_BLOCK, 0f, 0f, 0f, .18f, 2.5f, .18f,
                moving = true, pivot = pivot, motion = "diesel_rod_$index")
        }

        // --- six cylinder barrels, heads, rocker covers, and injector lines ---
        cylinderZ.forEachIndexed { index, z ->
            halfLinerY(Material.POLISHED_BASALT, 0f, 4.18f, z, .46f, 1.72f)
            halfLinerY(Material.IRON_BLOCK, 0f, 5.0f, z, .44f, .18f)
            box(Material.POLISHED_ANDESITE, .2f, 5.22f, z, 1.9f, .46f, .9f)
            // Rear half-cover leaves the cam lobes and valve followers exposed.
            box(Material.WEATHERED_CUT_COPPER, -.26f, 5.63f, z, 1.05f, .32f, .78f)
            box(Material.CUT_COPPER, -.26f, 5.84f, z, .94f, .1f, .6f)
            for (x in listOf(-.28f)) {
                tube(Material.CUT_COPPER, Vector3f(x, 5.88f, z), Vector3f(x, 6.48f, z), .09f)
                box(Material.IRON_BLOCK, x, 6.52f, z, .14f, .14f, .14f)
            }
            // A small service bolt pair keeps each cylinder readable as its own unit.
            for (x in listOf(-.64f, .12f))
                box(Material.IRON_BLOCK, x, 5.92f, z, .12f, .1f, .12f)
        }
        box(Material.WEATHERED_CUT_COPPER, -.28f, 6.58f, 0f, .54f, .18f, 6.5f)
        for (z in cylinderZ)
            box(Material.IRON_BLOCK, -.28f, 6.72f, z, .16f, .16f, .52f)

        // --- exposed overhead camshaft and twelve direct valve followers -----
        val camX = .85f
        val camY = 6.5f
        val camPivot = Vector3f(camX, camY, 0f)
        box(Material.IRON_BLOCK, camX, camY, -.2f, .16f, .16f, 7.1f,
            moving = true, pivot = camPivot, motion = "diesel_cam")
        box(Material.POLISHED_BASALT, camX, 5.42f, 0f, .52f, .1f, 6.78f)
        for (z in listOf(-3.3f, -2.2f, -1.1f, 0f, 1.1f, 2.2f, 3.3f)) {
            box(Material.POLISHED_ANDESITE, camX, 5.98f, z, .42f, 1.04f, .14f)
            box(Material.CUT_COPPER, camX, camY, z, .48f, .48f, .16f)
            box(Material.IRON_BLOCK, camX, 6.79f, z, .18f, .1f, .12f)
        }
        cylinderZ.forEachIndexed { index, z ->
            for (inlet in listOf(false, true)) {
                val lobeZ = z + if (inlet) .23f else -.23f
                val phase = MineDieselGeneratorMotion.camAngle(index, inlet)
                val pivot = Vector3f(camX, camY, lobeZ)
                ringZ(Material.POLISHED_ANDESITE, camX, camY, lobeZ, .25f, .13f,
                    segments = 8, motion = "diesel_cam", pivot = pivot, phase = phase)
                box(Material.CUT_COPPER, camX + sin(phase) * .22f, camY - cos(phase) * .22f,
                    lobeZ, .18f, .42f, .15f, phase, true, pivot, "diesel_cam")
                val motion = "diesel_valve_${if (inlet) "inlet" else "exhaust"}_$index"
                box(Material.IRON_BLOCK, camX, 6.124f, lobeZ, .9f, .12f, .19f,
                    moving = true, motion = motion)
                box(Material.IRON_BLOCK, camX, 5.764f, lobeZ, .07f, .6f, .07f,
                    moving = true, motion = motion)
                box(Material.POLISHED_ANDESITE, camX, 5.43f, lobeZ, .24f, .07f, .24f,
                    moving = true, motion = motion)
                box(Material.POLISHED_BASALT, camX, 5.66f, lobeZ, .19f, .3f, .19f)
            }
        }
        // Separate timing belt connects the crank and cam at a 2:1 ratio.
        val timingZ = -3.8f
        fun timingPulley(cx: Float, cy: Float, r: Float, motion: String) {
            ringZ(Material.CUT_COPPER, cx, cy, timingZ, r, .12f, 12, motion)
            spokesZ(Material.IRON_BLOCK, cx, cy, timingZ, r - .07f, .14f, .1f, 4, motion)
            ringZ(Material.BLACK_CONCRETE, cx, cy, timingZ, r + .08f, .07f, 16)
        }
        timingPulley(0f, 1.8f, .35f, "rotate")
        timingPulley(camX, camY, .7f, "diesel_cam")
        val dx = camX; val dy = camY - 1.8f
        val length = kotlin.math.sqrt(dx * dx + dy * dy)
        val ratio = -.35f / length
        val tangent = kotlin.math.sqrt(1f - ratio * ratio)
        for (side in listOf(-1f, 1f)) {
            val nx = ratio * dx / length - side * tangent * dy / length
            val ny = ratio * dy / length + side * tangent * dx / length
            val a = Vector3f(nx * .43f, 1.8f + ny * .43f, timingZ)
            val b = Vector3f(camX + nx * .78f, camY + ny * .78f, timingZ)
            box(Material.BLACK_CONCRETE, (a.x + b.x) / 2, (a.y + b.y) / 2, timingZ,
                .07f, a.distance(b), .055f, atan2(-(b.x - a.x), b.y - a.y))
        }
        // +X inspection rails frame the open side without turning it into a box.
        for (z in listOf(-3.2f, -1.6f, 0f, 1.6f, 3.2f))
            box(Material.POLISHED_BASALT, 1.72f, 4.25f, z, .18f, 2.25f, .16f)
        for (y in listOf(3.16f, 5.34f))
            box(Material.CUT_COPPER, 1.72f, y, 0f, .2f, .18f, 6.55f)
        box(Material.YELLOW_CONCRETE, 1.83f, 5.05f, -2.75f, .08f, .34f, .54f)

        // --- exhaust manifold, turbo, intake, filter, muffler, and stack --------
        for (z in cylinderZ) {
            hose(Material.POLISHED_BLACKSTONE, listOf(
                Vector3f(-.72f, 5.28f, z), Vector3f(-1.25f, 5.28f, z),
                Vector3f(-1.82f, 5.28f, z), Vector3f(-1.82f, 5.48f, z)), .18f)
            box(Material.CUT_COPPER, -1.0f, 5.28f, z, .22f, .24f, .24f)
        }
        hose(Material.POLISHED_BASALT, listOf(
            Vector3f(-1.82f, 5.48f, -2.75f), Vector3f(-2.08f, 5.48f, -2.75f),
            Vector3f(-2.08f, 5.48f, 3.02f)), .3f)
        ringZ(Material.POLISHED_ANDESITE, -1.9f, 5.62f, 3.4f, .62f, .5f,
            segments = 12)
        ringZ(Material.CUT_COPPER, -1.9f, 5.62f, 3.7f, .44f, .18f, segments = 12)
        box(Material.POLISHED_BASALT, -1.9f, 5.62f, 3.52f, 1.08f, 1.08f, .5f)
        for (a in 0 until 8) {
            val angle = a * PI.toFloat() / 4f
            box(Material.IRON_BLOCK, -1.9f + cos(angle) * .7f, 5.62f + sin(angle) * .7f, 3.38f,
                .12f, .22f, .2f, angle)
        }
        hose(Material.POLISHED_BLACKSTONE, listOf(
            Vector3f(-1.48f, 6.0f, 3.4f), Vector3f(-.95f, 6.0f, 3.4f),
            Vector3f(-.95f, 6.35f, 3.4f), Vector3f(-.95f, 6.35f, 2.9f),
            Vector3f(-.15f, 6.35f, 2.9f), Vector3f(1.85f, 6.35f, 2.9f),
            Vector3f(1.85f, 5.98f, 2.9f)), .2f)
        // Intake plenum on the service side.
        for (z in cylinderZ) {
            hose(Material.BLACK_CONCRETE, listOf(
                Vector3f(.72f, 5.66f, z), Vector3f(1.3f, 5.66f, z),
                Vector3f(1.72f, 5.66f, z), Vector3f(1.72f, 5.98f, z)), .16f)
            box(Material.EXPOSED_CUT_COPPER, 1.05f, 5.66f, z, .2f, .2f, .2f)
        }
        box(Material.WEATHERED_CUT_COPPER, 1.85f, 5.98f, 0f, .32f, .32f, 6.15f)
        hose(Material.BLACK_CONCRETE, listOf(
            Vector3f(2.2f, 6.72f, 2.9f), Vector3f(2.2f, 7.05f, 2.9f),
            Vector3f(2.2f, 7.05f, 3.3f), Vector3f(-1.9f, 7.05f, 3.3f),
            Vector3f(-1.9f, 6.0f, 3.3f)), .24f)
        // Intake filter, made from open rings so the blue service element remains visible.
        box(Material.POLISHED_BASALT, 2.15f, 6.85f, 2.9f, 1.0f, .24f, 1.0f)
        ringZ(Material.POLISHED_ANDESITE, 2.15f, 6.85f, 2.98f, .52f, .16f, segments = 12)
        for (z in listOf(2.54f, 2.9f, 3.26f))
            box(Material.CYAN_STAINED_GLASS, 2.15f, 6.85f, z, .6f, .12f, .12f)
        // Exhaust muffler and the bounded smoke mouth (x=-2.12,y=7.28,z=4.25).
        box(Material.POLISHED_BASALT, -2.08f, 6.22f, 4.18f, 1.0f, .8f, 1.55f)
        box(Material.POLISHED_BASALT, -2.25f, 3.55f, 4.7f, .12f, 5.7f, .12f)
        box(Material.CUT_COPPER, -2.25f, .77f, 4.7f, .32f, .1f, .32f)
        for (z in listOf(3.55f, 4.18f, 4.81f))
            box(Material.CUT_COPPER, -2.08f, 6.22f, z, 1.08f, .14f, .18f)
        hose(Material.POLISHED_BLACKSTONE, listOf(
            Vector3f(-2.08f, 5.62f, 3.82f), Vector3f(-2.08f, 5.62f, 4.18f),
            Vector3f(-2.08f, 5.82f, 4.18f)), .28f)
        tube(Material.POLISHED_BASALT, Vector3f(-2.08f, 6.62f, 4.18f), Vector3f(-2.08f, 7.22f, 4.18f), .38f)
        // Open rim around the exhaust outlet; particles originate above it.
        for (dx in listOf(-.25f, .25f))
            box(Material.IRON_BLOCK, -2.08f + dx, 7.3f, 4.18f, .12f, .14f, .62f)
        for (dz in listOf(-.25f, .25f))
            box(Material.IRON_BLOCK, -2.08f, 7.3f, 4.18f + dz, .38f, .14f, .12f)
        box(Material.YELLOW_TERRACOTTA, -2.08f, 7.05f, 4.48f, .16f, .3f, .1f)

        // --- coupling, flywheel, and alternator at the +Z end ------------------
        box(Material.IRON_BLOCK, 0f, 1.8f, 3.65f, .36f, .36f, .95f)
        ringZ(Material.POLISHED_ANDESITE, 0f, 1.8f, 4.08f, 1.42f, .28f,
            segments = 16, motion = "rotate", pivot = Vector3f(0f, 1.8f, 4.08f))
        spokesZ(Material.POLISHED_BASALT, 0f, 1.8f, 4.08f, 1.32f, .34f, .22f,
            count = 8, motion = "rotate", pivot = Vector3f(0f, 1.8f, 4.08f))
        box(Material.CUT_COPPER, 0f, 1.8f, 4.43f, .68f, .68f, .22f,
            moving = true, pivot = Vector3f(0f, 1.8f, 4.08f), motion = "rotate")
        // Alternator centerline is the same Y=1.8 crank/flywheel axis.  Its
        // housing starts above the skid and the real shaft enters its hub.
        box(Material.POLISHED_BASALT, 0f, 1.8f, 5.5f, 3.0f, 2.6f, 2.0f)
        ringZ(Material.POLISHED_ANDESITE, 0f, 1.8f, 6.52f, 1.38f, .2f, segments = 16)
        ringZ(Material.CUT_COPPER, 0f, 1.8f, 6.66f, 1.16f, .14f, segments = 12)
        for (a in 0 until 16) {
            val angle = a * PI.toFloat() / 8f
            box(Material.IRON_BLOCK, cos(angle) * 1.52f, 1.8f + sin(angle) * 1.52f, 5.55f,
                .14f, .34f, 1.62f, angle)
        }
        for (x in listOf(-1.2f, 1.2f)) {
            box(Material.POLISHED_DEEPSLATE, x, .88f, 5.35f, .4f, .7f, 1.55f)
            box(Material.CUT_COPPER, x, .43f, 5.35f, .64f, .18f, 1.74f)
        }
        box(Material.COPPER_BLOCK, 1.45f, 2.75f, 5.72f, .42f, .54f, .72f)
        box(Material.REDSTONE_LAMP, 1.68f, 2.9f, 5.72f, .08f, .16f, .24f, motion = "signal")
        hose(Material.BLACK_CONCRETE, listOf(
            Vector3f(1.65f, 2.85f, 5.72f), Vector3f(2.0f, 2.85f, 5.72f),
            Vector3f(2.0f, 2.55f, 5.72f), Vector3f(2.18f, 2.55f, 5.72f)), .16f)

        // --- battery, starter leads, and service instrument cabinet -------------
        box(Material.POLISHED_DEEPSLATE, 2.0f, 1.02f, -4.78f, 1.18f, .3f, 1.9f)
        box(Material.POLISHED_BASALT, 2.0f, 1.68f, -4.78f, 1.02f, 1.1f, 1.68f)
        for (z in listOf(-5.3f, -4.78f, -4.26f)) {
            box(Material.COPPER_BLOCK, 2.0f, 1.95f, z, .82f, .36f, .36f)
            box(Material.REDSTONE_BLOCK, 2.0f, 2.2f, z, .24f, .12f, .2f)
        }
        for (z in listOf(-5.3f, -4.26f))
            hose(Material.BLACK_CONCRETE, listOf(
                Vector3f(2.0f, 2.3f, z), Vector3f(2.18f, 2.3f, z),
                Vector3f(2.18f, 2.55f, z)), .11f)
        box(Material.YELLOW_CONCRETE, 2.54f, 1.8f, -4.78f, .08f, .62f, .68f)

        // The cabinet is at the alternator end, on two feet, so it cannot
        // occlude the six-cylinder service path or float above the skid.
        for (z in listOf(4.2f, 5.35f)) {
            box(Material.POLISHED_DEEPSLATE, 2.08f, .52f, z, .86f, .2f, .36f)
            box(Material.POLISHED_BASALT, 2.08f, .95f, z, .28f, .72f, .28f)
        }
        box(Material.POLISHED_DEEPSLATE, 2.08f, 2.58f, 4.78f, .58f, 2.82f, 1.65f)
        box(Material.WEATHERED_CUT_COPPER, 2.4f, 2.64f, 4.78f, .1f, 2.38f, 1.38f)
        for (z in listOf(4.28f, 4.78f, 5.28f)) {
            box(Material.BLACK_CONCRETE, 2.47f, 3.1f, z, .06f, .6f, .42f)
            box(Material.CYAN_STAINED_GLASS, 2.53f, 3.1f, z, .05f, .4f, .28f)
            box(Material.IRON_BLOCK, 2.59f, 3.1f, z, .06f, .08f, .2f)
        }
        for (z in listOf(4.28f, 4.78f, 5.28f))
            box(Material.YELLOW_CONCRETE, 2.59f, 2.2f, z, .08f, .12f, .16f)
        box(Material.RED_CONCRETE, 2.59f, 1.75f, 4.78f, .08f, .2f, .22f, motion = "signal")
        box(Material.POLISHED_BLACKSTONE, 2.5f, 1.95f, 4.78f, .08f, .38f, 1.0f)
        for (z in listOf(4.42f, 5.14f))
            box(Material.IRON_BLOCK, 2.59f, 1.95f, z, .08f, .28f, .12f)
        hose(Material.BLACK_CONCRETE, listOf(
            Vector3f(2.51f, 1.8f, 4.78f), Vector3f(2.51f, 1.5f, 4.78f),
            Vector3f(2.18f, 1.5f, 4.78f), Vector3f(2.18f, 1.5f, -4.78f),
            Vector3f(2.18f, 2.55f, -4.78f)), .12f)

        // A pair of yellow service plaques and fasteners make the open cutaway
        // read as a maintained industrial machine rather than loose decoration.
        for (z in listOf(-2.75f, 2.75f)) {
            box(Material.YELLOW_TERRACOTTA, 2.05f, 5.8f, z, .08f, .28f, .5f)
            box(Material.IRON_BLOCK, 2.12f, 5.62f, z - .22f, .08f, .12f, .12f)
            box(Material.IRON_BLOCK, 2.12f, 5.62f, z + .22f, .08f, .12f, .12f)
        }
    }
}

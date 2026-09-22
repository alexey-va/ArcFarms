package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The modern Last Descent assemblies.  Anchors are floor points; the models
 * keep their working faces toward -Z so the lower deck can be read from the
 * approach aisle.
 */
internal object MineDescentModels {
    val kinds = setOf(
        "descent_brake_housing", "descent_tension_winch", "descent_brake_lever",
        "descent_battery_rack", "descent_power_socket", "descent_intake_valve",
        "descent_priming_wheel", "descent_starter_console", "descent_pump",
        "descent_upper_winder",
    )

    fun model(kind: String): List<MineDisplayBlueprints.Part> = buildList {
        require(kind in kinds) { "Unknown descent display model: $kind" }

        fun box(
            material: Material, x: Float, y: Float, z: Float,
            width: Float, height: Float, depth: Float,
            angle: Float = 0f, moving: Boolean = false,
            pivot: Vector3f = Vector3f(), motion: String = "rotate", idleHidden: Boolean = false,
        ) = add(MineDisplayBlueprints.Part(
            material, Vector3f(x, y, z), Vector3f(width, height, depth),
            angle, moving, pivot, motion, idleHidden,
        ))

        fun wheel(cx: Float, cy: Float, cz: Float, radius: Float, moving: Boolean = true) {
            val pivot = Vector3f(cx, cy, cz)
            val rimWidth = min(.22f, radius * .075f)
            val rimLength = 2f * (radius - rimWidth / 2f) * tan(PI.toFloat() / 16f) - .01f
            repeat(16) { index ->
                val angle = index * PI.toFloat() / 8f
                box(
                    Material.EXPOSED_CUT_COPPER,
                    cx + cos(angle) * radius, cy + sin(angle) * radius, cz,
                    rimWidth, rimLength, .28f, angle, moving, pivot,
                )
                if (index % 2 == 0) box(
                    Material.IRON_BLOCK,
                    cx + cos(angle) * (radius + rimWidth * .3f),
                    cy + sin(angle) * (radius + rimWidth * .3f), cz,
                    min(.28f, radius * .11f), min(.24f, radius * .11f), .38f,
                    angle, moving, pivot,
                )
            }
            val spokes = if (radius >= 2.5f) 8 else 6
            val inner = if (radius >= 2.5f) .62f else radius * .28f
            val outer = radius - rimWidth / 2f
            repeat(spokes) { index ->
                val angle = index * PI.toFloat() * 2f / spokes
                box(
                    if (radius >= 2.5f) Material.STRIPPED_DARK_OAK_LOG else Material.POLISHED_ANDESITE,
                    cx + cos(angle) * (outer + inner) / 2f,
                    cy + sin(angle) * (outer + inner) / 2f, cz,
                    outer - inner, if (radius >= 2.5f) .34f else .14f,
                    if (radius >= 2.5f) .34f else .18f,
                    angle, moving, pivot,
                )
            }
            box(Material.IRON_BLOCK, cx, cy, cz, inner * 2f, inner * 2f, .56f)
        }

        fun base(width: Float, depth: Float) {
            box(Material.POLISHED_DEEPSLATE, 0f, .16f, 0f, width, .32f, depth)
        }

        fun flange(x: Float, y: Float, z: Float, width: Float = .84f, depth: Float = .84f) {
            box(Material.POLISHED_ANDESITE, x, y, z, width, .18f, depth)
            box(Material.IRON_BLOCK, x, y + .13f, z, width * .62f, .08f, depth * .62f)
        }

        when (kind) {
            "descent_brake_housing" -> {
                base(4.8f, 3.6f)
                box(Material.DEEPSLATE_BRICKS, 0f, 1.55f, .22f, 4.05f, 2.7f, 2.35f)
                box(Material.POLISHED_BLACKSTONE, 0f, 1.62f, -1.04f, 2.75f, 1.95f, .14f)
                // Counterweight rubble is a real BREAK projection owned by the
                // objective, so the housing stays visible after the obstruction clears.
                box(Material.DEEPSLATE_TILES, 0f, .52f, -1.12f, 2.6f, .55f, .52f)
                box(Material.EXPOSED_CUT_COPPER, 0f, 1.65f, -1.19f, 1.92f, .15f, .08f)
                box(Material.IRON_BLOCK, 0f, 2.22f, -1.2f, .16f, 1.05f, .12f)
                flange(-1.93f, 1.65f, .23f, .72f, .72f)
                flange(1.93f, 1.65f, .23f, .72f, .72f)
                box(Material.IRON_BLOCK, -1.93f, 1.65f, .64f, .16f, .16f, .9f)
                box(Material.IRON_BLOCK, 1.93f, 1.65f, .64f, .16f, .16f, .9f)
                box(Material.CUT_COPPER, 0f, 2.92f, .25f, 3.72f, .22f, 2.05f)
            }
            "descent_tension_winch" -> {
                base(5.1f, 3.1f)
                box(Material.POLISHED_DEEPSLATE, 0f, 1.62f, .38f, 3.9f, 2.85f, 1.35f)
                box(Material.STRIPPED_DARK_OAK_LOG, 0f, 1.82f, .1f, 2.55f, 1.35f, .95f)
                for (x in listOf(-1.72f, 1.72f)) {
                    box(Material.POLISHED_ANDESITE, x, 1.84f, -.02f, .28f, 3.15f, .36f)
                    box(Material.CUT_COPPER, x, .55f, -.02f, .48f, .16f, .52f)
                }
                box(Material.IRON_BLOCK, 0f, 1.82f, -.92f, 3.42f, .18f, .18f)
                // A full walking ring has a clear 1–3 block envelope in front of the drum.
                wheel(0f, 1.82f, -1.24f, 1.47f)
                box(Material.POLISHED_BLACKSTONE, 0f, 1.82f, .02f, .64f, .64f, .72f)
                box(Material.IRON_BLOCK, 0f, 1.82f, -1.52f, .18f, .18f, .72f)
                box(Material.IRON_CHAIN, 0f, 2.92f, .43f, .12f, 2.1f, .12f)
            }
            "descent_brake_lever" -> {
                base(2.6f, 1.55f)
                box(Material.POLISHED_DEEPSLATE, 0f, 1.12f, .35f, 2.2f, 1.95f, .5f)
                box(Material.WEATHERED_CUT_COPPER, 0f, 1.65f, -.12f, 1.76f, 1.24f, .16f)
                box(Material.POLISHED_BLACKSTONE, 0f, 1.36f, -.23f, .7f, .12f, .42f)
                box(Material.IRON_BLOCK, 0f, 1.82f, -.24f, .12f, .86f, .12f,
                    moving = true, pivot = Vector3f(0f, 1.36f, -.24f), motion = "lever")
                box(Material.RED_CONCRETE, 0f, 2.28f, -.24f, .42f, .22f, .3f,
                    moving = true, pivot = Vector3f(0f, 1.36f, -.24f), motion = "lever")
                box(Material.SEA_LANTERN, -.6f, 1.72f, -.23f, .28f, .28f, .05f)
                box(Material.ORANGE_CONCRETE, .62f, 1.72f, -.23f, .25f, .25f, .06f, motion = "signal")
            }
            "descent_battery_rack" -> {
                base(4.6f, 2.25f)
                for (x in listOf(-1.9f, 1.9f)) {
                    box(Material.POLISHED_ANDESITE, x, 1.9f, 0f, .28f, 3.6f, .3f)
                    box(Material.CUT_COPPER, x, .52f, 0f, .48f, .18f, .52f)
                }
                for (y in listOf(.68f, 1.72f, 2.76f)) {
                    box(Material.POLISHED_DEEPSLATE, 0f, y, 0f, 4.05f, .18f, 1.82f)
                    for (x in listOf(-1.25f, 0f, 1.25f)) {
                        box(Material.COPPER_BLOCK, x, y + .38f, -.08f, .72f, .58f, .86f,
                            moving = false,
                            motion = if (x == 0f && y == .68f) "descent_cell" else "rack_battery")
                        box(Material.REDSTONE_BLOCK, x, y + .7f, -.08f, .32f, .15f, .4f,
                            motion = if (x == 0f && y == .68f) "descent_cell" else "rack_battery")
                    }
                }
                box(Material.WEATHERED_CUT_COPPER, 0f, 3.55f, 0f, 4.1f, .28f, 2f)
                box(Material.REDSTONE_LAMP, 0f, 3.72f, -.95f, .72f, .18f, .12f)
            }
            "descent_power_socket" -> {
                base(2.6f, 1.25f)
                box(Material.POLISHED_DEEPSLATE, 0f, 1.38f, .32f, 2.22f, 2.55f, .5f)
                box(Material.WEATHERED_CUT_COPPER, 0f, 1.52f, -.01f, 1.72f, 1.55f, .12f)
                box(Material.BLACK_CONCRETE, 0f, 1.52f, -.1f, .78f, .78f, .08f)
                box(Material.CYAN_STAINED_GLASS, 0f, 1.52f, -.16f, .46f, .46f, .04f)
                box(Material.SEA_LANTERN, 0f, 1.52f, -.2f, .25f, .25f, .04f)
                box(Material.COPPER_BLOCK, 0f, 1.52f, -.27f, .58f, .64f, .34f,
                    moving = false, motion = "descent_cell", idleHidden = true)
                for (x in listOf(-.68f, .68f)) {
                    box(Material.IRON_BLOCK, x, 2.35f, -.08f, .12f, .32f, .12f)
                    box(Material.IRON_BLOCK, x, .69f, -.08f, .12f, .32f, .12f)
                }
                box(Material.EXPOSED_CUT_COPPER, 0f, 2.88f, .3f, .34f, .72f, .34f)
            }
            "descent_intake_valve" -> {
                base(4.4f, 2.7f)
                box(Material.POLISHED_DEEPSLATE, 0f, .72f, .2f, 3.65f, 1.08f, 1.8f)
                box(Material.WEATHERED_CUT_COPPER, 0f, 1.42f, .18f, 3.15f, .42f, 1.25f)
                for (x in listOf(-1.62f, 1.62f)) flange(x, 1.42f, .18f, .76f, 1.1f)
                box(Material.EXPOSED_COPPER, 0f, 1.48f, .96f, .64f, .64f, .36f)
                wheel(0f, 1.88f, -1.02f, 1.08f)
                box(Material.IRON_BLOCK, 0f, 1.48f, -.08f, .2f, .2f, 1.9f)
                box(Material.CYAN_STAINED_GLASS, 1.2f, 1.75f, -.72f, .16f, .46f, .06f)
            }
            "descent_priming_wheel" -> {
                base(3.5f, 2.5f)
                box(Material.POLISHED_DEEPSLATE, 0f, .82f, .32f, 2.48f, 1.28f, 1.48f)
                box(Material.STRIPPED_DARK_OAK_LOG, 0f, 1.72f, .26f, .5f, 1.72f, .5f)
                box(Material.CUT_COPPER, 0f, 2.52f, .26f, 1.18f, .2f, 1.18f)
                // The player-facing handwheel is kept separate from the pump body.
                wheel(0f, 2.28f, -1.02f, 1.26f)
                box(Material.IRON_BLOCK, 0f, 2.28f, -.18f, .2f, .2f, 1.7f)
                box(Material.SEA_LANTERN, -.8f, 1.22f, -.72f, .28f, .28f, .06f)
            }
            "descent_starter_console" -> {
                base(3.6f, 2f)
                box(Material.POLISHED_DEEPSLATE, 0f, .82f, .22f, 3.1f, 1.28f, 1.4f)
                box(Material.WEATHERED_CUT_COPPER, 0f, 1.62f, -.3f, 2.85f, .5f, .3f)
                box(Material.POLISHED_BLACKSTONE, 0f, 1.78f, -.49f, 2.42f, .34f, .08f)
                for (x in listOf(-.8f, 0f, .8f)) {
                    box(Material.SEA_LANTERN, x, 1.82f, -.55f, .3f, .18f, .04f)
                    box(Material.BLACK_CONCRETE, x, 1.56f, -.55f, .34f, .12f, .05f)
                }
                box(Material.IRON_BLOCK, 0f, 2.12f, -.45f, .14f, .72f, .14f,
                    moving = true, pivot = Vector3f(0f, 1.58f, -.45f), motion = "lever")
                box(Material.RED_CONCRETE, 0f, 2.52f, -.45f, .42f, .2f, .3f,
                    moving = true, pivot = Vector3f(0f, 1.58f, -.45f), motion = "lever")
                box(Material.COPPER_BLOCK, 0f, 2.62f, .25f, 1.08f, .18f, .62f)
            }
            "descent_pump" -> {
                base(12.8f, 6.8f)
                // Four feet and a lower cross brace make the mass read as supported machinery.
                for (x in listOf(-4.75f, 4.75f)) for (z in listOf(-1.9f, 1.9f)) {
                    box(Material.POLISHED_DEEPSLATE, x, .82f, z, .72f, 1.45f, .72f)
                    box(Material.CUT_COPPER, x, .38f, z, .92f, .18f, .92f)
                }
                box(Material.POLISHED_ANDESITE, 0f, 1.42f, 0f, 10.4f, .32f, 4.55f)
                box(Material.DEEPSLATE_BRICKS, 0f, 4.48f, .34f, 8.6f, 5.85f, 4.2f)
                box(Material.POLISHED_BLACKSTONE, 0f, 3.04f, -.9f, 6.1f, 2.62f, 1.45f)
                box(Material.POLISHED_BASALT, 0f, 5.15f, -1.18f, 3.12f, 2.4f, .78f)
                box(Material.IRON_BLOCK, 0f, 5.15f, -1.68f, .58f, .58f, .72f)
                box(Material.IRON_BLOCK, 0f, 6.76f, .25f, .72f, 2.45f, .72f,
                    moving = true, pivot = Vector3f(0f, 5.38f, .25f), motion = "press")
                box(Material.POLISHED_ANDESITE, 0f, 7.78f, .25f, 2.05f, .28f, 1.76f,
                    moving = true, pivot = Vector3f(0f, 5.38f, .25f), motion = "press")
                box(Material.CUT_COPPER, 0f, 8.18f, .34f, 8.9f, .3f, 4.5f)
                // The large flywheel is on the -Z face, facing the approach aisle.
                wheel(0f, 4.12f, -2.45f, 2.82f)
                box(Material.POLISHED_BASALT, 0f, 4.12f, -1.94f, .72f, .72f, .72f)
                for (x in listOf(-3.8f, 3.8f)) {
                    box(Material.EXPOSED_CUT_COPPER, x, 4.15f, -.02f, .72f, .72f, 3.8f)
                    flange(x, 4.15f, -2.02f, .98f, .96f)
                    // Leave a hairline seam below the flange cap so the two
                    // steel faces remain distinct during the flywheel turn.
                    box(Material.IRON_BLOCK, x, 4.08f, -2.46f, .34f, .24f, .8f)
                }
                // Paired side runs explain the intake and discharge without floating props.
                box(Material.WEATHERED_CUT_COPPER, -5.0f, 3.45f, .22f, 1.22f, .66f, 3.8f)
                box(Material.WEATHERED_CUT_COPPER, 5.0f, 3.45f, .22f, 1.22f, .66f, 3.8f)
                flange(-5.0f, 3.45f, -1.82f, 1.1f, 1.1f)
                flange(5.0f, 3.45f, -1.82f, 1.1f, 1.1f)
                box(Material.SEA_LANTERN, 0f, 6.92f, -1.76f, .44f, .44f, .08f)
            }
            "descent_upper_winder" -> {
                base(5.2f, 2.8f)
                for (x in listOf(-1.9f, 1.9f)) {
                    box(Material.POLISHED_ANDESITE, x, 2.35f, .2f, .34f, 4.35f, .34f)
                    box(Material.CUT_COPPER, x, .54f, .2f, .55f, .18f, .58f)
                }
                box(Material.STRIPPED_DARK_OAK_LOG, 0f, 2.35f, .18f, 3.52f, 1.2f, 1.18f)
                for (x in listOf(-1.15f, -.58f, 0f, .58f, 1.15f))
                    box(Material.POLISHED_BASALT, x, 2.35f, .18f, .16f, 1.35f, 1.3f)
                wheel(0f, 2.35f, -1.08f, 1.28f)
                box(Material.IRON_BLOCK, 0f, 4.24f, .2f, 4.0f, .2f, .24f)
                box(Material.IRON_CHAIN, 0f, 3.34f, .88f, .12f, 1.8f, .12f)
            }
        }
    }
}

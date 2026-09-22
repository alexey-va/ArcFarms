package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.joml.Vector3f

/** Block-display models for the physical manual-crane console and buttons. */
internal object MineFactoryCraneModels {
    val kinds: Set<String> = buildSet {
        add("factory_crane_panel")
        addAll(MineFactoryCraneLayout.buttons.map { it.model })
    }

    fun model(kind: String): List<MineDisplayBlueprints.Part> = when (kind) {
        "factory_crane_panel" -> console()
        "factory_crane_button_left" -> button(Material.BLUE_CONCRETE, "left")
        "factory_crane_button_right" -> button(Material.BLUE_CONCRETE, "right")
        "factory_crane_button_forward" -> button(Material.BLUE_CONCRETE, "forward")
        "factory_crane_button_back" -> button(Material.BLUE_CONCRETE, "back")
        "factory_crane_button_lift" -> button(Material.LIME_CONCRETE, "lift")
        "factory_crane_button_lower" -> button(Material.RED_CONCRETE, "lower")
        else -> error("Unknown factory crane model: $kind")
    }

    private fun part(
        material: Material,
        x: Float,
        y: Float,
        z: Float,
        width: Float,
        height: Float,
        depth: Float,
        motion: String = "fixed",
    ) = MineDisplayBlueprints.Part(
        material = material,
        center = Vector3f(x, y, z),
        size = Vector3f(width, height, depth),
        motion = motion,
    )

    private fun console(): List<MineDisplayBlueprints.Part> = listOf(
        part(Material.POLISHED_DEEPSLATE, 0f, .08f, 0f, 4.65f, .16f, 1.65f),
        // The tall rear panel makes both button rows physically mounted; the
        // front face remains at +Z where the native Interaction hitboxes sit.
        part(Material.POLISHED_BASALT, 0f, 1.25f, -.04f, 4.35f, 2.35f, .82f),
        // Slight overhang keeps the trim's side faces off the basalt side
        // planes while preserving the solid supported silhouette.
        part(Material.WEATHERED_CUT_COPPER, 0f, 2.45f, .05f, 4.45f, .16f, .92f),
        part(Material.POLISHED_BLACKSTONE, 0f, 1.52f, .44f, 4.15f, 1.82f, .12f),
        part(Material.EXPOSED_CUT_COPPER, .95f, 1.52f, .52f, .055f, 1.80f, .025f),
        part(Material.SEA_LANTERN, -.65f, 1.575f, .54f, .12f, .12f, .04f),
    )

    /** Each cap is a front-facing .72 square on the panel, with a usable .86 backing. */
    private fun button(cap: Material, direction: String): List<MineDisplayBlueprints.Part> {
        val arrow = Material.WHITE_CONCRETE
        // A stepped triangular arrowhead reads as a direction at player
        // distance; a rectangular crossbar looked like an unrelated hammer.
        val arrowPixels = listOf(
            floatArrayOf(0f, -.11f, .10f, .26f),
            floatArrayOf(0f, .06f, .42f, .08f),
            floatArrayOf(0f, .14f, .26f, .08f),
            floatArrayOf(0f, .22f, .10f, .08f),
        )
        val shape = arrowPixels.map { pixel ->
            val (x, y, w, h) = pixel
            when (direction) {
                "left" -> part(arrow, -y, x, .17f, h, w, .04f)
                "right" -> part(arrow, y, x, .17f, h, w, .04f)
                "back", "lower" -> part(arrow, x, -y, .17f, w, h, .04f)
                else -> part(arrow, x, y, .17f, w, h, .04f)
            }
        } + if (direction == "lift" || direction == "lower") listOf(
            part(arrow, 0f, -.31f, .17f, .42f, .045f, .04f),
        ) else emptyList()
        return listOf(
            // Button origin is the centre of its vertical face.  Keeping Y at
            // zero prevents a floating plate and aligns both rows to the rear
            // panel; depth is along +Z toward the player.
            part(Material.POLISHED_DEEPSLATE, 0f, 0f, 0f, .86f, .86f, .10f),
            part(cap, 0f, 0f, .08f, .72f, .72f, .12f),
        ) + shape
    }
}

package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Material
import org.bukkit.util.Vector

/**
 * Fixed floor-console geometry for the connected-factory manual crane.
 *
 * The parent fixture resolver owns the named [anchor]; this file only owns
 * the relative console and button poses, so an edited factory can move the
 * whole control panel without changing interaction code.
 */
internal object MineFactoryCraneLayout {
    // Keep the wide panel beside the operator's sightline to the roller deck.
    const val YAW = 90
    data class Button(
        val id: String,
        val key: String,
        val offset: Vector,
        val material: Material,
        val model: String,
    ) {
        fun fixture() = MineFactoryExperimentLayout.Fixture(
            id = id,
            anchor = "crane_control",
            offset = Vector(offset.z, offset.y, -offset.x),
            model = model,
            scale = 1f,
            yaw = YAW,
            interactive = true,
        )
    }

    /** Non-actionable body target; the six button hitboxes own all input. */
    val console = MineFactoryExperimentLayout.Fixture(
        id = "crane_control",
        anchor = "crane_control",
        offset = Vector(0.0, 0.0, 0.0),
        model = "factory_crane_panel",
        scale = 1f,
        yaw = YAW,
        interactive = false,
    )

    // A familiar directional cross and a separate lift/lower column. The
    // native .86 hitboxes never overlap neighbouring controls at default yaw.
    val buttons: List<Button> = listOf(
        Button("crane_left", "crane-left", Vector(-1.65, 1.575, 0.42), Material.BLUE_CONCRETE, "factory_crane_button_left"),
        Button("crane_forward", "crane-forward", Vector(-.65, 2.05, 0.42), Material.BLUE_CONCRETE, "factory_crane_button_forward"),
        Button("crane_right", "crane-right", Vector(.35, 1.575, 0.42), Material.BLUE_CONCRETE, "factory_crane_button_right"),
        Button("crane_back", "crane-back", Vector(-.65, 1.10, 0.42), Material.BLUE_CONCRETE, "factory_crane_button_back"),
        Button("crane_lift", "crane-lift", Vector(1.6, 2.05, 0.42), Material.LIME_CONCRETE, "factory_crane_button_lift"),
        Button("crane_lower", "crane-lower", Vector(1.6, 1.10, 0.42), Material.RED_CONCRETE, "factory_crane_button_lower"),
    )

    fun staticFixtures(): List<MineFactoryExperimentLayout.Fixture> =
        listOf(console) + buttons.map(Button::fixture)

    fun button(id: String): Button? = buttons.firstOrNull { it.id == id }
}

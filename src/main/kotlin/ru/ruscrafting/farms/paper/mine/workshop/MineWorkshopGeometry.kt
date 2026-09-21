package ru.ruscrafting.farms.paper.mine.workshop

import org.bukkit.Location
import org.joml.Vector3f

/**
 * The authored ORE_WORKSHOP footprint shared by the packet scene, hitboxes and
 * offline previews.  The world points are deliberately kept here instead of
 * being repeated in a renderer so a control cannot drift away from its model.
 */
internal object MineWorkshopGeometry {
    data class Anchor(val x: Double, val y: Double, val z: Double)

    const val MIN_X = 35.0
    const val MAX_X = 53.0
    const val MIN_Y = 111.0
    const val MAX_Y = 119.0
    const val MIN_Z = 13.0
    const val MAX_Z = 22.0
    const val SERVICE_AISLE_MIN_Z = 23.0
    const val LINE_Z = 17.5
    const val FURNACE_YAW_RADIANS = 1.5707964f

    /** Station anchors are the same points used by the authored working plan. */
    val STATIONS: Map<String, Anchor> = linkedMapOf(
        "ore" to Anchor(36.5, 111.0, 20.5),
        "crusher" to Anchor(40.5, 111.0, 17.5),
        "furnace" to Anchor(46.5, 111.0, 17.5),
        "output" to Anchor(50.5, 111.0, 17.5),
        "shipping" to Anchor(51.5, 111.0, 21.5),
    )

    /**
     * Control centers are relative to the owning machine anchor.  Every point
     * is on the south face so an operator can use the z >= 23 service aisle.
     */
    val CONTROL_OFFSETS: Map<String, Vector3f> = mapOf(
        "crusher_feed" to Vector3f(0f, 1.0f, 2.70f),
        "crusher_drive" to Vector3f(1.72f, 1.48f, 1.94f),
        "furnace_air" to Vector3f(.15f, 1.48f, 1.95f),
        "furnace_tap" to Vector3f(1.15f, 1.48f, 1.95f),
    )

    fun controlLocation(anchor: Location, control: String): Location {
        val offset = CONTROL_OFFSETS.getValue(control)
        return anchor.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
    }

    /** South-facing label offset used by the packet caption attached to a control. */
    fun captionLocation(control: Location): Location = control.clone().add(0.0, .80, .18)

    /** Connector endpoints used by the preview and by the one-way display poses. */
    val CRUSHER_TO_FURNACE = listOf(
        Vector3f(-1.15f, .86f, 0f),
        Vector3f(4.45f, .86f, 0f),
    )
    /**
     * Same route expressed in the conveyor model's unscaled local units. The
     * runtime assembly is scaled by .45 and offset by +x 2.8, so these values
     * resolve to CRUSHER_TO_FURNACE after the display transform.
     */
    val CONVEYOR_CARGO = listOf(
        Vector3f(-8.777778f, 1.911111f, 0f),
        Vector3f(3.666667f, 1.911111f, 0f),
    )
    val FURNACE_TO_CASTING = listOf(
        Vector3f(-2.5f, 1.29f, 0f),
        Vector3f(1f, 1.29f, 0f),
    )
    val CASTING_TO_RACK = listOf(
        // The formed billet sits on the rolls; its bottom meets the molten surface.
        Vector3f(1f, 1.40f, 0f),
        Vector3f(1f, 1.40f, 4.55f),
    )
    val PICKUP_OFFSET: Vector3f get() = Vector3f(CASTING_TO_RACK.last())
}

package ru.ruscrafting.farms.paper.farm.care

import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmSpatialSeed

/** Deterministic, connected and deliberately uneven crop-bed footprints for rescue ditches. */
internal object FarmDitchLayout {
    data class Offset(val x: Int, val z: Int)

    fun offsets(placementSequence: Long): List<Offset> {
        val selection = FarmSpatialSeed.mix(
            placementSequence,
            FarmCareType.DITCH_RESCUE.ordinal * 17L + 101L,
        )
        return offsetsForSelection(selection)
    }

    fun offsetsForSelection(selection: Long): List<Offset> =
        TEMPLATES[Math.floorMod(selection, TEMPLATES.size.toLong()).toInt()]

    private val TEMPLATES = listOf(
        listOf(
            Offset(0, 0), Offset(-1, 0), Offset(1, 0), Offset(-2, 0),
            Offset(-1, -1), Offset(0, -1), Offset(1, -1), Offset(0, 1), Offset(1, 1), Offset(0, 2),
        ),
        listOf(
            Offset(0, 0), Offset(0, -1), Offset(0, -2), Offset(1, -2),
            Offset(-1, -1), Offset(1, -1), Offset(-1, 0), Offset(1, 0), Offset(2, 0), Offset(0, 1), Offset(1, 1),
        ),
        listOf(
            Offset(0, 0), Offset(-1, 0), Offset(1, 0), Offset(-2, 0),
            Offset(-2, -1), Offset(-1, -1), Offset(0, -1), Offset(0, 1), Offset(1, 1),
        ),
        listOf(
            Offset(0, 0), Offset(-1, 0), Offset(1, 0), Offset(2, 0),
            Offset(-1, -1), Offset(0, -1), Offset(1, -1), Offset(-1, 1), Offset(0, 1), Offset(0, 2), Offset(-1, 2), Offset(1, 1),
        ),
    )
}

package ru.ruscrafting.farms.paper.farm.care

import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmSpatialSeed

/** Deterministic, connected and deliberately uneven crop-bed footprints for rescue ditches. */
internal object FarmDitchLayout {
    data class Cell(val x: Int, val z: Int, val depth: Int) {
        init {
            require(depth in 2..4)
        }
    }

    fun cells(placementSequence: Long): List<Cell> {
        val selection = FarmSpatialSeed.mix(
            placementSequence,
            FarmCareType.DITCH_RESCUE.ordinal * 17L + 101L,
        )
        return cellsForSelection(selection)
    }

    fun cellsForSelection(selection: Long): List<Cell> =
        TEMPLATES[Math.floorMod(selection, TEMPLATES.size.toLong()).toInt()]

    private fun template(originX: Int, originZ: Int, vararg rows: String): List<Cell> =
        rows.flatMapIndexed { z, row ->
            row.mapIndexedNotNull { x, symbol ->
                symbol.digitToIntOrNull()?.let { depth -> Cell(x - originX, z - originZ, depth) }
            }
        }.sortedWith(compareBy<Cell> { kotlin.math.abs(it.x) + kotlin.math.abs(it.z) }.thenBy { it.z }.thenBy { it.x })

    private val TEMPLATES = listOf(
        template(
            3, 3,
            "  222  ",
            " 23332 ",
            "2333332",
            "2334332",
            " 33332 ",
            "  222  ",
        ),
        template(
            3, 3,
            " 2222  ",
            "233332 ",
            "2343332",
            "2334332",
            " 33332 ",
            "  222  ",
        ),
        template(
            3, 3,
            "   22  ",
            " 23332 ",
            "2334332",
            "2344332",
            "233332 ",
            " 2222  ",
        ),
        template(
            3, 3,
            "  222  ",
            " 233332",
            "2334332",
            "2344332",
            " 33332 ",
            " 222   ",
        ),
    )
}

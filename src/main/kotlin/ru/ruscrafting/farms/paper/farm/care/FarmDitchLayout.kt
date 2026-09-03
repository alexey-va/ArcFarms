package ru.ruscrafting.farms.paper.farm.care

import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmSpatialSeed

/** Deterministic, connected and deliberately uneven crop-bed footprints for rescue ditches. */
internal object FarmDitchLayout {
    data class Cell(val x: Int, val z: Int, val depth: Int) {
        init {
            require(depth in 3..6)
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
            5, 4,
            "...33333...",
            "..3444443..",
            ".344555443.",
            "34455655443",
            "34556665543",
            "34455655443",
            ".344555443.",
            "..3444443..",
            "...33333...",
        ),
        template(
            5, 4,
            "..33333....",
            ".3444443...",
            "344555443..",
            "3455665543.",
            "34566655443",
            ".3456655443",
            ".344555443.",
            "..3444443..",
            "...3333....",
        ),
        template(
            5, 4,
            "....3333...",
            "..3444443..",
            ".344555443.",
            "34455655443",
            "34556665543",
            "3445665543.",
            ".344555443.",
            "..3444443..",
            "...33333...",
        ),
        template(
            5, 4,
            "...3333....",
            "..3444443..",
            ".344555443.",
            ".3456655443",
            "34566655443",
            "3455665543.",
            "344555443..",
            ".3444443...",
            "..33333....",
        ),
    )
}

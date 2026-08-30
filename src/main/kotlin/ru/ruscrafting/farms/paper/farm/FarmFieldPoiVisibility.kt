package ru.ruscrafting.farms.paper.farm

/** Shared display tracking ranges for farm points of interest. Vanilla display range units span 64 blocks. */
internal object FarmFieldPoiVisibility {
    private const val BLOCKS_PER_VIEW_RANGE_UNIT = 64.0f
    private const val FULL_FIELD_VIEW_RANGE = 3.0f

    fun nearby(distanceBlocks: Float): Float = distanceBlocks / BLOCKS_PER_VIEW_RANGE_UNIT

    fun fullField(configured: Float): Float = maxOf(configured, FULL_FIELD_VIEW_RANGE)
}

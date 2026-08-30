package ru.ruscrafting.farms.paper.farm

/** Shared display tracking ranges for farm points of interest. Vanilla display range units span 64 blocks. */
internal object FarmFieldPoiVisibility {
    const val NEARBY_VIEW_RANGE = 10.0f / 64.0f
    private const val FULL_FIELD_VIEW_RANGE = 3.0f

    fun fullField(configured: Float): Float = maxOf(configured, FULL_FIELD_VIEW_RANGE)
}

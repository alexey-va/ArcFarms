package ru.ruscrafting.farms.paper.mine.workshop

/**
 * Runtime-only visual state for the authored workshop.  Process ownership and
 * persistence stay in the mine working domain; this DTO only carries the
 * normalized values needed by packet animation.
 */
internal data class MineWorkshopVisualState(
    val running: Boolean = false,
    val crushing: Boolean = false,
    /** -1 when inactive, otherwise the one-way crusher-to-furnace progress. */
    val transfer: Float = -1f,
    /** Furnace temperature normalized to the domain's relative gauge range. */
    val temperature: Float = 0f,
    val airOpen: Boolean = false,
    val heatReady: Boolean = false,
    /** -1 when inactive, otherwise the one-way pour/cooling progress. */
    val pouring: Float = -1f,
) {
    init {
        require(transfer in -1f..1f) { "transfer must be -1 or normalized: $transfer" }
        require(pouring in -1f..1f) { "pouring must be -1 or normalized: $pouring" }
        require(temperature in 0f..1f) { "temperature must be normalized: $temperature" }
    }

    val transferActive: Boolean get() = transfer >= 0f
    val pouringActive: Boolean get() = pouring >= 0f
    val heatVisible: Boolean get() = running && (temperature > 0f || heatReady || pouringActive)
}

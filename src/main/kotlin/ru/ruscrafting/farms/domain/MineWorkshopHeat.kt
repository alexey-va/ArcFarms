package ru.ruscrafting.farms.domain

/** A single six-second furnace run with a latched ready result. */
data class MineWorkshopHeat(
    val running: Boolean = false,
    val elapsedMillis: Long = 0L,
) {
    init {
        require(elapsedMillis in 0L..REQUIRED_MILLIS)
    }

    val ready: Boolean get() = elapsedMillis >= REQUIRED_MILLIS
    val progress: Double get() = (elapsedMillis.toDouble() / REQUIRED_MILLIS).coerceIn(0.0, 1.0)

    /** Start the cycle exactly once. */
    fun start(): MineWorkshopHeat = if (ready || running) this else copy(running = true)

    /** Advance the automatic cycle; no progress is made before [start]. */
    fun tick(elapsedMillis: Long): MineWorkshopHeat {
        if (!running || ready || elapsedMillis <= 0L) return this
        val elapsed = (this.elapsedMillis + elapsedMillis.coerceAtMost(1_000L)).coerceAtMost(REQUIRED_MILLIS)
        return copy(running = elapsed < REQUIRED_MILLIS, elapsedMillis = elapsed)
    }

    companion object {
        const val REQUIRED_MILLIS = 6_000L
        const val RUN_MILLIS = REQUIRED_MILLIS
    }
}

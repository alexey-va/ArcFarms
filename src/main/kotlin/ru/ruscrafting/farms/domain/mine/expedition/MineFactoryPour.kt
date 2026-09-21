package ru.ruscrafting.farms.domain.mine.expedition

/** Open the ladle, then close it in the broad 100..130% green zone. */
internal data class MineFactoryPour(val startedAt: Long) {
    /** The nominal mould fill is 100%; the display intentionally continues to 130%. */
    fun level(now: Long) = ((now - startedAt).toDouble() / NOMINAL_MILLIS).coerceAtLeast(0.0)
    fun ready(now: Long) = level(now) in READY_START..READY_END
    fun overflow(now: Long) = level(now) > READY_END

    companion object {
        const val NOMINAL_MILLIS = 10_000L
        const val READY_START = 1.0
        const val READY_END = 1.3
    }
}

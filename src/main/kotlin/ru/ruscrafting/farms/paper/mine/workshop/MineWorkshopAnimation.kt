package ru.ruscrafting.farms.paper.mine.workshop

/**
 * Pure timing rules shared by the live packet renderer, preview validation and
 * tests. A pour has two visible one-way stages: hot metal reaches the form,
 * then the formed billet travels to the cooling rack.
 */
internal object MineWorkshopAnimation {
    const val POUR_SPLIT = .4f
    const val RESET_HIDE_MILLIS = 100L
    private const val FEED_HIDE_FRACTION = .08f

    fun hotVisible(progress: Float): Boolean = progress in 0f..<POUR_SPLIT

    fun coolingVisible(progress: Float): Boolean = progress in POUR_SPLIT..1f

    fun hotProgress(progress: Float): Float =
        (progress / POUR_SPLIT).coerceIn(0f, 1f)

    fun coolingProgress(progress: Float): Float =
        ((progress - POUR_SPLIT) / (1f - POUR_SPLIT)).coerceIn(0f, 1f)

    /** Hide the falling feed briefly at its reset boundary, never mid-flight. */
    fun feedVisible(progress: Float): Boolean =
        progress.mod(1f) in FEED_HIDE_FRACTION..(1f - FEED_HIDE_FRACTION)

    fun feedProgress(cycle: Float, offset: Float): Float =
        (cycle + offset).mod(1f)

    fun cycleProgress(now: Long, period: Long): Float =
        (now % period).toFloat() / period.toFloat()

    fun hiddenUntil(now: Long): Long = now + RESET_HIDE_MILLIS

    fun canReappear(now: Long, hiddenUntil: Long?): Boolean =
        hiddenUntil == null || now >= hiddenUntil
}

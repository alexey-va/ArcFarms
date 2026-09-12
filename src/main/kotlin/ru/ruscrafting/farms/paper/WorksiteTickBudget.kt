package ru.ruscrafting.farms.paper

/**
 * Cooperative per-tick budget for bounded world scans.
 *
 * Callers ask before each operation. The monotonic clock is sampled only at
 * the configured checkpoint interval, keeping the hot loop cheap while still
 * yielding when a scan exceeds its wall-time slice.
 */
internal class WorksiteTickBudget(
    private val maxOperations: Int,
    private val maxNanos: Long = DEFAULT_MAX_NANOS,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    init {
        require(maxOperations > 0) { "Worksite operation budget must be positive" }
        require(maxNanos > 0) { "Worksite wall-time budget must be positive" }
    }

    private val startedAt = nowNanos()
    private var consumed = 0

    val operations: Int
        get() = consumed

    val remainingOperations: Int
        get() = maxOperations - consumed

    var stoppedByTime: Boolean = false
        private set

    fun tryConsume(): Boolean {
        if (consumed >= maxOperations) return false
        if (consumed > 0 && consumed % CHECK_INTERVAL == 0 && elapsedNanos() >= maxNanos) {
            stoppedByTime = true
            return false
        }
        consumed++
        return true
    }

    private fun elapsedNanos(): Long = (nowNanos() - startedAt).coerceAtLeast(0L)

    private companion object {
        const val CHECK_INTERVAL = 64
        const val DEFAULT_MAX_NANOS = 2_000_000L
    }
}

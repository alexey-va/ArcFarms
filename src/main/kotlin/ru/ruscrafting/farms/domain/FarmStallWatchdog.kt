package ru.ruscrafting.farms.domain

internal enum class FarmStallAction {
    NONE,
    REMIND,
    RELEASE,
}

internal data class FarmStallWatchdogState(
    val lastProgressTick: Long,
    val reminded: Boolean = false,
)

internal data class FarmStallWatchdogResult(
    val state: FarmStallWatchdogState,
    val action: FarmStallAction,
)

/**
 * A deliberately geometry-blind lease timer. Callers decide what counts as
 * progress; stairs, slabs, turns and collision shapes are never inspected.
 */
internal object FarmStallWatchdog {
    fun observe(
        state: FarmStallWatchdogState,
        tick: Long,
        progressed: Boolean,
        reminderTicks: Long,
        releaseTicks: Long,
    ): FarmStallWatchdogResult {
        require(reminderTicks in 1 until releaseTicks)
        if (progressed || tick < state.lastProgressTick) {
            return FarmStallWatchdogResult(FarmStallWatchdogState(tick), FarmStallAction.NONE)
        }
        val idleTicks = tick - state.lastProgressTick
        if (idleTicks >= releaseTicks) return FarmStallWatchdogResult(state, FarmStallAction.RELEASE)
        if (!state.reminded && idleTicks >= reminderTicks) {
            return FarmStallWatchdogResult(state.copy(reminded = true), FarmStallAction.REMIND)
        }
        return FarmStallWatchdogResult(state, FarmStallAction.NONE)
    }
}

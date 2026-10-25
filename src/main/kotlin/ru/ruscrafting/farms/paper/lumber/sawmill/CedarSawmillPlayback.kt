package ru.ruscrafting.farms.paper.lumber.sawmill

/** One shared, non-persistent demonstration clock; the worksite tick owns its only advancement. */
internal class CedarSawmillPlayback {
    enum class Phase { IDLE, RUNNING, READY, EXHAUSTED }

    var phase: Phase = Phase.IDLE
        private set
    var elapsedTicks: Int = 0
        private set
    var cyclesStarted: Int = 0
        private set
    var passIndex: Int = 0
        private set
    val elapsedSeconds: Double get() = elapsedTicks / 20.0

    fun start(): Boolean {
        if (phase == Phase.RUNNING) return false
        passIndex = if (phase == Phase.READY) 1 else 0
        elapsedTicks = 0
        cyclesStarted++
        phase = Phase.RUNNING
        return true
    }

    fun tick() {
        if (phase != Phase.RUNNING) return
        if (++elapsedTicks == DURATION_TICKS) phase = if (passIndex == 0) Phase.READY else Phase.EXHAUSTED
    }

    fun reset() {
        phase = Phase.IDLE
        elapsedTicks = 0
        cyclesStarted = 0
        passIndex = 0
    }

    companion object {
        const val DURATION_TICKS = 25 * 20
    }
}

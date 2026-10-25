package ru.ruscrafting.farms.paper.lumber.sawmill

/** One shared, non-persistent demonstration clock; the worksite tick owns its only advancement. */
internal class CedarSawmillPlayback {
    enum class Phase { IDLE, RUNNING, PAUSED, FINISHED }

    var phase: Phase = Phase.IDLE
        private set
    var elapsedTicks: Int = 0
        private set
    var cyclesStarted: Int = 0
        private set
    val elapsedSeconds: Double get() = elapsedTicks / 20.0

    fun toggle() {
        when (phase) {
            Phase.IDLE, Phase.FINISHED -> {
                elapsedTicks = 0
                cyclesStarted++
                phase = Phase.RUNNING
            }
            Phase.RUNNING -> phase = Phase.PAUSED
            Phase.PAUSED -> phase = Phase.RUNNING
        }
    }

    fun tick() {
        if (phase != Phase.RUNNING) return
        if (++elapsedTicks == DURATION_TICKS) phase = Phase.FINISHED
    }

    fun reset() {
        phase = Phase.IDLE
        elapsedTicks = 0
        cyclesStarted = 0
    }

    companion object {
        const val DURATION_TICKS = 25 * 20
    }
}

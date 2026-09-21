package ru.ruscrafting.farms.domain

/** Transient furnace control. A reload safely reheats the same durable batch, never grants another one. */
data class MineWorkshopHeat(
    val temperature: Double = 24.0,
    val airOpen: Boolean = true,
    val stableMillis: Long = 0L,
) {
    init {
        require(temperature.isFinite() && temperature in 18.0..100.0)
        require(stableMillis in 0L..REQUIRED_MILLIS)
    }

    val inBand: Boolean get() = temperature in MIN_HEAT..MAX_HEAT
    val ready: Boolean get() = stableMillis >= REQUIRED_MILLIS
    val progress: Double get() = (stableMillis.toDouble() / REQUIRED_MILLIS).coerceIn(0.0, 1.0)

    fun toggleAir(): MineWorkshopHeat = if (ready) this else copy(airOpen = !airOpen)

    /** Integrate short slices so time in the green band is independent of render/tick frequency. */
    fun tick(elapsedMillis: Long): MineWorkshopHeat {
        if (ready || elapsedMillis <= 0L) return this
        var next = this
        var remaining = elapsedMillis.coerceAtMost(1_000L) // pauses/server stalls never finish unattended heating
        while (remaining > 0 && !next.ready) {
            val step = minOf(remaining, 25L)
            val temperature = (next.temperature + (if (next.airOpen) 12.0 else -6.0) * step / 1_000.0).coerceIn(18.0, 100.0)
            val stable = if (temperature in MIN_HEAT..MAX_HEAT) next.stableMillis + step else next.stableMillis
            next = next.copy(temperature = temperature, stableMillis = stable.coerceAtMost(REQUIRED_MILLIS))
            remaining -= step
        }
        return next
    }

    companion object {
        const val MIN_HEAT = 60.0
        const val MAX_HEAT = 78.0
        const val REQUIRED_MILLIS = 4_000L
    }
}

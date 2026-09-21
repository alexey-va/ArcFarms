package ru.ruscrafting.farms.domain.mine.expedition

/** Open the ladle, then close it in the broad green zone. A missed dose simply drains for a retry. */
internal data class MineFactoryPour(val startedAt: Long) {
    fun level(now: Long)=((now-startedAt).toDouble()/10_000).coerceIn(0.0,1.0)
    fun ready(now: Long)=level(now) in .65.. .90
    fun overflow(now: Long)=level(now)>=1.0
}

package ru.ruscrafting.farms.domain.mine.expedition

/** Visual cycles grant no rewards; one existing domain checkpoint follows completion. */
internal data class MineFactoryOperation(val stage: MineExpeditionStage, val startedAt: Long) {
    val duration: Long = when(stage) {
        MineExpeditionStage.FACTORY_WATER -> 3_000L
        MineExpeditionStage.FACTORY_COAL -> 6_000L
        MineExpeditionStage.FACTORY_CRANE -> 4_500L
        MineExpeditionStage.FACTORY_INSTALL -> 2_400L
        MineExpeditionStage.DESCENT_CORE_VALVES -> 6_000L
        else -> error("Not a powered factory operation: $stage")
    }
    fun progress(now: Long): Double = ((now-startedAt).toDouble()/duration).coerceIn(0.0,1.0)
}

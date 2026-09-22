package ru.ruscrafting.farms.paper.mine.expedition

import java.util.UUID
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiment

/** Transient jam/cooling progress; selected incidents remain in the durable plan. */
internal data class MineFactoryExperimentSession(
    val scene: MineExpeditionScene,
    val experiment: MineFactoryExperiment,
    var owner: UUID? = null,
    var pryCount: Int = 0,
    var lastTick: Long = 0L,
    var lastInput: Long = Long.MIN_VALUE,
    var sprayMillis: Long = 0L,
    var hoseEquipped: Boolean = false,
    var nextParticles: Long = 0L,
    var nextWaterSound: Long = 0L,
    val informed: MutableSet<UUID> = hashSetOf(),
)

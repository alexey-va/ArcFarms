package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Location
import java.util.UUID
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiment

/** Ephemeral operator/animation state; durable selection lives in the domain plan. */
internal data class MineFactoryExperimentSession(
    val scene: MineExpeditionScene,
    val experiment: MineFactoryExperiment,
    var owner: UUID? = null,
    var pryCount: Int = 0,
    var startedAt: Long = -1L,
    var lastTick: Long = 0L,
    var lastInput: Long = Long.MIN_VALUE,
    var lastSound: Long = Long.MIN_VALUE,
    var sprayMillis: Long = 0L,
    var hoseEquipped: Boolean = false,
    var grabbed: Boolean = false,
    var current: Location? = null,
    var landing: Location? = null,
    var routeProgress: Double = 0.0,
    var routeCycle: Int = 0,
    var mouldChoice: Int = -1,
    var mouldStartedAt: Long = -1L,
)

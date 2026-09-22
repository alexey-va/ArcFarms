package ru.ruscrafting.farms.domain.mine.expedition

import kotlin.math.PI
import kotlin.math.max

/** Startup timing shared by the physical factory generator and its checkpoint gate. */
object MineFactoryGeneratorCycle {
    const val STARTUP_MILLIS = 6_000L
    const val CRANK_PERIOD_MILLIS = 3_000L

    private const val START_SPEED = 0.28
    private const val SPEED_DELTA = 1.0 - START_SPEED
    private const val TWO_PI = 2.0 * PI

    /** Legacy saves have no ignition timestamp and already run at steady speed. */
    fun ready(state: MineExpeditionState, now: Long): Boolean {
        require(now >= 0L)
        val startedAt = state.factoryGeneratorStartedAt
        return startedAt == 0L || now >= startedAt && now - startedAt >= STARTUP_MILLIS
    }

    /** Speed as a fraction of steady-state RPM. */
    fun speed(state: MineExpeditionState, now: Long): Double {
        require(now >= 0L)
        val startedAt = state.factoryGeneratorStartedAt
        if (startedAt == 0L) return 1.0
        val elapsed = (now - startedAt).coerceAtLeast(0L).coerceAtMost(STARTUP_MILLIS).toDouble()
        val progress = elapsed / STARTUP_MILLIS
        val smooth = progress * progress * (3.0 - 2.0 * progress)
        return START_SPEED + SPEED_DELTA * smooth
    }

    /** Unwrapped crank angle in radians, analytically integrating the smooth RPM ramp. */
    fun phase(state: MineExpeditionState, now: Long): Double {
        require(now >= 0L)
        val startedAt = state.factoryGeneratorStartedAt
        if (startedAt == 0L) return now.toDouble() * TWO_PI / CRANK_PERIOD_MILLIS

        val elapsed = max(0L, now - startedAt).toDouble()
        val rampElapsed = elapsed.coerceAtMost(STARTUP_MILLIS.toDouble())
        val progress = rampElapsed / STARTUP_MILLIS
        val integratedSpeedMillis = STARTUP_MILLIS * (
            START_SPEED * progress + SPEED_DELTA * (progress * progress * progress - 0.5 * progress * progress * progress * progress)
        )
        val startPhase = TWO_PI
        val startupPhase = startPhase + TWO_PI * integratedSpeedMillis / CRANK_PERIOD_MILLIS
        val steadyMillis = (elapsed - STARTUP_MILLIS).coerceAtLeast(0.0)
        return startupPhase + TWO_PI * steadyMillis / CRANK_PERIOD_MILLIS
    }

    /** Normalized startup progress; old saves are treated as fully commissioned. */
    fun progress(state: MineExpeditionState, now: Long): Double {
        require(now >= 0L)
        val startedAt = state.factoryGeneratorStartedAt
        if (startedAt == 0L) return 1.0
        return ((now - startedAt).coerceAtLeast(0L).toDouble() / STARTUP_MILLIS).coerceIn(0.0, 1.0)
    }

    /** Accept the final water-stage action only after the two manual commissioning steps. */
    fun completeStart(current: MineExpeditionState, now: Long): MineExpeditionStep {
        require(now >= 0L)
        if (current.stage != MineExpeditionStage.FACTORY_WATER ||
            current.factoryGeneratorStartedAt != 0L ||
            0 !in current.completed || 1 !in current.completed ||
            MineExpeditionEngine.currentTarget(current) != 2
        ) return MineExpeditionStep(current, false)

        val step = MineExpeditionEngine.completeTarget(current, 2, now)
        return if (step.accepted) {
            step.copy(state = step.state.copy(factoryGeneratorStartedAt = now.coerceAtLeast(1L)))
        } else {
            step
        }
    }
}

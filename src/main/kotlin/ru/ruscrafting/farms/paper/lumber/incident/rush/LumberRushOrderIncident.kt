package ru.ruscrafting.farms.paper.lumber.incident.rush

import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberRushOrderState
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime

/** A parallel bonus deadline: it never owns or blocks the foreground phase. */
internal class LumberRushOrderIncident(private val port: WorksiteRuntimePort) {
    fun start(runtime: LumberRuntime, now: Long, durationMillis: Long): Boolean {
        require(durationMillis in 1_000L..3_600_000L)
        if (runtime.state.phase != LumberPhase.STACKING || runtime.state.rushOrder != null) return false
        runtime.state = runtime.state.copy(rushOrder = LumberRushOrderState(now, now + durationMillis))
        port.persistAsync()
        return true
    }

    fun tick(runtime: LumberRuntime, now: Long): Boolean {
        val rush = runtime.state.rushOrder ?: return false
        val next = when {
            rush.bonusEarned -> rush
            runtime.state.phase in COMPLETED_FOREGROUND && now <= rush.deadlineAt -> rush.copy(bonusEarned = true)
            now > rush.deadlineAt -> rush.copy(bonusAvailable = false)
            else -> rush
        }
        if (next == rush) return false
        runtime.state = runtime.state.copy(rushOrder = next)
        port.persistAsync()
        return true
    }

    private companion object {
        val COMPLETED_FOREGROUND = setOf(LumberPhase.DISPATCH, LumberPhase.COOLDOWN)
    }
}

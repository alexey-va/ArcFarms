package ru.ruscrafting.farms.paper.mine.incident

import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.incident.cavein.MineCaveInIncident
import ru.ruscrafting.farms.paper.mine.incident.creature.MineCreatureNestIncident
import ru.ruscrafting.farms.paper.mine.incident.crystal.MineCrystalResonanceIncident
import ru.ruscrafting.farms.paper.mine.incident.flood.MineFloodingIncident
import ru.ruscrafting.farms.paper.mine.incident.gas.MineGasLeakIncident
import ru.ruscrafting.farms.paper.mine.incident.power.MinePowerFailureIncident
import ru.ruscrafting.farms.paper.mine.incident.rescue.MineLostMinerIncident
import ru.ruscrafting.farms.paper.mine.incident.track.MineTrackDamageIncident
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import java.util.logging.Level

internal class MineIncidentScheduler(
    private val caveIn: MineCaveInIncident,
    private val gasLeak: MineGasLeakIncident,
    private val flooding: MineFloodingIncident,
    private val trackDamage: MineTrackDamageIncident,
    private val crystal: MineCrystalResonanceIncident,
    private val creatures: MineCreatureNestIncident,
    private val power: MinePowerFailureIncident,
    private val lostMiner: MineLostMinerIncident,
    private val diagnostics: MineIncidentPlacementDiagnostics,
    private val state: WorksiteStatePort,
) {
    private val retryAfter = mutableMapOf<String, Long>()
    private val diagnosed = mutableSetOf<String>()

    fun tick(runtime: MineRuntime, now: Long, onlineParticipants: Int): Boolean {
        if (onlineParticipants <= 0 || runtime.state.phase == MinePhase.INCIDENT) return false
        val type = runtime.state.incidentSchedule.getOrNull(runtime.state.incidentCursor) ?: return false
        if (runtime.settings.miningOnly) {
            if (runtime.state.phase !in setOf(MinePhase.MINING, MinePhase.EXTRACTION) ||
                runtime.state.mined < (runtime.rules().miningQuota / 2).coerceAtLeast(1)) return false
        } else if ((PHASE_RANK[runtime.state.phase] ?: return false) < (INCIDENT_RANK[type] ?: 0)) return false
        val key = "${runtime.settings.id}:${runtime.state.sequence}:${runtime.state.incidentCursor}"
        if (now < (retryAfter[key] ?: 0L)) return false
        val started = force(runtime, type, now)
        if (started) {
            retryAfter.remove(key)
        } else {
            retryAfter[key] = now + RETRY_MILLIS
        }
        return started
    }

    fun force(runtime: MineRuntime, type: MineIncidentType, now: Long): Boolean {
        if (runtime.state.phase == MinePhase.INCIDENT) return false
        val phaseRank = PHASE_RANK[runtime.state.phase] ?: return false
        if (!runtime.settings.miningOnly && phaseRank < (INCIDENT_RANK[type] ?: 0)) return false
        val started = when (type) {
            MineIncidentType.CAVE_IN -> caveIn.start(runtime, now)
            MineIncidentType.GAS_LEAK -> gasLeak.start(runtime, 2, now)
            MineIncidentType.FLOODING -> flooding.start(runtime, 2, now)
            MineIncidentType.TRACK_DAMAGE -> trackDamage.start(runtime, 2, now)
            MineIncidentType.CRYSTAL_RESONANCE -> crystal.start(runtime, 2, now)
            MineIncidentType.CREATURE_NEST -> creatures.start(runtime, 3, now)
            MineIncidentType.POWER_FAILURE -> power.start(runtime, 2, now)
            MineIncidentType.LOST_MINER -> lostMiner.start(runtime, now)
            else -> false
        }
        val key = "${runtime.settings.id}:${runtime.state.sequence}:${runtime.state.incidentCursor}:$type"
        if (started) {
            diagnosed.remove(key)
        } else if (diagnosed.add(key)) {
            logFailure(runtime, type)
        }
        return started
    }

    fun cleanup() {
        retryAfter.clear()
        diagnosed.clear()
    }

    private fun logFailure(runtime: MineRuntime, type: MineIncidentType) {
        val required = when (type) {
            MineIncidentType.CAVE_IN -> 4
            MineIncidentType.CREATURE_NEST -> 3
            MineIncidentType.LOST_MINER -> 1
            else -> 2
        }
        state.log(
            Level.WARNING,
            "Mine incident start rejected zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                "type=$type phase=${runtime.state.phase} " +
                (caveIn.placementFailure(runtime.settings.id).takeIf { type == MineIncidentType.CAVE_IN }
                    ?: diagnostics.describe(runtime, type, required)),
        )
    }

    private companion object {
        const val RETRY_MILLIS = 5_000L
        val PHASE_RANK = mapOf(
            MinePhase.PROSPECTING to 0, MinePhase.MINING to 1, MinePhase.LOADING to 2, MinePhase.EXTRACTION to 3,
        )
        val INCIDENT_RANK = mapOf(
            MineIncidentType.CAVE_IN to 0,
            MineIncidentType.GAS_LEAK to 0,
            MineIncidentType.FLOODING to 0,
            MineIncidentType.TRACK_DAMAGE to 1,
            MineIncidentType.CRYSTAL_RESONANCE to 1,
            MineIncidentType.CREATURE_NEST to 2,
            MineIncidentType.POWER_FAILURE to 2,
            MineIncidentType.LOST_MINER to 3,
        )
    }
}

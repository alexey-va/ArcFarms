package ru.ruscrafting.farms.paper.mine.incident

import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionEngine
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
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import java.util.logging.Level
import java.util.concurrent.CompletableFuture

internal class MineIncidentScheduler(
    private val caveIn: MineCaveInIncident,
    private val gasLeak: MineGasLeakIncident,
    private val flooding: MineFloodingIncident,
    private val trackDamage: MineTrackDamageIncident,
    private val crystal: MineCrystalResonanceIncident,
    private val creatures: MineCreatureNestIncident,
    private val power: MinePowerFailureIncident,
    private val lostMiner: MineLostMinerIncident,
    private val workings: ru.ruscrafting.farms.paper.mine.working.MineWorkingController,
    private val diagnostics: MineIncidentPlacementDiagnostics,
    private val state: WorksiteStatePort,
    private val workshop: ru.ruscrafting.farms.paper.mine.workshop.MineOreWorkshopController,
    private val expeditions: ru.ruscrafting.farms.paper.mine.expedition.MineExpeditionController? = null,
    private val tasks: WorksiteTaskPort? = null,
    private val floorActivities: ru.ruscrafting.farms.paper.mine.floorwork.MineFloorActivities? = null,
) {
    private val retryAfter = mutableMapOf<String, Long>()
    private val diagnosed = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()

    fun tick(runtime: MineRuntime, now: Long, onlineParticipants: Int): Boolean {
        if (onlineParticipants <= 0 || runtime.state.phase == MinePhase.INCIDENT) return false
        val type = runtime.state.incidentSchedule.getOrNull(runtime.state.incidentCursor) ?: return false
        if (type == MineIncidentType.POWER_FAILURE) {
            runtime.state = runtime.state.copy(incidentCursor = runtime.state.incidentCursor + 1)
            state.persistAsync()
            return false
        }
        if (runtime.settings.miningOnly) {
            if (runtime.state.phase !in setOf(MinePhase.MINING, MinePhase.EXTRACTION) ||
                runtime.state.mined < threshold(runtime.rules().miningQuota, runtime.state.incidentCursor,
                    runtime.state.incidentSchedule.size)) return false
        } else if ((PHASE_RANK[runtime.state.phase] ?: return false) < (INCIDENT_RANK[type] ?: 0)) return false
        val key = "${runtime.settings.id}:${runtime.state.sequence}:${runtime.state.incidentCursor}"
        if (now < (retryAfter[key] ?: 0L)) return false
        if (!pending.add(key)) return false
        val token = tasks?.lifecycleToken()
        forceAsync(runtime, type, now).whenComplete { started, failure ->
            val finish = {
                pending.remove(key)
                if (failure == null && started == true) retryAfter.remove(key)
                else retryAfter[key] = now + RETRY_MILLIS
                Unit
            }
            if (token != null) tasks?.runSync(token, finish) else finish()
        }
        return runtime.state.incident?.type == type
    }

    fun force(runtime: MineRuntime, type: MineIncidentType, now: Long): Boolean {
        val key = "${runtime.settings.id}:${runtime.state.sequence}:${runtime.state.incidentCursor}:$type"
        if (stateBlocker(runtime, type) != null) {
            if (diagnosed.add(key)) logFailure(runtime, type)
            return false
        }
        val started = when (type) {
            MineIncidentType.CAVE_IN -> caveIn.start(runtime, now)
            MineIncidentType.GAS_LEAK -> gasLeak.start(runtime, 2, now)
            MineIncidentType.FLOODING -> flooding.start(runtime, 1, now)
            MineIncidentType.TRACK_DAMAGE -> workings.start(runtime, type, now)
            MineIncidentType.CRYSTAL_RESONANCE -> crystal.start(runtime, 4, now)
            MineIncidentType.CREATURE_NEST -> creatures.start(runtime, 3, now)
            MineIncidentType.POWER_FAILURE -> false
            MineIncidentType.FREIGHT_WINCH, MineIncidentType.DRAINAGE_PUMP -> floorActivities?.start(runtime, type, now) == true
            MineIncidentType.LOST_MINER -> lostMiner.start(runtime, now)
            MineIncidentType.TUNNEL_DRIVE, MineIncidentType.RAIL_EXTENSION -> workings.start(runtime, type, now)
            MineIncidentType.ORE_WORKSHOP -> workshop.start(runtime, ru.ruscrafting.farms.domain.MineWorkingEngine.BATCHES *
                (ru.ruscrafting.farms.domain.MineWorkingEngine.CRUSH_STROKES + 3))
            MineIncidentType.LAST_DESCENT, MineIncidentType.DRILLING_ARK, MineIncidentType.DEAD_FACTORY ->
                expeditions?.start(runtime, type, now) == true
        }
        if (started) {
            diagnosed.remove(key)
        } else if (diagnosed.add(key)) {
            logFailure(runtime, type)
        }
        return started
    }

    fun forceAsync(runtime: MineRuntime, type: MineIncidentType, now: Long): CompletableFuture<Boolean> {
        if (type !in setOf(MineIncidentType.TRACK_DAMAGE, MineIncidentType.TUNNEL_DRIVE, MineIncidentType.RAIL_EXTENSION)) {
            return CompletableFuture.completedFuture(force(runtime, type, now))
        }
        if (stateBlocker(runtime, type) != null) return CompletableFuture.completedFuture(false)
        val key = "${runtime.settings.id}:${runtime.state.sequence}:${runtime.state.incidentCursor}:$type"
        return workings.startAsync(runtime, type, now).thenApply { started ->
            if (started) diagnosed.remove(key)
            else if (diagnosed.add(key)) logFailure(runtime, type)
            started
        }
    }

    fun diagnostics(runtime: MineRuntime): List<MineIncidentPlacementReport> = SUPPORTED_TYPES.map { type -> diagnostics(runtime, type) }

    fun diagnostics(runtime: MineRuntime, type: MineIncidentType): MineIncidentPlacementReport {
        if (type == MineIncidentType.CRYSTAL_RESONANCE) {
            val placement = crystal.diagnostics(runtime)
            if (crystal.hasPreparation(runtime) && placement.rejected.isNotEmpty() || stateBlocker(runtime, type) == null) return placement
        }
        val blocker = stateBlocker(runtime, type)
        return when {
            blocker != null -> MineIncidentPlacementReport(type, required(type), 0, 0, mapOf(blocker to 1))
            type == MineIncidentType.CAVE_IN -> caveIn.diagnostics(runtime)
            type in ru.ruscrafting.farms.paper.mine.floorwork.MineFloorActivities.TYPES ->
                floorActivities?.diagnostics(runtime, type) ?: MineIncidentPlacementReport(type, required(type), 0, 0, mapOf("floor_machine_unavailable" to 1))
            MineExpeditionEngine.supports(type) -> when {
                expeditions?.configured(runtime) != true -> MineIncidentPlacementReport(type, 1, 0, 0, mapOf("expedition_gate_missing" to 1))
                expeditions.available(type) -> MineIncidentPlacementReport(type, 1, 1, 1, emptyMap())
                else -> MineIncidentPlacementReport(type, 1, 0, 0, mapOf("expedition_reserve_not_ready" to 1))
            }
            type == MineIncidentType.ORE_WORKSHOP -> if (workshop.configured(runtime)) MineIncidentPlacementReport(type, 1, 1, 1, emptyMap())
                else MineIncidentPlacementReport(type, 1, 0, 0, mapOf("workshop_points_missing" to 1))
            ru.ruscrafting.farms.domain.MineWorkingEngine.supports(type) -> workings.diagnostics(runtime, type)
            else -> diagnostics.report(runtime, type, required(type))
        }
    }

    fun cleanup() {
        retryAfter.clear()
        diagnosed.clear()
        pending.clear()
    }

    fun reportPreparationFailure(runtime: MineRuntime, type: MineIncidentType) {
        val report = diagnostics(runtime, type)
        if (report.rejected.keys.all { it in setOf("search_pending", "chunk_unloaded") }) return
        val key = "${runtime.settings.id}:${runtime.state.sequence}:${runtime.state.incidentCursor}:$type:${report.rejected.keys}"
        if (diagnosed.add(key)) logFailure(runtime, type, report)
    }

    private fun logFailure(runtime: MineRuntime, type: MineIncidentType, placement: MineIncidentPlacementReport? = null) {
        val required = required(type)
        val blocker = stateBlocker(runtime, type)
        state.log(
            Level.WARNING,
            "Mine incident start rejected zone=${runtime.settings.id} sequence=${runtime.state.sequence} " +
                "type=$type phase=${runtime.state.phase} " +
                (placement?.technical() ?: blocker?.let { "required=$required usable=0 considered=0 rejected={$it=1}" }
                    ?: caveIn.placementFailure(runtime.settings.id).takeIf { type == MineIncidentType.CAVE_IN }
                    ?: if (ru.ruscrafting.farms.domain.MineWorkingEngine.supports(type)) workings.diagnostics(runtime, type).toString()
                    else diagnostics(runtime, type).technical()),
        )
    }

    /** Inspect the same post-abort state the admin switch will use, without retiring the current scene. */
    fun replacementBlocker(runtime: MineRuntime, type: MineIncidentType): String? {
        val current = runtime.state
        val replacement = if (current.phase == MinePhase.INCIDENT || current.incident != null) {
            val aborted = MineShiftEngine.abortIncident(current)
            if (!aborted.accepted) return "state_transition_rejected"
            aborted.state
        } else current
        return stateBlocker(runtime, type, replacement)
    }

    private fun stateBlocker(runtime: MineRuntime, type: MineIncidentType, current: MineShiftState = runtime.state): String? {
        if (current.phase == MinePhase.INCIDENT || current.incident != null) return "incident_already_active"
        if (ru.ruscrafting.farms.domain.MineWorkingEngine.supports(type) && type != MineIncidentType.ORE_WORKSHOP && workings.transitioning(runtime)) return "working_cleanup_pending"
        if (type == MineIncidentType.LOST_MINER && lostMiner.transitioning(runtime)) return "rescue_cleanup_pending"
        val phaseRank = PHASE_RANK[current.phase] ?: return "phase_not_ready"
        if (!runtime.settings.miningOnly && phaseRank < (INCIDENT_RANK[type] ?: 0)) return "phase_too_early"
        return null
    }

    companion object {
        internal fun threshold(quota: Int, cursor: Int, count: Int): Int =
            ((quota.toLong() * (cursor + 1) + count) / (count + 1)).toInt().coerceAtLeast(1)

        val SUPPORTED_TYPES = listOf(
            MineIncidentType.CAVE_IN,
            MineIncidentType.GAS_LEAK,
            MineIncidentType.FLOODING,
            MineIncidentType.TRACK_DAMAGE,
            MineIncidentType.CRYSTAL_RESONANCE,
            MineIncidentType.CREATURE_NEST,
            MineIncidentType.LOST_MINER,
            MineIncidentType.TUNNEL_DRIVE, MineIncidentType.RAIL_EXTENSION, MineIncidentType.ORE_WORKSHOP,
            MineIncidentType.LAST_DESCENT, MineIncidentType.DRILLING_ARK, MineIncidentType.DEAD_FACTORY,
            MineIncidentType.FREIGHT_WINCH, MineIncidentType.DRAINAGE_PUMP,
        )

        private fun required(type: MineIncidentType): Int = when (type) {
            MineIncidentType.CAVE_IN -> 60
            MineIncidentType.FLOODING -> 1
            MineIncidentType.CREATURE_NEST -> 3
            MineIncidentType.LOST_MINER -> 1
            MineIncidentType.CRYSTAL_RESONANCE -> 4
            MineIncidentType.FREIGHT_WINCH -> ru.ruscrafting.farms.domain.MineFloorActivity.WINCH_STEPS
            MineIncidentType.DRAINAGE_PUMP -> ru.ruscrafting.farms.domain.MineFloorActivity.PUMP_STEPS
            MineIncidentType.LAST_DESCENT, MineIncidentType.DRILLING_ARK, MineIncidentType.DEAD_FACTORY ->
                MineExpeditionEngine.required(type)
            else -> 2
        }

        private const val RETRY_MILLIS = 5_000L
        private val PHASE_RANK = mapOf(
            MinePhase.PROSPECTING to 0, MinePhase.MINING to 1, MinePhase.LOADING to 2, MinePhase.EXTRACTION to 3,
        )
        private val INCIDENT_RANK = mapOf(
            MineIncidentType.CAVE_IN to 0,
            MineIncidentType.GAS_LEAK to 0,
            MineIncidentType.FLOODING to 0,
            MineIncidentType.TRACK_DAMAGE to 1,
            MineIncidentType.CRYSTAL_RESONANCE to 1,
            MineIncidentType.CREATURE_NEST to 2,
            MineIncidentType.POWER_FAILURE to 2,
            MineIncidentType.LOST_MINER to 3,
            MineIncidentType.LAST_DESCENT to 2,
            MineIncidentType.DRILLING_ARK to 2,
            MineIncidentType.DEAD_FACTORY to 2,
        )
    }
}

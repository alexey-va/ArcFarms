package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.Sound
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmCarePlanner
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink

internal fun interface FarmCareTargetSpawner {
    fun ensure(runtime: FarmRuntime, target: FarmCareTarget)
}

/** Sole owner of disease spread timing and target expansion. */
internal class FarmDiseaseController(
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val transitions: FarmTransitionSink,
    private val targets: FarmCareTargetSpawner,
) {
    private val nextSpreadAt = mutableMapOf<String, Long>()

    fun start(runtime: FarmRuntime, now: Long) {
        nextSpreadAt[runtime.settings.id] = now + runtime.settings.diseaseSpreadSeconds * 1_000L
    }

    fun update(runtime: FarmRuntime, now: Long) {
        if (runtime.state.phase != FarmPhase.CARE || runtime.state.careType != FarmCareType.DISEASE) {
            clear(runtime.settings.id)
            return
        }
        if (port.players(runtime.region).isEmpty()) {
            start(runtime, now)
            return
        }
        val next = nextSpreadAt.getOrPut(runtime.settings.id) {
            now + runtime.settings.diseaseSpreadSeconds * 1_000L
        }
        if (now < next) return
        start(runtime, now)
        val current = runtime.state.careTargets.filter { it.role == FarmCareRole.DISEASED_CROP }
        if (current.size >= runtime.settings.diseaseMaxSpots) return
        val occupied = current.map(FarmCareTarget::position)
        val candidate = FarmCarePlanner.relocate(
            runtime.state.preparationPatch.filter { plot ->
                occupied.all { point ->
                    val dx = plot.x + 0.5 - point.x
                    val dz = plot.z + 0.5 - point.z
                    dx * dx + dz * dz >= 9.0
                }
            },
            occupied,
            runtime.state.sequence * 173L + current.size * 19L,
        ) ?: return
        val target = FarmCareTarget(
            id = (runtime.state.careTargets.maxOfOrNull(FarmCareTarget::id) ?: -1) + 1,
            role = FarmCareRole.DISEASED_CROP,
            position = FarmPointPosition(candidate.world, candidate.x + 0.5, candidate.y + 1.05, candidate.z + 0.5),
            required = 2,
        )
        val spread = FarmShiftEngine.spreadDisease(runtime.state, target, runtime.settings.diseaseMaxSpots)
        if (!spread.accepted) return
        transitions.apply(runtime, spread, null)
        targets.ensure(runtime, target)
        port.players(runtime.region).forEach { player ->
            port.sendActionBar(player, MessageKey.FARM_CARE_DISEASE_SPREAD)
            if (settings().sounds) player.playSound(player.location, Sound.BLOCK_SCULK_SPREAD, 0.55f, 1.45f)
        }
        debug.event("farm_disease_spread", "zone" to runtime.settings.id, "target" to target.id, "total" to current.size + 1)
        port.persistAsync()
    }

    fun clear(zoneId: String) {
        nextSpreadAt.remove(zoneId)
    }

    fun clearAll() {
        nextSpreadAt.clear()
    }
}

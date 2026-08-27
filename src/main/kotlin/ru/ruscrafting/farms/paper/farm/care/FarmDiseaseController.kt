package ru.ruscrafting.farms.paper.farm.care

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.FarmCarePlanner
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmCropDamage
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmBlockLedger
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.placement.FarmSurfacePolicy

internal fun interface FarmCareTargetSpawner {
    fun ensure(runtime: FarmRuntime, target: FarmCareTarget)
}

/** Owns the local disease frontier, crop-death deadlines, and restart-safe damage journal. */
internal class FarmDiseaseController(
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val ledger: FarmBlockLedger,
    private val transitions: FarmTransitionSink,
    private val targets: FarmCareTargetSpawner,
) {
    private val nextSpreadAt = mutableMapOf<String, Long>()
    private val killAt = mutableMapOf<String, MutableMap<Int, Long>>()

    fun start(runtime: FarmRuntime, now: Long) {
        nextSpreadAt[runtime.settings.id] = now + runtime.settings.diseaseSpreadSeconds * 1_000L
        killAt.remove(runtime.settings.id)
        refreshKillDeadlines(runtime, now)
    }

    fun update(runtime: FarmRuntime, now: Long) {
        if (!active(runtime)) {
            clear(runtime.settings.id)
            return
        }
        val normalized = FarmShiftEngine.normalizeDisease(runtime.state)
        if (normalized.accepted) {
            transitions.apply(runtime, normalized, null)
            port.persistAsync()
            if (!active(runtime)) return
        }
        if (port.players(runtime.region).isEmpty()) {
            // Empty farms pause the challenge instead of silently losing crops.
            start(runtime, now)
            return
        }
        refreshKillDeadlines(runtime, now)
        killDueCrops(runtime, now)

        val next = nextSpreadAt.getOrPut(runtime.settings.id) {
            now + runtime.settings.diseaseSpreadSeconds * 1_000L
        }
        if (now < next) return
        nextSpreadAt[runtime.settings.id] = now + runtime.settings.diseaseSpreadSeconds * 1_000L
        spread(runtime, now)
    }

    private fun spread(runtime: FarmRuntime, now: Long) {
        val current = runtime.state.careTargets.filter { it.role == FarmCareRole.DISEASED_CROP }
        if (current.size >= runtime.settings.diseaseMaxSpots) return
        val candidate = FarmCarePlanner.diseaseFrontier(
            runtime.state.preparationPatch.filter { plot ->
                plot.block()?.let(FarmSurfacePolicy::isOutdoorBed) == true
            },
            current.filterNot(FarmCareTarget::complete).ifEmpty { current }.map(FarmCareTarget::position),
            runtime.settings.diseaseSpreadRadius,
            runtime.state.sequence * 173L + current.size * 19L,
        ) ?: return
        val target = FarmCareTarget(
            id = (runtime.state.careTargets.maxOfOrNull(FarmCareTarget::id) ?: -1) + 1,
            role = FarmCareRole.DISEASED_CROP,
            position = FarmPointPosition(candidate.world, candidate.x + 0.5, candidate.y + 1.05, candidate.z + 0.5),
        )
        val result = FarmShiftEngine.spreadDisease(runtime.state, target, runtime.settings.diseaseMaxSpots)
        if (!result.accepted) return
        transitions.apply(runtime, result, null)
        killAt.getOrPut(runtime.settings.id, ::mutableMapOf)[target.id] =
            now + runtime.settings.diseaseKillSeconds * 1_000L
        targets.ensure(runtime, target)
        port.players(runtime.region).forEach { player ->
            port.sendActionBar(player, MessageKey.FARM_CARE_DISEASE_SPREAD)
            if (settings().sounds) player.playSound(player.location, Sound.BLOCK_SCULK_SPREAD, 0.55f, 1.45f)
        }
        debug.event("farm_disease_spread", "zone" to runtime.settings.id, "target" to target.id, "total" to current.size + 1)
        port.persistAsync()
    }

    private fun refreshKillDeadlines(runtime: FarmRuntime, now: Long) {
        val deadlines = killAt.getOrPut(runtime.settings.id, ::mutableMapOf)
        val damaged = runtime.state.diseaseDamagedCrops.orEmpty().mapTo(hashSetOf()) { it.position }
        val live = runtime.state.careTargets.asSequence()
            .filter { it.role == FarmCareRole.DISEASED_CROP && !it.complete }
            .filter { target ->
                FarmPlotPosition(
                    target.position.world,
                    kotlin.math.floor(target.position.x).toInt(),
                    kotlin.math.floor(target.position.y).toInt() - 1,
                    kotlin.math.floor(target.position.z).toInt(),
                ) !in damaged
            }
            .mapTo(hashSetOf(), FarmCareTarget::id)
        deadlines.keys.retainAll(live)
        live.forEach { id -> deadlines.putIfAbsent(id, now + runtime.settings.diseaseKillSeconds * 1_000L) }
    }

    private fun killDueCrops(runtime: FarmRuntime, now: Long) {
        val deadlines = killAt[runtime.settings.id] ?: return
        runtime.state.careTargets.asSequence()
            .filter { it.role == FarmCareRole.DISEASED_CROP && !it.complete }
            .filter { deadlines.getOrDefault(it.id, Long.MAX_VALUE) <= now }
            .forEach { target ->
                deadlines.remove(target.id)
                killCrop(runtime, target)
            }
    }

    private fun killCrop(runtime: FarmRuntime, target: FarmCareTarget) {
        val world = Bukkit.getWorld(target.position.world) ?: return
        val crop = world.getBlockAt(
            kotlin.math.floor(target.position.x).toInt(),
            kotlin.math.floor(target.position.y).toInt(),
            kotlin.math.floor(target.position.z).toInt(),
        )
        if (crop.type.name !in runtime.settings.crops) return
        val soil = crop.getRelative(BlockFace.DOWN)
        if (!FarmSurfacePolicy.isOutdoorBed(soil)) return
        val plot = FarmPlotPosition(soil.world.name, soil.x, soil.y, soil.z)
        if (runtime.state.diseaseDamagedCrops.orEmpty().any { it.position == plot }) return
        ledger.captureActiveCrop(soil, runtime.settings.id)
        runtime.state = runtime.state.copy(
            diseaseDamagedCrops = runtime.state.diseaseDamagedCrops.orEmpty() + FarmCropDamage(plot, crop.type.name),
        )
        crop.setType(Material.AIR, false)
        if (settings().particles) {
            crop.world.spawnParticle(Particle.SMOKE, crop.location.add(0.5, 0.8, 0.5), 12, 0.3, 0.35, 0.3, 0.02)
        }
        debug.event("farm_disease_crop_killed", "zone" to runtime.settings.id, "target" to target.id, "plot" to plot)
        port.persistAsync()
    }

    fun clear(zoneId: String) {
        nextSpreadAt.remove(zoneId)
        killAt.remove(zoneId)
    }

    fun clearAll() {
        nextSpreadAt.clear()
        killAt.clear()
    }

    private fun active(runtime: FarmRuntime): Boolean =
        runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.DISEASE
}

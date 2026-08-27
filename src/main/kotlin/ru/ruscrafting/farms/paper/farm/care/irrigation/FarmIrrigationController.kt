package ru.ruscrafting.farms.paper.farm.care.irrigation

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.type.Farmland
import org.bukkit.entity.Player
import org.bukkit.event.block.MoistureChangeEvent
import ru.ruscrafting.farms.config.ArcFarmsConfig
import ru.ruscrafting.farms.domain.FarmCarePlotAssignment
import ru.ruscrafting.farms.domain.FarmCareRole
import ru.ruscrafting.farms.domain.FarmCareTarget
import ru.ruscrafting.farms.domain.FarmCareType
import ru.ruscrafting.farms.domain.FarmIrrigationRing
import ru.ruscrafting.farms.domain.FarmIrrigationWavePlanner
import ru.ruscrafting.farms.domain.FarmPhase
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmShiftEngine
import ru.ruscrafting.farms.paper.ArcFarmsDebug
import ru.ruscrafting.farms.paper.FarmRuntime
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.block
import ru.ruscrafting.farms.paper.farm.FarmTransitionSink
import ru.ruscrafting.farms.paper.farm.field.FARM_SOIL_TYPES
import ru.ruscrafting.farms.paper.toFarmPlotPosition
import java.util.UUID
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

private data class FarmIrrigationScope(val zoneId: String, val sequence: Long)
private data class FarmIrrigationWaveKey(val scope: FarmIrrigationScope, val targetId: Int)

private data class ActiveIrrigationWave(
    val actorId: UUID,
    val target: FarmCareTarget,
    val rings: List<FarmIrrigationRing>,
    val watered: MutableSet<FarmPlotPosition> = linkedSetOf(),
    var ringIndex: Int = 0,
    var plotIndex: Int = 0,
    var renderedRingIndex: Int = -1,
    var nextRingTick: Long = 0,
)

/** Owns dry irrigation soil and advances one bounded radial water wave at a time. */
internal class FarmIrrigationController(
    private val settings: () -> ArcFarmsConfig,
    private val debug: ArcFarmsDebug,
    private val port: WorksiteRuntimePort,
    private val transitions: FarmTransitionSink,
) {
    private val assignments = mutableMapOf<FarmIrrigationScope, Map<Int, Set<FarmPlotPosition>>>()
    private val plotAssignments = mutableMapOf<FarmIrrigationScope, Map<FarmPlotPosition, Int>>()
    private val activeWaves = mutableMapOf<FarmIrrigationWaveKey, ActiveIrrigationWave>()
    private val dryCursors = mutableMapOf<FarmIrrigationScope, Int>()
    private var currentTick = 0L

    fun start(runtime: FarmRuntime, target: FarmCareTarget, player: Player): Boolean {
        if (!isIrrigation(runtime) || target.role != FarmCareRole.VALVE || target.complete) return false
        val scope = scope(runtime)
        if (FarmIrrigationWaveKey(scope, target.id) in activeWaves) return false
        val plots = assignments(runtime)[target.id].orEmpty()
        if (plots.isEmpty()) return false
        val irrigation = runtime.settings.irrigation
        val wave = ActiveIrrigationWave(
            actorId = player.uniqueId,
            target = target,
            rings = FarmIrrigationWavePlanner.rings(plots, target.position, irrigation.ringWidth),
            nextRingTick = currentTick + irrigation.waveStartDelayTicks,
        )
        activeWaves[FarmIrrigationWaveKey(scope, target.id)] = wave
        if (settings().sounds) {
            player.playSound(player.location, Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.25f)
            player.playSound(player.location, Sound.BLOCK_WATER_AMBIENT, 0.55f, 0.85f)
        }
        debug.event(
            "farm_irrigation_wave_started",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "target" to target.id,
            "beds" to plots.size,
            "rings" to wave.rings.size,
            "player" to player.name,
        )
        return true
    }

    fun process(runtimes: Collection<FarmRuntime>) {
        currentTick++
        runtimes.forEach { runtime ->
            if (!isIrrigation(runtime)) {
                clear(runtime)
                return@forEach
            }
            processDrySoil(runtime)
            processWave(runtime)
        }
    }

    fun dryPlots(runtime: FarmRuntime): Set<FarmPlotPosition> {
        if (!isIrrigation(runtime)) return emptySet()
        val scope = scope(runtime)
        val waves = activeWaves.filterKeys { it.scope == scope }.values
        return runtime.state.careTargets.asSequence()
            .filter { it.role == FarmCareRole.VALVE && !it.complete }
            .flatMap { target -> assignments(runtime)[target.id].orEmpty().asSequence() }
            .filterNot { plot -> waves.any { plot in it.watered } }
            .toCollection(linkedSetOf())
    }

    fun onMoistureChange(event: MoistureChangeEvent, runtime: FarmRuntime): Boolean {
        if (!isIrrigation(runtime)) return false
        val scope = scope(runtime)
        val plot = event.block.toFarmPlotPosition()
        val targetId = plotAssignments(runtime)[plot] ?: return false
        val target = runtime.state.careTargets.firstOrNull {
            it.id == targetId && it.role == FarmCareRole.VALVE && !it.complete
        } ?: return false
        val wave = activeWaves[FarmIrrigationWaveKey(scope, target.id)]
        if (wave != null && plot in wave.watered) return false
        event.isCancelled = true
        return true
    }

    fun clear(runtime: FarmRuntime) {
        val zoneId = runtime.settings.id
        assignments.keys.removeIf { it.zoneId == zoneId }
        plotAssignments.keys.removeIf { it.zoneId == zoneId }
        activeWaves.keys.removeIf { it.scope.zoneId == zoneId }
        dryCursors.keys.removeIf { it.zoneId == zoneId }
    }

    fun clearAll() {
        assignments.clear()
        plotAssignments.clear()
        activeWaves.clear()
        dryCursors.clear()
    }

    private fun processDrySoil(runtime: FarmRuntime) {
        val dry = dryPlots(runtime).sortedWith(PLOT_ORDER)
        if (dry.isEmpty()) return
        val scope = scope(runtime)
        val start = Math.floorMod(dryCursors[scope] ?: 0, dry.size)
        val limit = minOf(runtime.settings.irrigation.dryBlocksPerTick, dry.size)
        repeat(limit) { offset -> dehydrate(dry[(start + offset) % dry.size].block()) }
        dryCursors[scope] = (start + limit) % dry.size
    }

    private fun processWave(runtime: FarmRuntime) {
        val scope = scope(runtime)
        val entry = activeWaves.entries.firstOrNull { it.key.scope == scope } ?: return
        val wave = entry.value
        val liveTarget = runtime.state.careTargets.firstOrNull { it.id == wave.target.id && it.role == FarmCareRole.VALVE }
        if (liveTarget == null || liveTarget.complete || currentTick < wave.nextRingTick) {
            if (liveTarget == null || liveTarget.complete) activeWaves.remove(entry.key)
            return
        }
        val ring = wave.rings.getOrNull(wave.ringIndex)
        if (ring == null) {
            finish(runtime, entry.key, wave)
            return
        }
        if (wave.renderedRingIndex != wave.ringIndex) {
            renderFront(runtime, wave.target, ring)
            wave.renderedRingIndex = wave.ringIndex
        }
        val end = minOf(wave.plotIndex + runtime.settings.irrigation.waveBlocksPerTick, ring.plots.size)
        for (index in wave.plotIndex until end) {
            val plot = ring.plots[index]
            val block = plot.block() ?: return
            hydrate(block)
            wave.watered += plot
            wave.plotIndex++
        }
        if (wave.plotIndex < ring.plots.size) return
        wave.ringIndex++
        wave.plotIndex = 0
        wave.nextRingTick = currentTick + runtime.settings.irrigation.ringIntervalTicks
        if (wave.ringIndex >= wave.rings.size) finish(runtime, entry.key, wave)
    }

    private fun finish(runtime: FarmRuntime, key: FarmIrrigationWaveKey, wave: ActiveIrrigationWave) {
        activeWaves.remove(key)
        val actor = org.bukkit.Bukkit.getPlayer(wave.actorId)
        if (settings().sounds) {
            port.players(runtime.region).forEach { player ->
                player.playSound(player.location, Sound.ENTITY_PLAYER_SPLASH, 0.75f, 1.15f)
            }
        }
        debug.event(
            "farm_irrigation_wave_completed",
            "zone" to runtime.settings.id,
            "sequence" to runtime.state.sequence,
            "target" to wave.target.id,
            "beds" to wave.watered.size,
            "player" to (actor?.name ?: wave.actorId),
        )
        transitions.apply(runtime, FarmShiftEngine.advanceCare(runtime.state, wave.target.id, wave.actorId), actor)
    }

    private fun renderFront(runtime: FarmRuntime, target: FarmCareTarget, ring: FarmIrrigationRing) {
        if (!settings().particles || ring.plots.isEmpty()) return
        val world = runtime.region.world
        if (world.name != target.position.world) return
        val irrigation = runtime.settings.irrigation
        val y = ring.plots.map { it.y }.average() + irrigation.particleHeight
        val radius = ring.radius.coerceAtLeast(0.55)
        val points = ceil(2.0 * PI * radius / irrigation.particleSpacing).toInt().coerceIn(8, 64)
        val viewers = port.players(runtime.region).filter { it.world === world }
        repeat(points) { index ->
            val angle = 2.0 * PI * index / points
            val location = Location(
                world,
                target.position.x + cos(angle) * radius,
                y,
                target.position.z + sin(angle) * radius,
            )
            viewers.forEach { player ->
                player.spawnParticle(
                    Particle.DUST,
                    location,
                    irrigation.particleCount,
                    irrigation.particleSpread,
                    irrigation.particleSpread * 0.45,
                    irrigation.particleSpread,
                    0.0,
                    WATER_DUST,
                )
                if (index % 2 == 0) player.spawnParticle(
                    Particle.SPLASH,
                    location,
                    irrigation.particleCount * 2,
                    irrigation.particleSpread,
                    irrigation.particleSpread * 0.65,
                    irrigation.particleSpread,
                    0.04,
                )
            }
        }
    }

    private fun assignments(runtime: FarmRuntime): Map<Int, Set<FarmPlotPosition>> = assignments.getOrPut(scope(runtime)) {
        FarmCarePlotAssignment.assignments(
            runtime.state.preparationPatch,
            runtime.state.careTargets.filter { it.role == FarmCareRole.VALVE },
        ).also { planned ->
            plotAssignments[scope(runtime)] = buildMap {
                planned.forEach { (targetId, plots) -> plots.forEach { plot -> put(plot, targetId) } }
            }
        }
    }

    private fun plotAssignments(runtime: FarmRuntime): Map<FarmPlotPosition, Int> {
        val scope = scope(runtime)
        assignments(runtime)
        return plotAssignments[scope].orEmpty()
    }

    private fun isIrrigation(runtime: FarmRuntime): Boolean =
        runtime.state.phase == FarmPhase.CARE && runtime.state.careType == FarmCareType.IRRIGATION

    private fun scope(runtime: FarmRuntime) = FarmIrrigationScope(runtime.settings.id, runtime.state.sequence)

    private fun dehydrate(block: Block?) = setMoisture(block, 0)

    private fun hydrate(block: Block) {
        setMoisture(block, Int.MAX_VALUE)
    }

    private fun setMoisture(block: Block?, requested: Int): Farmland? {
        block ?: return null
        if (block.type !in FARM_SOIL_TYPES) return null
        if (block.type != Material.FARMLAND) block.setType(Material.FARMLAND, false)
        val farmland = block.blockData as? Farmland ?: return null
        val moisture = requested.coerceIn(0, farmland.maximumMoisture)
        if (farmland.moisture != moisture) {
            farmland.moisture = moisture
            block.setBlockData(farmland, false)
        }
        return farmland
    }

    private companion object {
        val WATER_DUST = Particle.DustOptions(Color.fromRGB(69, 200, 245), 1.2f)
        val PLOT_ORDER = compareBy<FarmPlotPosition> { it.world }
            .thenBy(FarmPlotPosition::y)
            .thenBy(FarmPlotPosition::x)
            .thenBy(FarmPlotPosition::z)
    }
}

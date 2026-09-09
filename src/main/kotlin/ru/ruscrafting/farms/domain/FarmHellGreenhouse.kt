package ru.ruscrafting.farms.domain

import java.util.UUID

data class FarmHellGreenhouseRules(
    val quota: Int = 4,
    val growSeconds: Int = 6,
    val hotSeconds: Int = 6,
    val heatLimit: Int = 100,
    val heatPerHarvest: Int = 10,
    val evacuationSeconds: Int = 15,
) {
    init {
        require(quota in 1..16)
        require(growSeconds in 0..86_400)
        require(hotSeconds in 1..86_400)
        require(heatLimit in 1..1_000_000)
        require(heatPerHarvest in 0..1_000_000)
        require(evacuationSeconds in 1..86_400)
    }
}

data class FarmHellPlantationPlot(
    val heating: Boolean = false,
    val growthSeconds: Int = 0,
    val coolingSeconds: Int = 0,
    val overheatSeconds: Int = 0,
)

enum class FarmHellPlantationPhase { COLD, GROWING, HOT, COOLING, READY }

data class FarmHellPepper(val index: Int, val expiresAt: Int) {
    init { require(index >= 0); require(expiresAt >= 0) }
}

data class FarmHellGreenhouseState(
    val points: List<FarmPointPosition>,
    val elapsedSeconds: Int = 0,
    val heat: Int = 0,
    val harvested: Set<Int> = emptySet(),
    val cooled: Int = 0,
    val carried: Map<UUID, FarmHellPepper> = emptyMap(),
    val evacuationSeconds: Int? = null,
    val finished: Boolean = false,
    val entrance: FarmPointPosition? = null,
    val layoutVersion: Int = 0,
    val plots: List<FarmHellPlantationPlot> = emptyList(),
) {
    fun containsRoom(world: String, x: Double, y: Double, z: Double): Boolean {
        if (entrance == null || points.isEmpty() || world != points.first().world) return false
        val centerX = points.map { it.x }.average()
        val centerZ = points.map { it.z }.average()
        return if (layoutVersion >= 2) {
            kotlin.math.abs(x - centerX) <= 9.8 && kotlin.math.abs(z - centerZ) <= 11.8 &&
                y in points.first().y..(points.first().y + 6.8)
        } else {
            kotlin.math.abs(x - centerX) <= 4.8 && kotlin.math.abs(z - centerZ) <= 5.8 && kotlin.math.abs(y - points.first().y) <= 3.0
        }
    }
}

data class FarmHellGreenhouseResult(
    val state: FarmHellGreenhouseState,
    val accepted: Boolean,
    val contribution: Int = 0,
    val expiredPlayerIds: Set<UUID> = emptySet(),
    val scorchedPlots: Set<Int> = emptySet(),
    val finished: Boolean = false,
    val timedOut: Boolean = false,
    val successful: Boolean = false,
)

object FarmHellGreenhouseEngine {
    const val PHYSICAL_PLOTS = 4
    const val GROWTH_TARGET_SECONDS = 8
    const val COOLING_SECONDS = 2
    const val OVERHEAT_SECONDS = 4
    private val POINT_WORLD_ID = Regex("[A-Za-z0-9._-]{1,128}")

    fun initialize(current: FarmHellGreenhouseState, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        if (current.layoutVersion >= 2) {
            return FarmHellGreenhouseResult(current, true, finished = current.finished, successful = current.finished)
        }
        require(current.points.size >= PHYSICAL_PLOTS) { "Hell plantation requires four plot centers" }
        val next = current.copy(
            points = current.points.take(PHYSICAL_PLOTS),
            heat = 0, evacuationSeconds = null, harvested = emptySet(), carried = emptyMap(),
            finished = current.cooled >= rules.quota, layoutVersion = 2,
            plots = List(PHYSICAL_PLOTS) { FarmHellPlantationPlot() },
        )
        validate(next)
        return FarmHellGreenhouseResult(next, true, finished = next.finished, successful = next.finished)
    }

    fun phase(plot: FarmHellPlantationPlot): FarmHellPlantationPhase = when {
        !plot.heating && plot.growthSeconds < GROWTH_TARGET_SECONDS -> FarmHellPlantationPhase.COLD
        plot.heating && plot.growthSeconds < GROWTH_TARGET_SECONDS -> FarmHellPlantationPhase.GROWING
        plot.heating -> FarmHellPlantationPhase.HOT
        plot.coolingSeconds < COOLING_SECONDS -> FarmHellPlantationPhase.COOLING
        else -> FarmHellPlantationPhase.READY
    }

    fun secondsRemaining(plot: FarmHellPlantationPlot): Int = when (phase(plot)) {
        FarmHellPlantationPhase.COLD -> GROWTH_TARGET_SECONDS
        FarmHellPlantationPhase.GROWING -> GROWTH_TARGET_SECONDS - plot.growthSeconds
        FarmHellPlantationPhase.HOT -> OVERHEAT_SECONDS - plot.overheatSeconds
        FarmHellPlantationPhase.COOLING -> COOLING_SECONDS - plot.coolingSeconds
        FarmHellPlantationPhase.READY -> 0
    }.coerceAtLeast(0)

    fun toggleHeat(current: FarmHellGreenhouseState, index: Int, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        if (current.layoutVersion != 2 || current.finished || index !in current.plots.indices) return FarmHellGreenhouseResult(current, false)
        val old = current.plots[index]
        val plot = old.copy(heating = !old.heating, coolingSeconds = 0)
        return FarmHellGreenhouseResult(current.copy(plots = current.plots.updated(index, plot)), true)
    }

    fun harvest(current: FarmHellGreenhouseState, index: Int, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        if (current.layoutVersion != 2 || current.finished || index !in current.plots.indices || phase(current.plots[index]) != FarmHellPlantationPhase.READY) return FarmHellGreenhouseResult(current, false)
        val done = current.cooled + 1 >= rules.quota
        return FarmHellGreenhouseResult(current.copy(
            plots = current.plots.updated(index, FarmHellPlantationPlot()), cooled = current.cooled + 1,
            harvested = emptySet(), finished = done,
        ), true, contribution = 1, finished = done, successful = done)
    }

    fun second(current: FarmHellGreenhouseState, participants: Set<UUID>, rules: FarmHellGreenhouseRules): FarmHellGreenhouseResult {
        validate(current)
        if (current.finished || participants.isEmpty()) return FarmHellGreenhouseResult(current, false)
        if (current.layoutVersion != 2) return FarmHellGreenhouseResult(current.copy(elapsedSeconds = (current.elapsedSeconds + 1).coerceAtMost(1_000_000)), true)
        val scorched = linkedSetOf<Int>()
        val plots = current.plots.mapIndexed { index, plot -> when {
            plot.heating && plot.growthSeconds < GROWTH_TARGET_SECONDS -> plot.copy(growthSeconds = plot.growthSeconds + 1)
            plot.heating -> {
                val overheat = plot.overheatSeconds + 1
                if (overheat >= OVERHEAT_SECONDS) { scorched += index; FarmHellPlantationPlot() } else plot.copy(overheatSeconds = overheat)
            }
            plot.growthSeconds >= GROWTH_TARGET_SECONDS && plot.coolingSeconds < COOLING_SECONDS -> plot.copy(coolingSeconds = plot.coolingSeconds + 1, overheatSeconds = 0)
            else -> plot
        } }
        return FarmHellGreenhouseResult(current.copy(plots = plots, elapsedSeconds = (current.elapsedSeconds + 1).coerceAtMost(1_000_000)), true, scorchedPlots = scorched)
    }

    fun validate(state: FarmHellGreenhouseState) {
        require(state.layoutVersion in 0..2) { "Unsupported hell greenhouse layout" }
        require(state.points.size in 4..16 && state.points.distinct().size == state.points.size && state.points.all(::validPoint)) { "Hell greenhouse points are invalid" }
        require(state.points.map(FarmPointPosition::world).distinct().size == 1) { "Hell greenhouse points must share a world" }
        require(state.entrance == null || (validPoint(state.entrance) && state.entrance.world == state.points.first().world)) { "Hell greenhouse entrance is invalid" }
        require(state.elapsedSeconds in 0..1_000_000 && state.heat in 0..1_000_000 && state.cooled in 0..PHYSICAL_PLOTS * 4) { "Hell greenhouse progress is invalid" }
        require(state.evacuationSeconds == null || state.evacuationSeconds in 0..86_400) { "Hell greenhouse evacuation is invalid" }
        if (state.finished) require(state.carried.isEmpty()) { "Finished hell greenhouse cannot carry legacy peppers" }
        if (state.layoutVersion <= 1) {
            require(state.harvested.all { it in state.points.indices })
            require(state.cooled <= state.harvested.size)
            require(state.carried.values.all { it.index in state.points.indices && it.index in state.harvested && it.expiresAt >= 0 })
            require(state.carried.values.map(FarmHellPepper::index).distinct().size == state.carried.size)
            require(state.cooled + state.carried.size <= state.harvested.size)
        } else {
            require(state.points.size == PHYSICAL_PLOTS && state.plots.size == PHYSICAL_PLOTS)
            require(state.carried.isEmpty()) { "Plantation layout cannot carry legacy peppers" }
            require(state.plots.all { it.growthSeconds in 0..GROWTH_TARGET_SECONDS && it.coolingSeconds in 0..COOLING_SECONDS && it.overheatSeconds in 0..OVERHEAT_SECONDS })
            require(state.plots.all { plot ->
                (plot.coolingSeconds == 0 || plot.growthSeconds == GROWTH_TARGET_SECONDS) &&
                    (plot.overheatSeconds == 0 || (plot.heating && plot.growthSeconds == GROWTH_TARGET_SECONDS)) &&
                    !(plot.heating && plot.coolingSeconds > 0)
            })
            require(state.harvested.all { it in 0 until PHYSICAL_PLOTS })
        }
    }

    private fun validPoint(point: FarmPointPosition): Boolean = POINT_WORLD_ID.matches(point.world) && listOf(point.x, point.y, point.z).all(Double::isFinite) &&
        point.x in -30_000_000.0..30_000_000.0 && point.z in -30_000_000.0..30_000_000.0 && point.y in -2_048.0..2_048.0 && point.yaw.isFinite() && point.pitch.isFinite() && point.pitch in -90f..90f

    private fun <T> List<T>.updated(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
}

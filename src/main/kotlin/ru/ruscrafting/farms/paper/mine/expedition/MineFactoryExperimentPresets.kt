package ru.ruscrafting.farms.paper.mine.expedition

import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiment
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperimentPlan
import ru.ruscrafting.farms.domain.mine.expedition.MineFactoryExperiments

/** An admin selection is consumed only when a new factory state is initialized. */
internal class MineFactoryExperimentPresets {
    private val next = mutableMapOf<String, Set<MineFactoryExperiment>>()

    fun configure(zone: String, preset: String): Boolean {
        val selected = when (preset) {
            "random" -> { next.remove(zone); return true }
            "none" -> emptySet()
            "all" -> MineFactoryExperiments.supported
            else -> SINGLE[preset]?.let(::setOf) ?: return false
        }
        next[zone] = selected
        return true
    }

    fun consume(zone: String, seed: Long): MineFactoryExperimentPlan =
        MineFactoryExperiments.select(seed, next.remove(zone))

    fun clear() = next.clear()

    private companion object {
        val SINGLE = mapOf(
            "rock" to MineFactoryExperiment.ROCK_JAM,
            "crane" to MineFactoryExperiment.MANUAL_CRANE,
            "cooling" to MineFactoryExperiment.COOLING,
        )
    }
}

package ru.ruscrafting.farms.domain.mine.crystal

import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineResource
import ru.ruscrafting.farms.domain.MineRules
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftEvent
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.util.UUID

enum class MineCrystalPhase { CHARGING, CONVERGING, DRILLING, EXTRACTING }

data class MineCrystalCut(val position: WorksitePosition, val material: String)

/** The four charged targets remain in the ordinary objective; only the beam's wall and beat live here. */
data class MineCrystalState(
    val cuts: List<MineCrystalCut>,
    val faceX: Int,
    val faceZ: Int,
    val phase: MineCrystalPhase = MineCrystalPhase.CHARGING,
    val phaseStartedAt: Long = 0,
    val operator: UUID? = null,
) {
    init { validate() }

    fun validate() {
        // Gson can bypass Kotlin constructors and fill non-null fields with null.
        val savedCuts = requireNotNull(cuts as List<MineCrystalCut?>?)
        val savedPhase = requireNotNull(phase as MineCrystalPhase?)
        require(savedCuts.size in 1..4)
        val positions = savedCuts.map { savedCut ->
            val cut = requireNotNull(savedCut)
            val position = requireNotNull(cut.position as WorksitePosition?)
            val material = requireNotNull(cut.material as String?)
            require(!requireNotNull(position.world as String?).isBlank())
            require(material in setOf("STONE", "DEEPSLATE") || material.endsWith("_ORE") &&
                MineResource.fromMaterial(material) in MineResource.knownResources)
            position
        }
        require(positions.distinct().size == savedCuts.size && positions.map { it.world }.distinct().size == 1)
        require((faceX to faceZ) in setOf(1 to 0, -1 to 0, 0 to 1, 0 to -1))
        val anchor = positions.first()
        require(positions.all { position ->
            kotlin.math.abs(position.y.toLong() - anchor.y) <= 1 &&
                if (faceX != 0) position.x == anchor.x && kotlin.math.abs(position.z.toLong() - anchor.z) <= 1
                else position.z == anchor.z && kotlin.math.abs(position.x.toLong() - anchor.x) <= 1
        })
        require(phaseStartedAt >= 0)
        require(savedPhase == MineCrystalPhase.CHARGING || operator != null)
    }
}

internal object MineCrystalCompletion {
    /**
     * Result, incident retirement and cursor advance share ONE persisted shift snapshot.
     * Recovery loads either the unfinished incident or the already credited order, never a
     * separate replayable item/payment operation. There are no drops, XP or currency grants.
     */
    fun finish(current: MineShiftState, rules: MineRules, requirements: Map<String, Int>): EngineResult<MineShiftState, MineShiftEvent> {
        val incident = current.incident ?: return EngineResult(current, false)
        val crystal = incident.crystal ?: return EngineResult(current, false)
        val operator = crystal.operator ?: return EngineResult(current, false)
        if (incident.type != MineIncidentType.CRYSTAL_RESONANCE || crystal.phase != MineCrystalPhase.EXTRACTING ||
            incident.required != 4 || current.objective?.completed != 4) return EngineResult(current, false)
        val resolved = MineShiftEngine.resolveIncident(current)
        if (!resolved.accepted) return resolved
        var result = resolved
        repeat(crystal.cuts.size) {
            if (result.state.phase != MinePhase.MINING) return@repeat
            val missing = MineResource.normalizeRequirements(requirements).entries.firstOrNull { (resource, count) ->
                (MineResource.normalizeProgress(result.state.minedByMaterial)[resource] ?: 0) < count
            }?.key
            val next = if (requirements.isEmpty()) MineShiftEngine.mineTarget(result.state, rules, operator)
                else missing?.let { MineShiftEngine.mineTarget(result.state, rules, operator, it, requirements) }
                    ?: return@repeat
            if (next.accepted) result = next.copy(
                contribution = result.contribution + next.contribution,
                events = result.events + next.events,
            )
        }
        return result
    }
}

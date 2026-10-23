package ru.ruscrafting.farms.persistence

import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.mine.crystal.MineCrystalPhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus

/** Gson does not call the domain constructors when recovering a stored shift. */
internal fun validateMineCrystal(mine: MineShiftState) {
    val incident = mine.incident ?: return
    val crystal = incident.crystal ?: return // Pre-beam incidents are upgraded by their controller.
    crystal.validate()
    crystal.cuts.forEach { it.position.copy() }
    require(mine.phase == MinePhase.INCIDENT && incident.type == MineIncidentType.CRYSTAL_RESONANCE &&
        incident.working == null && incident.expedition == null) { "Crystal state belongs to an incompatible mine incident" }
    val objective = requireNotNull(mine.objective) { "Crystal incident has no objective" }
    objective.copy() // Recheck quota and target uniqueness after Gson recovery.
    require(incident.required == 4 && objective.required == 4 && objective.targets.size == 4 &&
        incident.progress == objective.completed) { "Crystal incident progress is inconsistent" }
    require(objective.key.objectiveId == "incident_crystal_resonance" && objective.key.sequence == mine.sequence) {
        "Crystal objective identity is inconsistent"
    }
    objective.key.copy()
    objective.targets.forEach { target ->
        target.copy()
        target.position.copy()
        require(target.status in ObjectiveTargetStatus.entries && target.role.value == "crystal_node" &&
            target.position.world == crystal.cuts.first().position.world) { "Crystal target is invalid" }
    }
    require(crystal.phase == MineCrystalPhase.CHARGING || objective.completed == 4) {
        "Crystal beam started before all four targets were charged"
    }
}

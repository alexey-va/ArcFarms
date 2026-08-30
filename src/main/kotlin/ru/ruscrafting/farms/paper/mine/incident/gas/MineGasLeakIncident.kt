package ru.ruscrafting.farms.paper.mine.incident.gas

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.sequence.MineSequenceIncident
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex

internal class MineGasLeakIncident(
    registry: MineRuntimeRegistry, index: MineBlockIndex, incidents: MineIncidentCoordinator,
) : MineSequenceIncident(MineIncidentType.GAS_LEAK, MineAnchorRole.VENT, "gas_vent", registry, index, incidents) {
    fun useVent(runtime: MineRuntime, targetId: String, player: Player): Boolean = use(runtime, targetId, player)
}

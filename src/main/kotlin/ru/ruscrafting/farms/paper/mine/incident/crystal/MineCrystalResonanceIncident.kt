package ru.ruscrafting.farms.paper.mine.incident.crystal

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.sequence.MineSequenceIncident
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex

internal class MineCrystalResonanceIncident(
    registry: MineRuntimeRegistry, index: MineBlockIndex, incidents: MineIncidentCoordinator,
) : MineSequenceIncident(
    MineIncidentType.CRYSTAL_RESONANCE, MineAnchorRole.CRYSTAL, "crystal_node", registry, index, incidents,
) {
    fun hit(runtime: MineRuntime, targetId: String, player: Player, insideForgivingWindow: Boolean): Boolean =
        use(runtime, targetId, player, insideForgivingWindow)
}

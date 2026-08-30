package ru.ruscrafting.farms.paper.mine.incident.track

import org.bukkit.Material
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.construction.MineConstructionIncident
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems

internal class MineTrackDamageIncident(
    registry: MineRuntimeRegistry, index: MineBlockIndex, incidents: MineIncidentCoordinator,
    items: WorksiteServiceItems?, state: WorksiteStatePort,
) : MineConstructionIncident(
    MineIncidentType.TRACK_DAMAGE, MineAnchorRole.RAIL, "track_kit", Material.RAIL,
    registry, index, incidents, items, state,
)

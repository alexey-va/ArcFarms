package ru.ruscrafting.farms.paper.mine.incident.cavein

import org.bukkit.Material
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.construction.MineConstructionIncident
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems

internal class MineCaveInIncident(
    registry: MineRuntimeRegistry, index: MineBlockIndex, incidents: MineIncidentCoordinator,
    items: WorksiteServiceItems?, state: WorksiteStatePort,
) : MineConstructionIncident(
    MineIncidentType.CAVE_IN, MineAnchorRole.SUPPORT, "support_kit", Material.SCAFFOLDING,
    registry, index, incidents, items, state,
)

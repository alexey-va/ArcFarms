package ru.ruscrafting.farms.domain

import ru.ruscrafting.farms.domain.placement.WorksitePlacementPoint

internal fun FarmPlotPosition.toWorksitePlacementPoint(): WorksitePlacementPoint =
    WorksitePlacementPoint(world, x.toDouble(), y.toDouble(), z.toDouble())

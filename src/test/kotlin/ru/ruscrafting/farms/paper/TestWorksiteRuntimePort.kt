package ru.ruscrafting.farms.paper

import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteNetworkPort
import ru.ruscrafting.farms.paper.worksite.WorksitePorts
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort

/** Test-fixture convenience only; production owners must depend on narrow ports. */
internal interface WorksiteRuntimePort :
    WorksiteAccessPort,
    WorksiteAudiencePort,
    WorksiteStatePort,
    WorksiteTaskPort,
    WorksiteStatsPort,
    WorksiteNetworkPort

internal fun WorksiteRuntimePort.asWorksitePorts(): WorksitePorts = WorksitePorts(
    access = this,
    audience = this,
    state = this,
    tasks = this,
    stats = this,
    network = this,
)

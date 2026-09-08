package ru.ruscrafting.farms.paper.mine

import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.MineBlockEffects
import ru.ruscrafting.farms.paper.PaperMineBlockEffects
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.asWorksitePorts
import ru.ruscrafting.farms.paper.mine.extraction.MineCartEffects
import ru.ruscrafting.farms.paper.mine.extraction.PaperMineCartEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.PaperMineIncidentEntityEffects
import ru.ruscrafting.farms.paper.worksite.WorksiteRewardGrantService
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import java.util.random.RandomGenerator

internal fun testMineComponentGraph(
    plugin: Plugin,
    regions: RegionGateway,
    port: WorksiteRuntimePort,
    clock: () -> Long,
    journal: MineRecoveryJournal,
    random: RandomGenerator = RandomGenerator.getDefault(),
    blockEffects: MineBlockEffects = PaperMineBlockEffects,
    serviceItems: WorksiteServiceItems? = null,
    locale: ArcFarmsLocale? = null,
    cartEffects: MineCartEffects = PaperMineCartEffects(plugin),
    incidentEntityEffects: MineIncidentEntityEffects = PaperMineIncidentEntityEffects(plugin),
    rewardGrants: WorksiteRewardGrantService? = null,
) = MineComponentGraph(
    plugin,
    "spawn",
    regions,
    port.asWorksitePorts(),
    clock,
    journal,
    random,
    blockEffects,
    serviceItems,
    locale,
    cartEffects,
    incidentEntityEffects,
    rewardGrants,
)

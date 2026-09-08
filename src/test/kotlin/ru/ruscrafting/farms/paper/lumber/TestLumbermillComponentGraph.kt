package ru.ruscrafting.farms.paper.lumber

import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.asWorksitePorts
import ru.ruscrafting.farms.paper.lumber.skidding.LumberBundleEffects
import ru.ruscrafting.farms.paper.lumber.skidding.PaperLumberBundleEffects
import ru.ruscrafting.farms.paper.lumber.stacking.LumberStackingEffects
import ru.ruscrafting.farms.paper.lumber.stacking.PaperLumberStackingEffects
import ru.ruscrafting.farms.paper.worksite.WorksiteRewardGrantService
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.persistence.LumberRecoveryJournal

internal fun testLumbermillComponentGraph(
    plugin: Plugin,
    regions: RegionGateway,
    port: WorksiteRuntimePort,
    clock: () -> Long,
    journal: LumberRecoveryJournal,
    serviceItems: WorksiteServiceItems? = null,
    bundleEffects: LumberBundleEffects = PaperLumberBundleEffects(plugin),
    stackingEffects: LumberStackingEffects = PaperLumberStackingEffects(plugin),
    lostLoadEffects: LumberBundleEffects = PaperLumberBundleEffects(plugin, "lumber_lost"),
    locale: ArcFarmsLocale? = null,
    rewardGrants: WorksiteRewardGrantService? = null,
) = LumbermillComponentGraph(
    plugin,
    "spawn",
    regions,
    port.asWorksitePorts(),
    clock,
    journal,
    serviceItems,
    bundleEffects,
    stackingEffects,
    lostLoadEffects,
    locale,
    rewardGrants,
)

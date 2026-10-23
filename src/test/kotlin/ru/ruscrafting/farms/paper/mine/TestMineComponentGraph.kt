package ru.ruscrafting.farms.paper.mine

import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.MineBlockEffects
import ru.ruscrafting.farms.paper.ArcFarmsDebug
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
    debug: ArcFarmsDebug = ArcFarmsDebug({ false }) {},
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
    debug,
    random,
    blockEffects,
    serviceItems,
    locale,
    cartEffects,
    incidentEntityEffects,
    rewardGrants,
    tickets = object : ru.ruscrafting.farms.paper.mine.index.MineChunkTicket {
        override fun retain(chunk: org.bukkit.Chunk): Boolean = true
        override fun release(chunk: org.bukkit.Chunk) = Unit
    },
    creatureNavigation = object : ru.ruscrafting.farms.paper.mine.incident.creature.MineCreatureNavigation {
        // MockBukkit has no native pathfinder or goal selector. Selection and floor validation have separate tests.
        override fun initialize(mob: org.bukkit.entity.Mob) = Unit
        override fun stop(mob: org.bukkit.entity.Mob) = Unit
        override fun chase(mob: org.bukkit.entity.Mob, destination: org.bukkit.Location, runtime: MineRuntime, floorY: Int) = false
        override fun roam(mob: org.bukkit.entity.Mob, anchor: ru.ruscrafting.farms.domain.worksite.WorksitePosition, runtime: MineRuntime, step: Long) = false
    },
    blockDataDecoder = ru.ruscrafting.farms.paper.fixtures.MockBukkitFarmBlockDataDecoder,
)

internal fun MineComponentGraph.prewarmIncidentCandidates(runtime: MineRuntime, now: Long = 1_000L) {
    repeat(100) { candidateStock.prewarm(runtime, now) }
}

/** Models Paper event ordering only; this is not native-client or platform acceptance. */
internal fun MineComponentGraph.breakManagedOre(block: org.bukkit.block.Block, player: org.bukkit.entity.Player): org.bukkit.event.block.BlockBreakEvent {
    mining.prepareHit(player, block)
    val event = org.bukkit.event.block.BlockBreakEvent(block, player)
    module.onBreakLowest(event)
    module.onBreakHigh(event)
    if (!event.isCancelled) {
        module.onBreakMonitor(event)
        block.setType(org.bukkit.Material.AIR, false)
    }
    return event
}

package ru.ruscrafting.farms.paper.mine

import net.kyori.adventure.text.Component
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.worksite.WorksitePorts
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import ru.ruscrafting.farms.paper.MineBlockEffects
import ru.ruscrafting.farms.paper.PaperMineBlockEffects
import ru.ruscrafting.farms.paper.mine.prospecting.MineProspectingController
import ru.ruscrafting.farms.paper.mine.mining.MineMiningController
import ru.ruscrafting.farms.paper.mine.loading.MineLoadingController
import ru.ruscrafting.farms.paper.mine.extraction.MineCartEffects
import ru.ruscrafting.farms.paper.mine.extraction.MineCartScene
import ru.ruscrafting.farms.paper.mine.extraction.MineExtractionController
import ru.ruscrafting.farms.paper.mine.extraction.PaperMineCartEffects
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentSet
import ru.ruscrafting.farms.paper.mine.incident.cavein.MineCaveInIncident
import ru.ruscrafting.farms.paper.mine.incident.track.MineTrackDamageIncident
import ru.ruscrafting.farms.paper.mine.incident.gas.MineGasLeakIncident
import ru.ruscrafting.farms.paper.mine.incident.crystal.MineCrystalResonanceIncident
import ru.ruscrafting.farms.paper.mine.incident.flood.MineFloodingIncident
import ru.ruscrafting.farms.paper.mine.incident.power.MinePowerFailureIncident
import ru.ruscrafting.farms.paper.mine.incident.creature.MineCreatureNestIncident
import ru.ruscrafting.farms.paper.mine.incident.rescue.MineLostMinerIncident
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.PaperMineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.recovery.MineIncidentBlockJournal
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.worksite.WorksiteRewardGrantService
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentScheduler
import ru.ruscrafting.farms.paper.mine.presentation.MineGuidanceSource
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidancePresenter
import ru.ruscrafting.farms.paper.mine.admin.MineAdminService
import java.util.random.RandomGenerator
import ru.ruscrafting.farms.paper.mine.lift.MineLiftAccess

/** Composition-only graph; the registry remains the sole V2 runtime collection owner. */
internal class MineComponentGraph(
    plugin: Plugin,
    serverId: String,
    regions: RegionGateway,
    ports: WorksitePorts,
    clock: () -> Long,
    journal: MineRecoveryJournal,
    random: RandomGenerator = RandomGenerator.getDefault(),
    blockEffects: MineBlockEffects = PaperMineBlockEffects,
    serviceItems: WorksiteServiceItems? = null,
    locale: ArcFarmsLocale? = null,
    cartEffects: MineCartEffects = PaperMineCartEffects(plugin),
    incidentEntityEffects: MineIncidentEntityEffects = PaperMineIncidentEntityEffects(plugin),
    rewardGrants: WorksiteRewardGrantService? = null,
    internal val lift: MineLiftAccess? = null,
) {
    internal val registry = MineRuntimeRegistry()
    val recovery = MineBlockRecoveryController(journal, ports.access, ports.state, ports.tasks, clock)
    val index = MineBlockIndex(plugin)
    private val transitions = MineTransitionCoordinator(ports.state, ports.stats)
    private val incidents = MineIncidentCoordinator(transitions, ports.state)
    private val incidentJournal = MineIncidentBlockJournal(recovery)
    val caveIn = MineCaveInIncident(registry, index, incidents, serviceItems, ports.state)
    val trackDamage = MineTrackDamageIncident(registry, index, incidents, serviceItems, ports.state)
    val gasLeak = MineGasLeakIncident(registry, index, incidents)
    val crystalResonance = MineCrystalResonanceIncident(registry, index, incidents)
    val flooding = MineFloodingIncident(registry, index, incidents, incidentJournal, serviceItems, ports.state)
    val powerFailure = MinePowerFailureIncident(registry, index, incidents, incidentJournal)
    val cartScene = MineCartScene(cartEffects)
    val extraction = MineExtractionController(
        registry, serverId, index, cartScene, transitions, ports.access, ports.audience, ports.stats, ports.network, clock, rewardGrants,
    )
    val loading = MineLoadingController(
        registry, index, extraction, transitions, serviceItems, locale, ports.access, ports.audience, ports.state, clock,
    )
    val creatureNest = MineCreatureNestIncident(registry, index, incidents, incidentEntityEffects)
    val lostMiner = MineLostMinerIncident(registry, index, incidents, incidentEntityEffects, extraction::deliveryPoint)
    val scenarioRooms = ru.ruscrafting.farms.paper.mine.incident.scenario.MineScenarioRooms(
        plugin, ports.tasks, ports.access, ports.state,
        ru.ruscrafting.farms.paper.platform.PaperFarmBlockDataDecoder,
        ru.ruscrafting.farms.paper.farm.care.mole.PaperMoleBurrowChunkRetention(plugin),
        ru.ruscrafting.farms.paper.platform.PaperFarmRouteChunkLoader, locale,
    )
    val scenarios = ru.ruscrafting.farms.paper.mine.incident.scenario.MineScenarioController(
        registry, scenarioRooms, incidents, ports.access, ports.state, locale, lift,
        ru.ruscrafting.farms.paper.mine.incident.entity.MineScenarioActors(incidentEntityEffects),
        PaperMineCartEffects(plugin, "mine_scenario_cart", ru.ruscrafting.farms.config.MineCartVisualSettings()),
        ports.audience, ru.ruscrafting.farms.paper.mine.incident.scenario.MineScenarioEnvironment(
            ru.ruscrafting.farms.paper.platform.PaperFarmBlockDataDecoder),
        ru.ruscrafting.farms.paper.mine.incident.scenario.MineScenarioCargo(plugin),
    )
    val incidentScheduler = MineIncidentScheduler(
        caveIn, gasLeak, flooding, trackDamage, crystalResonance, creatureNest, powerFailure, lostMiner, scenarios,
    )
    val incidentSet = MineIncidentSet(
        registry, caveIn, trackDamage, gasLeak, crystalResonance, flooding, powerFailure, creatureNest, lostMiner,
        incidentScheduler, scenarios,
    )
    val guidance = MineGuidanceSource(
        registry, ports.audience, locale, extraction::guidanceTarget, { extraction.routeFor(it)?.finalIndex ?: 1 }, scenarioRooms,
    )
    private val guidancePresenter = WorksiteGuidancePresenter(ports.audience, ports.access, guidance)
    val prospecting = MineProspectingController(
        registry, index, recovery, transitions, ports.access, ports.audience, clock, loading::canStage,
    ) { runtime, player ->
        locale?.renderPath("route.mine.${runtime.settings.id}", player) ?: Component.text(runtime.settings.id)
    }
    val veins = ru.ruscrafting.farms.paper.mine.mining.MineVeinController(index, recovery, ports.state, clock)
    val mining = MineMiningController(
        registry, index, recovery, transitions, ports.access, ports.audience, ports.state, ports.tasks, clock, random, blockEffects, loading::begin,
    )
    private val tickets = object : MineChunkTicket {
        override fun retain(chunk: org.bukkit.Chunk): Boolean = chunk.addPluginChunkTicket(plugin)
        override fun release(chunk: org.bukkit.Chunk) {
            chunk.removePluginChunkTicket(plugin)
        }
    }
    val admin = MineAdminService(
        registry, index, tickets, prospecting, extraction, incidentScheduler, ports.state,
    )
    val module = MineModule(
        regions, ports.access, ports.audience, ports.tasks, transitions, registry, recovery, index, tickets, prospecting, mining, loading, extraction, cartScene,
        incidentSet, guidancePresenter, admin, clock, scenarios, veins,
    )

    internal val mutableRuntimeCollectionCount: Int = 1
}

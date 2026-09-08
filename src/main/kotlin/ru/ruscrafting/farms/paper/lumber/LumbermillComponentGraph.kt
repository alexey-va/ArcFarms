package ru.ruscrafting.farms.paper.lumber

import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.worksite.WorksitePorts
import ru.ruscrafting.farms.paper.lumber.index.LumberBlockIndex
import ru.ruscrafting.farms.paper.lumber.index.LumberChunkTicket
import ru.ruscrafting.farms.paper.lumber.recovery.LumberBlockRecoveryController
import ru.ruscrafting.farms.paper.lumber.felling.LumberFellingController
import ru.ruscrafting.farms.paper.lumber.skidding.LumberBundleEffects
import ru.ruscrafting.farms.paper.lumber.skidding.LumberBundleScene
import ru.ruscrafting.farms.paper.lumber.skidding.LumberSkiddingController
import ru.ruscrafting.farms.paper.lumber.skidding.PaperLumberBundleEffects
import ru.ruscrafting.farms.paper.lumber.sawing.LumberSawingController
import ru.ruscrafting.farms.paper.lumber.stacking.LumberStackingController
import ru.ruscrafting.farms.paper.lumber.stacking.LumberStackingEffects
import ru.ruscrafting.farms.paper.lumber.stacking.LumberStackingScene
import ru.ruscrafting.farms.paper.lumber.stacking.PaperLumberStackingEffects
import ru.ruscrafting.farms.paper.lumber.dispatch.LumberDispatchController
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentCoordinator
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentSet
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentScheduler
import ru.ruscrafting.farms.paper.lumber.incident.windthrow.LumberWindthrowIncident
import ru.ruscrafting.farms.paper.lumber.incident.beetle.LumberBarkBeetleIncident
import ru.ruscrafting.farms.paper.lumber.incident.jam.LumberSawJamIncident
import ru.ruscrafting.farms.paper.lumber.incident.warped.LumberWarpedBatchIncident
import ru.ruscrafting.farms.paper.lumber.incident.conveyor.LumberConveyorIncident
import ru.ruscrafting.farms.paper.lumber.incident.load.LumberLostLoadIncident
import ru.ruscrafting.farms.paper.lumber.incident.fire.LumberForestFireIncident
import ru.ruscrafting.farms.paper.lumber.incident.rush.LumberRushOrderIncident
import ru.ruscrafting.farms.persistence.LumberRecoveryJournal
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.paper.lumber.presentation.LumberGuidanceSource
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidancePresenter
import ru.ruscrafting.farms.paper.worksite.WorksiteRewardGrantService
import ru.ruscrafting.farms.paper.lumber.admin.LumberAdminService

/** Composition-only graph; the registry is the sole mutable runtime collection owner. */
internal class LumbermillComponentGraph(
    plugin: Plugin,
    serverId: String,
    regions: RegionGateway,
    ports: WorksitePorts,
    clock: () -> Long,
    journal: LumberRecoveryJournal,
    serviceItems: WorksiteServiceItems? = null,
    bundleEffects: LumberBundleEffects = PaperLumberBundleEffects(plugin),
    stackingEffects: LumberStackingEffects = PaperLumberStackingEffects(plugin),
    lostLoadEffects: LumberBundleEffects = PaperLumberBundleEffects(plugin, "lumber_lost"),
    locale: ArcFarmsLocale? = null,
    rewardGrants: WorksiteRewardGrantService? = null,
) {
    internal val registry = LumberRuntimeRegistry()
    internal val clock = clock
    val index = LumberBlockIndex(plugin)
    val recovery = LumberBlockRecoveryController(journal, ports.state, ports.tasks, clock)
    private val transitions = LumberTransitionCoordinator(ports.state, ports.stats)
    val incidents = LumberIncidentCoordinator(transitions, ports.state)
    val bundleScene = LumberBundleScene(registry, bundleEffects, transitions, ports.access, ports.state, clock)
    val skidding = LumberSkiddingController(registry, bundleScene, ports.access)
    val stackingScene = LumberStackingScene(registry, stackingEffects, transitions, ports.access)
    val stacking = LumberStackingController(registry, stackingScene, ports.access, ports.audience)
    val sawing = LumberSawingController(
        registry,
        transitions,
        ports.access,
        ports.audience,
        ports.state,
        clock,
        stackingScene::begin,
        stackingScene::reconcile,
    )
    val dispatch = LumberDispatchController(
        registry, serverId, transitions, ports.access, ports.audience, ports.stats, ports.network, clock, rewardGrants,
    )
    val windthrow = LumberWindthrowIncident(registry, index, recovery, incidents, ports.state)
    val beetles = LumberBarkBeetleIncident(registry, index, incidents)
    val sawJam = LumberSawJamIncident(registry, incidents, ports.audience)
    val warped = LumberWarpedBatchIncident(registry, incidents, ports.audience)
    val conveyor = LumberConveyorIncident(registry, incidents, serviceItems, ports.state)
    val lostLoad = LumberLostLoadIncident(registry, incidents, lostLoadEffects, ports.state, clock)
    val fire = LumberForestFireIncident(registry, incidents, recovery, serviceItems, ports.state, ports.tasks)
    val rush = LumberRushOrderIncident(ports.state)
    val guidance = LumberGuidanceSource(registry, ports.audience, locale)
    private val guidancePresenter = WorksiteGuidancePresenter(ports.audience, ports.access, guidance)
    val incidentScheduler = LumberIncidentScheduler(
        windthrow, beetles, sawJam, conveyor, fire, lostLoad, warped, rush, ports.state,
    )
    val incidentSet = LumberIncidentSet(
        windthrow, beetles, sawJam, conveyor, lostLoad, fire, rush, warped, incidentScheduler,
    )
    val felling = LumberFellingController(
        registry,
        index,
        recovery,
        transitions,
        ports.access,
        ports.audience,
        ports.state,
        clock,
        bundleScene::begin,
        bundleScene::canStage,
        bundleScene::reconcile,
    )
    private val tickets = object : LumberChunkTicket {
        override fun retain(chunk: org.bukkit.Chunk): Boolean = chunk.addPluginChunkTicket(plugin)
        override fun release(chunk: org.bukkit.Chunk) {
            chunk.removePluginChunkTicket(plugin)
        }
    }
    val admin = LumberAdminService(registry, index, tickets, felling, incidentScheduler)
    val module = LumbermillModule(
        regions, ports.access, ports.state, ports.tasks, registry, index, recovery, tickets, felling, skidding, bundleScene,
        sawing, stacking, stackingScene, dispatch, incidentSet, guidancePresenter, admin, clock,
    )

    internal val mutableRuntimeCollectionCount: Int = 1
}

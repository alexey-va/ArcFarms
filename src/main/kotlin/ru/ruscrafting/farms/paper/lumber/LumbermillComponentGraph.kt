package ru.ruscrafting.farms.paper.lumber

import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
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
import ru.ruscrafting.farms.paper.lumber.incident.windthrow.LumberWindthrowIncident
import ru.ruscrafting.farms.paper.lumber.incident.beetle.LumberBarkBeetleIncident
import ru.ruscrafting.farms.paper.lumber.incident.jam.LumberSawJamIncident
import ru.ruscrafting.farms.paper.lumber.incident.warped.LumberWarpedBatchIncident
import ru.ruscrafting.farms.persistence.LumberRecoveryJournal

/** Composition-only graph; the registry is the sole mutable runtime collection owner. */
internal class LumbermillComponentGraph(
    plugin: Plugin,
    regions: RegionGateway,
    port: WorksiteRuntimePort,
    clock: () -> Long,
    journal: LumberRecoveryJournal,
    bundleEffects: LumberBundleEffects = PaperLumberBundleEffects(plugin),
    stackingEffects: LumberStackingEffects = PaperLumberStackingEffects(plugin),
) {
    internal val registry = LumberRuntimeRegistry()
    internal val clock = clock
    val index = LumberBlockIndex(plugin)
    val recovery = LumberBlockRecoveryController(journal, port, clock)
    private val transitions = LumberTransitionCoordinator(port)
    val incidents = LumberIncidentCoordinator(transitions, port)
    val bundleScene = LumberBundleScene(registry, bundleEffects, transitions, port, clock)
    val skidding = LumberSkiddingController(registry, bundleScene, port)
    val stackingScene = LumberStackingScene(registry, stackingEffects, transitions, port)
    val stacking = LumberStackingController(registry, stackingScene, port)
    val sawing = LumberSawingController(
        registry,
        transitions,
        port,
        clock,
        stackingScene::begin,
        stackingScene::reconcile,
    )
    val dispatch = LumberDispatchController(registry, transitions, port, clock)
    val windthrow = LumberWindthrowIncident(registry, index, recovery, incidents, port)
    val beetles = LumberBarkBeetleIncident(registry, index, incidents, port)
    val sawJam = LumberSawJamIncident(registry, incidents, port)
    val warped = LumberWarpedBatchIncident(incidents)
    val felling = LumberFellingController(
        registry,
        index,
        recovery,
        transitions,
        port,
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
    val module = LumbermillModule(
        regions, port, registry, index, recovery, tickets, felling, skidding, bundleScene,
        sawing, stacking, stackingScene, dispatch, windthrow, beetles, sawJam,
    )

    internal val mutableRuntimeCollectionCount: Int = 1
}

package ru.ruscrafting.farms.paper.lumber

import org.bukkit.Chunk
import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.block.Block
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.WorksiteBlockBreakHandler
import ru.ruscrafting.farms.paper.WorksiteEntityInteractHandler
import ru.ruscrafting.farms.paper.WorksiteFastVisualHandler
import ru.ruscrafting.farms.paper.WorksiteGuidanceHandler
import ru.ruscrafting.farms.paper.ActivityBarKey
import ru.ruscrafting.farms.paper.WorksiteMoveHandler
import ru.ruscrafting.farms.paper.WorksiteBlockInteractHandler
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.lumber.index.LumberBlockIndex
import ru.ruscrafting.farms.paper.lumber.index.LumberChunkTicket
import ru.ruscrafting.farms.paper.lumber.index.LumberIndexDefinition
import ru.ruscrafting.farms.paper.lumber.index.LumberReindexJob
import ru.ruscrafting.farms.paper.lumber.recovery.LumberBlockRecoveryController
import ru.ruscrafting.farms.paper.lumber.felling.LumberFellingController
import ru.ruscrafting.farms.paper.lumber.skidding.LumberBundleScene
import ru.ruscrafting.farms.paper.lumber.skidding.LumberSkiddingController
import ru.ruscrafting.farms.paper.lumber.sawing.LumberSawingController
import ru.ruscrafting.farms.paper.lumber.stacking.LumberStackingController
import ru.ruscrafting.farms.paper.lumber.stacking.LumberStackingScene
import ru.ruscrafting.farms.paper.lumber.dispatch.LumberDispatchController
import ru.ruscrafting.farms.paper.lumber.incident.LumberIncidentSet
import ru.ruscrafting.farms.paper.worksite.WorksiteParticipantOwner
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemOwner
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidancePresenter
import ru.ruscrafting.farms.paper.lumber.admin.LumberAdminService
import java.util.UUID

internal class LumbermillModule(
    private val regions: RegionGateway,
    private val access: WorksiteAccessPort,
    private val state: WorksiteStatePort,
    private val tasks: WorksiteTaskPort,
    internal val registry: LumberRuntimeRegistry,
    internal val index: LumberBlockIndex,
    internal val recovery: LumberBlockRecoveryController,
    internal val tickets: LumberChunkTicket,
    private val felling: LumberFellingController,
    private val skidding: LumberSkiddingController,
    private val bundleScene: LumberBundleScene,
    private val sawing: LumberSawingController,
    private val stacking: LumberStackingController,
    private val stackingScene: LumberStackingScene,
    private val dispatch: LumberDispatchController,
    private val incidents: LumberIncidentSet,
    private val guidance: WorksiteGuidancePresenter,
    internal val admin: LumberAdminService,
    private val clock: () -> Long,
) : WorksiteModule<LumberShiftState>, WorksiteBlockBreakHandler, WorksiteEntityInteractHandler,
    WorksiteBlockInteractHandler, WorksiteMoveHandler, WorksiteFastVisualHandler, WorksiteParticipantOwner,
    WorksiteServiceItemOwner, WorksiteGuidanceHandler {
    override val kind: ActivityKind = ActivityKind.LUMBER
    override val zoneCount: Int get() = registry.size

    fun rebuild(
        configured: List<LumberZoneSettings>,
        persisted: Map<String, LumberShiftState>,
        cooldownMillis: Long,
    ) {
        registry.replace(LumberRuntimeFactory.build(configured, persisted, cooldownMillis, regions))
    }

    fun reconfigure(
        configured: List<LumberZoneSettings>,
        persisted: Map<String, LumberShiftState>,
        cooldownMillis: Long,
    ) {
        registry.reconfigure(LumberRuntimeFactory.build(configured, persisted, cooldownMillis, regions))
    }

    override fun states(): Map<String, LumberShiftState> =
        registry.snapshot().associate { it.settings.id to it.state }

    override fun statuses(): List<ActivityStatus> = registry.snapshot().map { runtime ->
        val (done, total) = progress(runtime)
        ActivityStatus(kind, runtime.settings.id, "phase.lumber.${runtime.state.phase.name.lowercase()}", "$done/$total")
    }

    override fun tick(now: Long) = registry.snapshot().forEach { runtime ->
        tasks.guarded("lumber_v2:${runtime.settings.id}") {
            if (runtime.state.phase == LumberPhase.IDLE) return@guarded
            val result = LumberShiftEngine.tick(runtime.state, runtime.rules(), now)
            if (result.accepted) {
                runtime.state = result.state
                state.persistAsync()
            }
            bundleScene.reconcile(runtime)
            stackingScene.reconcile(runtime)
            val participants = runtime.region.world.players.count {
                runtime.region.contains(it.location) || runtime.station.contains(it.location)
            }
            incidents.tick(runtime, participants, now)
        }
    }.also {
        tasks.guarded("lumber_v2_reindex") { admin.tickReindexes(REINDEX_BLOCKS_PER_TICK) }
        recovery.processDue(now)
    }

    override fun canAccess(player: Player): Boolean =
        registry.snapshot().any { access.hasAccess(player, it.settings.permission) }

    override fun onBreakHigh(event: BlockBreakEvent): Boolean =
        incidents.onBreak(event) || felling.onBreakHigh(event)

    override fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean =
        incidents.onInteract(event, clicked, player) ||
            sawing.onInteract(event, clicked, player) ||
            stacking.onInteract(event, clicked, player) ||
            dispatch.onInteract(event, clicked, player)

    override fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean =
        incidents.onInteractEntity(event) || skidding.onInteractEntity(event) || stacking.onInteractEntity(event)

    override fun onMove(from: Location, to: Location, player: Player): Boolean {
        val skiddingHandled = skidding.onMove(from, to, player)
        val incidentHandled = incidents.onMove(to, player)
        return stacking.onMove(to, player) || incidentHandled || skiddingHandled
    }

    override fun updateVisuals() {
        skidding.updateVisuals()
        stacking.updateVisuals()
        incidents.updateVisuals()
    }

    override fun updateGuidance(expectedBars: MutableSet<ActivityBarKey>) = guidance.updateHud(clock(), expectedBars)

    override fun emitGuidance() = guidance.emitParticles()

    override fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) {
        skidding.releasePlayer(player, reason)
        stacking.releasePlayer(player, reason)
        incidents.releasePlayer(player.uniqueId)
        guidance.releasePlayer(player)
    }

    override fun isActive(identity: ServiceItemIdentity): Boolean = incidents.isActive(identity)

    override fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        incidents.release(playerId, identity, reason)
    }

    override fun activateLoadedState() {
        registry.snapshot().forEach { runtime ->
            runtime.region.world.loadedChunks.forEach { chunk -> index.reconcileChunk(runtime.indexDefinition(), chunk) }
            bundleScene.reconcile(runtime)
            stackingScene.reconcile(runtime)
        }
        recovery.activateLoadedState()
    }

    override fun reconcileChunk(chunk: Chunk) {
        registry.snapshot().filter { it.region.world === chunk.world }.forEach { runtime ->
            index.reconcileChunk(runtime.indexDefinition(), chunk)
        }
        recovery.reconcileChunk(chunk)
    }

    override fun beforeReload(reason: String) {
        admin.cleanup()
        recovery.beforeReload(reason)
    }

    override fun cleanup(reason: String) {
        skidding.cleanup()
        stacking.cleanup()
        incidents.cleanup()
        admin.cleanup()
        recovery.cleanup(reason)
        index.clear()
    }

    fun reindex(zoneId: String): LumberReindexJob? = registry.byId(zoneId)?.let { runtime ->
        LumberReindexJob(runtime.indexDefinition(), index, tickets)
    }

    private fun progress(runtime: LumberRuntime): Pair<Int, Int> = when (runtime.state.phase) {
        LumberPhase.FELLING -> runtime.state.felled to runtime.rules().fellingQuota
        LumberPhase.SKIDDING -> runtime.state.skidded to runtime.rules().skiddingQuota
        LumberPhase.SAWING -> runtime.state.sawCuts to runtime.rules().sawingQuota
        LumberPhase.STACKING, LumberPhase.DISPATCH -> runtime.state.stacked to runtime.rules().stackingQuota
        LumberPhase.INCIDENT -> runtime.state.incident?.let { it.progress to it.required } ?: (0 to 1)
        LumberPhase.PROCESSING -> runtime.state.processed to runtime.rules().processingQuota
        LumberPhase.IDLE, LumberPhase.COOLDOWN -> 0 to 1
    }

    private fun LumberRuntime.indexDefinition() = LumberIndexDefinition(
        settings.id,
        region,
        settings.species.toSet(),
    )

    private companion object {
        const val REINDEX_BLOCKS_PER_TICK = 131_072
    }
}

package ru.ruscrafting.farms.paper.mine

import org.bukkit.Chunk
import org.bukkit.entity.Player
import org.bukkit.block.Block
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.Location
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.WorksiteBlockBreakHandler
import ru.ruscrafting.farms.paper.WorksiteBlockInteractHandler
import ru.ruscrafting.farms.paper.WorksiteMoveHandler
import ru.ruscrafting.farms.paper.WorksiteFastVisualHandler
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteTaskPort
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.index.MineChunkTicket
import ru.ruscrafting.farms.paper.mine.index.MineIndexDefinition
import ru.ruscrafting.farms.paper.mine.index.MineReindexJob
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController
import ru.ruscrafting.farms.paper.mine.prospecting.MineProspectingController
import ru.ruscrafting.farms.paper.mine.mining.MineMiningController
import ru.ruscrafting.farms.paper.mine.loading.MineLoadingController
import ru.ruscrafting.farms.paper.mine.extraction.MineCartScene
import ru.ruscrafting.farms.paper.mine.extraction.MineExtractionController
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentSet
import ru.ruscrafting.farms.paper.WorksiteEntityInteractHandler
import ru.ruscrafting.farms.paper.WorksiteEntityDeathHandler
import ru.ruscrafting.farms.paper.WorksiteGuidanceHandler
import ru.ruscrafting.farms.paper.ActivityBarKey
import ru.ruscrafting.farms.paper.worksite.WorksiteGuidancePresenter
import ru.ruscrafting.farms.paper.mine.admin.MineAdminService
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.paper.mine.admin.MineAdminStatus
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminReindexTick
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteParticipantOwner
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemOwner
import java.util.UUID

internal class MineModule(
    private val regions: RegionGateway,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val tasks: WorksiteTaskPort,
    private val transitions: MineTransitionCoordinator,
    internal val registry: MineRuntimeRegistry,
    internal val recovery: MineBlockRecoveryController,
    internal val index: MineBlockIndex,
    internal val tickets: MineChunkTicket,
    private val prospecting: MineProspectingController,
    private val mining: MineMiningController,
    private val loading: MineLoadingController,
    private val extraction: MineExtractionController,
    private val cartScene: MineCartScene,
    private val incidents: MineIncidentSet,
    private val guidance: WorksiteGuidancePresenter,
    internal val admin: MineAdminService,
    private val clock: () -> Long,
) : WorksiteModule<MineShiftState>, WorksiteBlockBreakHandler, WorksiteBlockInteractHandler,
    WorksiteMoveHandler, WorksiteEntityInteractHandler, WorksiteEntityDeathHandler, WorksiteFastVisualHandler,
    WorksiteParticipantOwner, WorksiteServiceItemOwner, WorksiteGuidanceHandler {
    override val kind: ActivityKind = ActivityKind.MINE
    override val zoneCount: Int get() = registry.size
    val pendingBlockCount: Int get() = recovery.pendingCount

    fun rebuild(configured: List<MineZoneSettings>, persisted: Map<String, MineShiftState>, cooldownMillis: Long) {
        registry.replace(MineRuntimeFactory.build(configured, persisted, cooldownMillis, regions))
    }

    fun reconfigure(configured: List<MineZoneSettings>, persisted: Map<String, MineShiftState>, cooldownMillis: Long) {
        registry.reconfigure(MineRuntimeFactory.build(configured, persisted, cooldownMillis, regions))
    }

    fun canStart(zoneId: String): Boolean = recovery.canStart(zoneId)

    override fun states(): Map<String, MineShiftState> =
        registry.snapshot().associate { it.settings.id to it.state }

    override fun statuses(): List<ActivityStatus> = registry.snapshot().map { runtime ->
        val (done, total) = progress(runtime)
        ActivityStatus(kind, runtime.settings.id, "phase.mine.${runtime.state.phase.name.lowercase()}", "$done/$total")
    }

    override fun tick(now: Long) {
        registry.snapshot().forEach { runtime ->
            tasks.guarded("mine_v2:${runtime.settings.id}") {
                transitions.apply(runtime, MineShiftEngine.tick(runtime.state, runtime.rules(), now), null)
                incidents.tick(runtime, now, audience.players(runtime.region).size)
                extraction.reconcile(runtime)
            }
        }
        tasks.guarded("mine_v2_recovery") { recovery.processDue(now) }
        tasks.guarded("mine_v2_reindex") { admin.tickReindexes(REINDEX_BLOCKS_PER_TICK) }
    }

    override fun canAccess(player: Player): Boolean =
        registry.snapshot().any { access.hasAccess(player, it.settings.permission) }

    override fun onBreakHigh(event: BlockBreakEvent): Boolean = mining.onBreakHigh(event)

    override fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean =
        incidents.onInteract(event) ||
            loading.onInteract(event) || prospecting.onInteract(event)

    override fun onMove(from: Location, to: Location, player: Player): Boolean =
        incidents.onMove(to, player) || loading.onMove(to, player) || extraction.onMove(from, to, player)

    override fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean = incidents.onInteractEntity(event)

    override fun onEntityDeath(event: EntityDeathEvent): Boolean = incidents.onEntityDeath(event)

    override fun updateVisuals() = Unit

    override fun updateGuidance(expectedBars: MutableSet<ActivityBarKey>) = guidance.updateHud(clock(), expectedBars)

    override fun emitGuidance() = guidance.emitParticles()

    override fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) {
        loading.releasePlayer(player, reason)
        incidents.releasePlayer(player.uniqueId)
        guidance.releasePlayer(player)
    }

    override fun isActive(identity: ServiceItemIdentity): Boolean =
        loading.isActive(identity) || incidents.isActive(identity)

    override fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        loading.release(playerId, identity, reason)
        incidents.release(playerId, identity, reason)
    }

    override fun activateLoadedState() {
        registry.snapshot().forEach { runtime ->
            runtime.region.world.loadedChunks.forEach { chunk ->
                index.reconcileChunk(runtime.indexDefinition(), chunk)
                cartScene.reconcileChunk(chunk)
                incidents.reconcileChunk(runtime, chunk)
            }
            extraction.reconcile(runtime)
        }
        recovery.activateLoadedState()
    }

    override fun reconcileChunk(chunk: Chunk) {
        registry.snapshot().filter { it.region.world === chunk.world }.forEach { runtime ->
            index.reconcileChunk(runtime.indexDefinition(), chunk)
        }
        recovery.reconcileChunk(chunk)
        cartScene.reconcileChunk(chunk)
        registry.snapshot().filter { it.region.world === chunk.world }.forEach { runtime ->
            incidents.reconcileChunk(runtime, chunk)
        }
    }

    override fun beforeReload(reason: String) {
        admin.cleanup()
        recovery.beforeReload(reason)
    }

    override fun cleanup(reason: String) {
        recovery.cleanup(reason)
        loading.cleanup()
        extraction.cleanup()
        incidents.cleanup()
        admin.cleanup()
        index.clear()
    }

    fun reindex(zoneId: String): MineReindexJob? = registry.byId(zoneId)?.let { runtime ->
        MineReindexJob(runtime.indexDefinition(), index, tickets)
    }

    fun adminStatus(zoneId: String): MineAdminStatus? = admin.status(zoneId)
    fun adminStart(zoneId: String, player: Player): Boolean = admin.start(zoneId, player)
    fun adminForceIncident(zoneId: String, type: MineIncidentType, now: Long): Boolean =
        admin.forceIncident(zoneId, type, now)
    fun adminStartReindex(zoneId: String): Boolean = admin.startReindex(zoneId)
    fun adminTickReindex(zoneId: String, budget: Int): WorksiteAdminReindexTick? = admin.tickReindex(zoneId, budget)
    fun adminCancelReindex(zoneId: String): Boolean = admin.cancelReindex(zoneId)

    private fun progress(runtime: MineRuntime): Pair<Int, Int> = when (runtime.state.phase) {
        MinePhase.PROSPECTING -> runtime.state.prospected to runtime.rules().prospectingQuota
        MinePhase.MINING -> runtime.state.mined to runtime.rules().miningQuota
        MinePhase.LOADING -> runtime.state.loaded to runtime.rules().loadingQuota
        MinePhase.EXTRACTION -> runtime.state.routeIndex to 1
        MinePhase.INCIDENT -> runtime.state.incident?.let { it.progress to it.required } ?: (0 to 1)
        MinePhase.IDLE, MinePhase.HAZARD, MinePhase.COOLDOWN -> 0 to 1
    }

    private fun MineRuntime.indexDefinition() = MineIndexDefinition(
        settings.id,
        region,
        settings.materialWeights.keys.mapTo(linkedSetOf(), MaterialRules::material),
        settings.extractionRailMaterials.mapTo(linkedSetOf(), MaterialRules::material),
    )

    private companion object {
        const val REINDEX_BLOCKS_PER_TICK = 131_072
    }
}

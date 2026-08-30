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
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
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
import ru.ruscrafting.farms.paper.mine.incident.cavein.MineCaveInIncident
import ru.ruscrafting.farms.paper.mine.incident.track.MineTrackDamageIncident
import ru.ruscrafting.farms.paper.mine.incident.gas.MineGasLeakIncident
import ru.ruscrafting.farms.paper.mine.incident.crystal.MineCrystalResonanceIncident
import ru.ruscrafting.farms.paper.mine.incident.flood.MineFloodingIncident
import ru.ruscrafting.farms.paper.mine.incident.power.MinePowerFailureIncident
import ru.ruscrafting.farms.paper.mine.incident.creature.MineCreatureNestIncident
import ru.ruscrafting.farms.paper.mine.incident.rescue.MineLostMinerIncident
import ru.ruscrafting.farms.paper.WorksiteEntityInteractHandler
import ru.ruscrafting.farms.paper.WorksiteEntityDeathHandler
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteParticipantOwner
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemOwner
import java.util.UUID

internal class MineModule(
    private val regions: RegionGateway,
    private val port: WorksiteRuntimePort,
    internal val registry: MineRuntimeRegistry,
    internal val recovery: MineBlockRecoveryController,
    internal val index: MineBlockIndex,
    internal val tickets: MineChunkTicket,
    private val prospecting: MineProspectingController,
    private val mining: MineMiningController,
    private val loading: MineLoadingController,
    private val extraction: MineExtractionController,
    private val cartScene: MineCartScene,
    private val caveIn: MineCaveInIncident,
    private val trackDamage: MineTrackDamageIncident,
    private val gasLeak: MineGasLeakIncident,
    private val crystalResonance: MineCrystalResonanceIncident,
    private val flooding: MineFloodingIncident,
    private val powerFailure: MinePowerFailureIncident,
    private val creatureNest: MineCreatureNestIncident,
    private val lostMiner: MineLostMinerIncident,
) : WorksiteModule<MineShiftState>, WorksiteBlockBreakHandler, WorksiteBlockInteractHandler,
    WorksiteMoveHandler, WorksiteEntityInteractHandler, WorksiteEntityDeathHandler, WorksiteFastVisualHandler,
    WorksiteParticipantOwner, WorksiteServiceItemOwner {
    private val transitions = MineTransitionCoordinator(port)
    override val kind: ActivityKind = ActivityKind.MINE
    override val zoneCount: Int get() = registry.size
    val pendingBlockCount: Int get() = recovery.pendingCount

    fun rebuild(configured: List<MineZoneSettings>, persisted: Map<String, MineShiftState>, cooldownMillis: Long) {
        registry.replace(MineRuntimeFactory.build(configured, persisted, cooldownMillis, regions))
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
            port.guarded("mine_v2:${runtime.settings.id}") {
                transitions.apply(runtime, MineShiftEngine.tick(runtime.state, runtime.rules(), now), null)
                caveIn.reconcile(runtime)
                trackDamage.reconcile(runtime)
                flooding.reconcile(runtime)
                powerFailure.reconcile(runtime)
                extraction.reconcile(runtime)
            }
        }
        port.guarded("mine_v2_recovery") { recovery.processDue(now) }
    }

    override fun canAccess(player: Player): Boolean =
        registry.snapshot().any { port.hasAccess(player, it.settings.permission) }

    override fun onBreakHigh(event: BlockBreakEvent): Boolean = mining.onBreakHigh(event)

    override fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean =
        caveIn.onInteract(event) || trackDamage.onInteract(event) || gasLeak.onInteract(event) ||
            crystalResonance.onInteract(event) || flooding.onInteract(event) || powerFailure.onInteract(event) ||
            loading.onInteract(event) || prospecting.onInteract(event)

    override fun onMove(from: Location, to: Location, player: Player): Boolean =
        lostMiner.onMove(to, player) || loading.onMove(to, player) || extraction.onMove(from, to, player)

    override fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean = lostMiner.onInteractEntity(event)

    override fun onEntityDeath(event: EntityDeathEvent): Boolean = creatureNest.onDeath(event)

    override fun updateVisuals() = Unit

    override fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) {
        loading.releasePlayer(player, reason)
        caveIn.releasePlayer(player.uniqueId)
        trackDamage.releasePlayer(player.uniqueId)
        flooding.releasePlayer(player.uniqueId)
        lostMiner.releasePlayer(player.uniqueId)
    }

    override fun isActive(identity: ServiceItemIdentity): Boolean =
        loading.isActive(identity) || caveIn.isActive(identity) || trackDamage.isActive(identity) || flooding.isActive(identity)

    override fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        loading.release(playerId, identity, reason)
        caveIn.release(playerId, identity, reason)
        trackDamage.release(playerId, identity, reason)
        flooding.release(playerId, identity, reason)
    }

    override fun activateLoadedState() {
        registry.snapshot().forEach { runtime ->
            runtime.region.world.loadedChunks.forEach { chunk -> index.reconcileChunk(runtime.indexDefinition(), chunk) }
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
            creatureNest.reconcileChunk(runtime, chunk)
            lostMiner.reconcileChunk(runtime, chunk)
        }
    }

    override fun cleanup(reason: String) {
        recovery.cleanup(reason)
        loading.cleanup()
        registry.snapshot().forEach { runtime ->
            runtime.state.incident?.serviceLeases?.values?.toSet().orEmpty().forEach { playerId ->
                caveIn.releasePlayer(playerId)
                trackDamage.releasePlayer(playerId)
                flooding.releasePlayer(playerId)
            }
        }
        extraction.cleanup()
        registry.snapshot().forEach { runtime ->
            creatureNest.cleanup(runtime)
            lostMiner.cleanup(runtime)
        }
        index.clear()
    }

    fun reindex(zoneId: String): MineReindexJob? = registry.byId(zoneId)?.let { runtime ->
        MineReindexJob(runtime.indexDefinition(), index, tickets)
    }

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
    )
}

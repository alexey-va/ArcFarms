package ru.ruscrafting.farms.paper.mine

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.paper.ActivityBarKey
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.MineController
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteBlockBreakHandler
import ru.ruscrafting.farms.paper.WorksiteBlockInteractHandler
import ru.ruscrafting.farms.paper.WorksiteGuidanceHandler
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.WorksiteMoveHandler
import ru.ruscrafting.farms.paper.worksite.WorksitePorts
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import ru.ruscrafting.farms.paper.WorksiteFastVisualHandler
import ru.ruscrafting.farms.paper.WorksiteEntityInteractHandler
import ru.ruscrafting.farms.paper.WorksiteEntityDeathHandler
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteParticipantOwner
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemOwner
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import ru.ruscrafting.farms.paper.worksite.WorksiteRewardGrantService
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminHandler
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminReindexTick
import ru.ruscrafting.farms.paper.worksite.WorksiteAdminStatus
import java.util.UUID
import java.util.random.RandomGenerator
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.paper.mine.lift.MineLiftAccess

/** Stable boundary that constructs exactly one mine engine generation for the process lifetime. */
internal class MineVersionedModule(
    plugin: Plugin,
    serverId: String,
    initial: List<MineZoneSettings>,
    regions: RegionGateway,
    locale: ArcFarmsLocale,
    journal: MineRecoveryJournal,
    ports: WorksitePorts,
    clock: () -> Long,
    random: RandomGenerator,
    serviceItems: WorksiteServiceItems? = null,
    rewardGrants: WorksiteRewardGrantService? = null,
    lift: MineLiftAccess? = null,
) : WorksiteModule<MineShiftState>, WorksiteBlockBreakHandler, WorksiteBlockInteractHandler,
    WorksiteMoveHandler, WorksiteGuidanceHandler, WorksiteFastVisualHandler, WorksiteServiceItemOwner,
    WorksiteParticipantOwner, WorksiteEntityInteractHandler, WorksiteEntityDeathHandler, WorksiteAdminHandler,
    ru.ruscrafting.farms.paper.WorksiteParticipantRecoveryOwner, ru.ruscrafting.farms.paper.WorksiteTeleportRetention, ru.ruscrafting.farms.paper.WorksiteTemporaryBlockOwner {
    private val engineVersion = initial.firstOrNull()?.engineVersion ?: 1
    private val delegate: WorksiteModule<MineShiftState> = if (engineVersion == 2) {
        MineComponentGraph(
            plugin, serverId, regions, ports, clock, journal, random, serviceItems = serviceItems, locale = locale,
            rewardGrants = rewardGrants,
            lift = lift,
        ).module
    } else {
        MineController(
            regions, locale, journal, ports.access, ports.audience, ports.state, ports.tasks, ports.stats, ports.network,
            clock, random,
        )
    }

    override val kind: ActivityKind get() = delegate.kind
    override val zoneCount: Int get() = delegate.zoneCount
    val pendingBlockCount: Int
        get() = when (val target = delegate) {
            is MineController -> target.pendingBlockCount
            is MineModule -> target.pendingBlockCount
            else -> 0
        }

    fun rebuild(configured: List<MineZoneSettings>, persisted: Map<String, MineShiftState>, cooldownMillis: Long) {
        require((configured.firstOrNull()?.engineVersion ?: 1) == engineVersion) {
            "Changing mine engine-version requires a full plugin restart"
        }
        when (val target = delegate) {
            is MineController -> target.rebuild(configured, persisted, cooldownMillis)
            is MineModule -> target.rebuild(configured, persisted, cooldownMillis)
            else -> error("Unsupported mine module: ${target::class.qualifiedName}")
        }
    }

    fun reconfigure(configured: List<MineZoneSettings>, persisted: Map<String, MineShiftState>, cooldownMillis: Long) {
        require((configured.firstOrNull()?.engineVersion ?: 1) == engineVersion) {
            "Changing mine engine-version requires a full plugin restart"
        }
        when (val target = delegate) {
            is MineController -> target.reconfigure(configured, persisted, cooldownMillis)
            is MineModule -> target.reconfigure(configured, persisted, cooldownMillis)
            else -> error("Unsupported mine module: ${target::class.qualifiedName}")
        }
    }

    override fun zoneIds(): List<String> = (delegate as? MineModule)?.admin?.zoneIds().orEmpty()
    override fun incidentIds(): List<String> = (delegate as? MineModule)?.admin?.incidentIds().orEmpty()
    override fun status(zoneId: String): WorksiteAdminStatus? = (delegate as? MineModule)?.adminStatus(zoneId)
    override fun start(zoneId: String, player: Player): Boolean = (delegate as? MineModule)?.adminStart(zoneId, player) == true
    override fun forceIncident(zoneId: String, incidentId: String, now: Long): Boolean =
        (delegate as? MineModule)?.admin?.forceIncident(zoneId, incidentId, now) == true
    override fun startReindex(zoneId: String): Boolean = (delegate as? MineModule)?.adminStartReindex(zoneId) == true
    override fun tickReindex(zoneId: String, budget: Int): WorksiteAdminReindexTick? =
        (delegate as? MineModule)?.adminTickReindex(zoneId, budget)
    override fun cancelReindex(zoneId: String): Boolean = (delegate as? MineModule)?.adminCancelReindex(zoneId) == true

    fun adminStatus(zoneId: String): WorksiteAdminStatus? = status(zoneId)
    fun adminStart(zoneId: String, player: Player): Boolean = start(zoneId, player)
    fun adminForceIncident(zoneId: String, type: MineIncidentType, now: Long): Boolean =
        (delegate as? MineModule)?.adminForceIncident(zoneId, type, now) == true

    override fun states(): Map<String, MineShiftState> = delegate.states()
    override fun statuses(): List<ActivityStatus> = delegate.statuses()
    override fun tick(now: Long) = delegate.tick(now)
    override fun isAvailable(): Boolean = delegate.isAvailable()
    override fun canAccess(player: Player): Boolean = delegate.canAccess(player)
    override fun activateLoadedState() = delegate.activateLoadedState()
    override fun reconcileChunk(chunk: Chunk) = delegate.reconcileChunk(chunk)
    override fun beforeReload(reason: String) = delegate.beforeReload(reason)
    override fun cleanup(reason: String) = delegate.cleanup(reason)

    override fun protectsTemporaryBlock(location: Location): Boolean = (delegate as? ru.ruscrafting.farms.paper.WorksiteTemporaryBlockOwner)?.protectsTemporaryBlock(location) == true

    override fun recoverPlayer(player: Player) { (delegate as? ru.ruscrafting.farms.paper.WorksiteParticipantRecoveryOwner)?.recoverPlayer(player) }
    override fun retainOnTeleport(player: Player, destination: org.bukkit.Location): Boolean = (delegate as? ru.ruscrafting.farms.paper.WorksiteTeleportRetention)?.retainOnTeleport(player, destination) == true

    override fun onBreakHigh(event: BlockBreakEvent): Boolean =
        (delegate as? WorksiteBlockBreakHandler)?.onBreakHigh(event) == true

    override fun onBreakMonitor(event: BlockBreakEvent): Boolean =
        (delegate as? WorksiteBlockBreakHandler)?.onBreakMonitor(event) == true

    override fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean =
        (delegate as? WorksiteBlockInteractHandler)?.onInteract(event, clicked, player) == true

    override fun onMove(from: Location, to: Location, player: Player): Boolean =
        (delegate as? WorksiteMoveHandler)?.onMove(from, to, player) == true

    override fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean =
        (delegate as? WorksiteEntityInteractHandler)?.onInteractEntity(event) == true

    override fun onEntityDeath(event: EntityDeathEvent): Boolean =
        (delegate as? WorksiteEntityDeathHandler)?.onEntityDeath(event) == true

    override fun updateGuidance(expectedBars: MutableSet<ActivityBarKey>) {
        (delegate as? WorksiteGuidanceHandler)?.updateGuidance(expectedBars)
    }

    override fun emitGuidance() {
        (delegate as? WorksiteGuidanceHandler)?.emitGuidance()
    }

    override fun updateVisuals() {
        (delegate as? WorksiteFastVisualHandler)?.updateVisuals()
    }

    override fun isActive(identity: ServiceItemIdentity): Boolean =
        (delegate as? WorksiteServiceItemOwner)?.isActive(identity) == true

    override fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        (delegate as? WorksiteServiceItemOwner)?.release(playerId, identity, reason)
    }

    override fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) {
        (delegate as? WorksiteParticipantOwner)?.releasePlayer(player, reason)
    }
}

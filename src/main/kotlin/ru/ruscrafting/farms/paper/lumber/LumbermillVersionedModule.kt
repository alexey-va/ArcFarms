package ru.ruscrafting.farms.paper.lumber

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.paper.ActivityBarKey
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.LumbermillController
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteBlockBreakHandler
import ru.ruscrafting.farms.paper.WorksiteBlockInteractHandler
import ru.ruscrafting.farms.paper.WorksiteGuidanceHandler
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.WorksiteMoveHandler
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksiteParticipantOwner
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItemOwner
import java.util.UUID

/** Stable application boundary that constructs exactly one lumber engine generation. */
internal class LumbermillVersionedModule(
    initial: List<LumberZoneSettings>,
    regions: RegionGateway,
    locale: ArcFarmsLocale,
    port: WorksiteRuntimePort,
    clock: () -> Long,
) : WorksiteModule<LumberShiftState>, WorksiteBlockBreakHandler, WorksiteBlockInteractHandler,
    WorksiteMoveHandler, WorksiteGuidanceHandler, WorksiteServiceItemOwner, WorksiteParticipantOwner {
    private val engineVersion = initial.firstOrNull()?.engineVersion ?: 1
    private val delegate: WorksiteModule<LumberShiftState> = if (engineVersion == 2) {
        LumbermillComponentGraph(regions, port, clock).module
    } else {
        LumbermillController(regions, locale, port, clock)
    }

    override val kind: ActivityKind get() = delegate.kind
    override val zoneCount: Int get() = delegate.zoneCount

    fun rebuild(configured: List<LumberZoneSettings>, persisted: Map<String, LumberShiftState>, cooldownMillis: Long) {
        require((configured.firstOrNull()?.engineVersion ?: 1) == engineVersion) {
            "Changing lumber engine-version requires a full plugin restart"
        }
        when (val target = delegate) {
            is LumbermillController -> target.rebuild(configured, persisted, cooldownMillis)
            is LumbermillModule -> target.rebuild(configured, persisted, cooldownMillis)
            else -> error("Unsupported lumber module: ${target::class.qualifiedName}")
        }
    }

    override fun states(): Map<String, LumberShiftState> = delegate.states()
    override fun statuses(): List<ActivityStatus> = delegate.statuses()
    override fun tick(now: Long) = delegate.tick(now)
    override fun isAvailable(): Boolean = delegate.isAvailable()
    override fun canAccess(player: Player): Boolean = delegate.canAccess(player)
    override fun activateLoadedState() = delegate.activateLoadedState()
    override fun reconcileChunk(chunk: Chunk) = delegate.reconcileChunk(chunk)
    override fun beforeReload(reason: String) = delegate.beforeReload(reason)
    override fun cleanup(reason: String) = delegate.cleanup(reason)

    override fun onBreakHigh(event: BlockBreakEvent): Boolean =
        (delegate as? WorksiteBlockBreakHandler)?.onBreakHigh(event) == true

    override fun onBreakMonitor(event: BlockBreakEvent): Boolean =
        (delegate as? WorksiteBlockBreakHandler)?.onBreakMonitor(event) == true

    override fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean =
        (delegate as? WorksiteBlockInteractHandler)?.onInteract(event, clicked, player) == true

    override fun onMove(from: Location, to: Location, player: Player): Boolean =
        (delegate as? WorksiteMoveHandler)?.onMove(from, to, player) == true

    override fun updateGuidance(expectedBars: MutableSet<ActivityBarKey>) {
        (delegate as? WorksiteGuidanceHandler)?.updateGuidance(expectedBars)
    }

    override fun emitGuidance() {
        (delegate as? WorksiteGuidanceHandler)?.emitGuidance()
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

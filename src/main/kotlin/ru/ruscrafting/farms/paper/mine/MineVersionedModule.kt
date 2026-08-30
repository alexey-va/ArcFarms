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
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.persistence.MineRecoveryJournal
import java.util.random.RandomGenerator

/** Stable boundary that constructs exactly one mine engine generation for the process lifetime. */
internal class MineVersionedModule(
    plugin: Plugin,
    initial: List<MineZoneSettings>,
    regions: RegionGateway,
    locale: ArcFarmsLocale,
    journal: MineRecoveryJournal,
    port: WorksiteRuntimePort,
    clock: () -> Long,
    random: RandomGenerator,
) : WorksiteModule<MineShiftState>, WorksiteBlockBreakHandler, WorksiteBlockInteractHandler,
    WorksiteMoveHandler, WorksiteGuidanceHandler {
    private val engineVersion = initial.firstOrNull()?.engineVersion ?: 1
    private val delegate: WorksiteModule<MineShiftState> = if (engineVersion == 2) {
        MineComponentGraph(plugin, regions, port, clock, journal, random).module
    } else {
        MineController(regions, locale, journal, port, clock, random)
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

    override fun states(): Map<String, MineShiftState> = delegate.states()
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
}

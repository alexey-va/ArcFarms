package ru.ruscrafting.farms.paper.mine

import org.bukkit.Chunk
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.MineZoneSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.mine.recovery.MineBlockRecoveryController

internal class MineModule(
    private val regions: RegionGateway,
    private val port: WorksiteRuntimePort,
    internal val registry: MineRuntimeRegistry,
    internal val recovery: MineBlockRecoveryController,
) : WorksiteModule<MineShiftState> {
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
            }
        }
        port.guarded("mine_v2_recovery") { recovery.processDue(now) }
    }

    override fun canAccess(player: Player): Boolean =
        registry.snapshot().any { port.hasAccess(player, it.settings.permission) }

    override fun activateLoadedState() = recovery.activateLoadedState()
    override fun reconcileChunk(chunk: Chunk) = recovery.reconcileChunk(chunk)
    override fun cleanup(reason: String) = recovery.cleanup(reason)

    private fun progress(runtime: MineRuntime): Pair<Int, Int> = when (runtime.state.phase) {
        MinePhase.PROSPECTING -> runtime.state.prospected to runtime.rules().prospectingQuota
        MinePhase.MINING -> runtime.state.mined to runtime.rules().miningQuota
        MinePhase.LOADING -> runtime.state.loaded to runtime.rules().loadingQuota
        MinePhase.EXTRACTION -> runtime.state.routeIndex to 1
        MinePhase.INCIDENT -> runtime.state.incident?.let { it.progress to it.required } ?: (0 to 1)
        MinePhase.IDLE, MinePhase.HAZARD, MinePhase.COOLDOWN -> 0 to 1
    }
}

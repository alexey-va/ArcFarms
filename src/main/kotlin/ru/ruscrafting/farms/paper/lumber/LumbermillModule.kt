package ru.ruscrafting.farms.paper.lumber

import org.bukkit.Chunk
import org.bukkit.entity.Player
import ru.ruscrafting.farms.config.LumberZoneSettings
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.paper.ActivityStatus
import ru.ruscrafting.farms.paper.RegionGateway
import ru.ruscrafting.farms.paper.WorksiteModule
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.index.LumberBlockIndex
import ru.ruscrafting.farms.paper.lumber.index.LumberChunkTicket
import ru.ruscrafting.farms.paper.lumber.index.LumberIndexDefinition
import ru.ruscrafting.farms.paper.lumber.index.LumberReindexJob
import ru.ruscrafting.farms.paper.lumber.recovery.LumberBlockRecoveryController

internal class LumbermillModule(
    private val regions: RegionGateway,
    private val port: WorksiteRuntimePort,
    internal val registry: LumberRuntimeRegistry,
    internal val index: LumberBlockIndex,
    internal val recovery: LumberBlockRecoveryController,
    internal val tickets: LumberChunkTicket,
) : WorksiteModule<LumberShiftState> {
    override val kind: ActivityKind = ActivityKind.LUMBER
    override val zoneCount: Int get() = registry.size

    fun rebuild(
        configured: List<LumberZoneSettings>,
        persisted: Map<String, LumberShiftState>,
        cooldownMillis: Long,
    ) {
        registry.replace(LumberRuntimeFactory.build(configured, persisted, cooldownMillis, regions))
    }

    override fun states(): Map<String, LumberShiftState> =
        registry.snapshot().associate { it.settings.id to it.state }

    override fun statuses(): List<ActivityStatus> = registry.snapshot().map { runtime ->
        val (done, total) = progress(runtime)
        ActivityStatus(kind, runtime.settings.id, "phase.lumber.${runtime.state.phase.name.lowercase()}", "$done/$total")
    }

    override fun tick(now: Long) = registry.snapshot().forEach { runtime ->
        port.guarded("lumber_v2:${runtime.settings.id}") {
            if (runtime.state.phase == LumberPhase.IDLE) return@guarded
            val result = LumberShiftEngine.tick(runtime.state, runtime.rules(), now)
            if (result.accepted) {
                runtime.state = result.state
                port.persistAsync()
            }
        }
    }.also { recovery.processDue(now) }

    override fun canAccess(player: Player): Boolean =
        registry.snapshot().any { port.hasAccess(player, it.settings.permission) }

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
    }

    override fun beforeReload(reason: String) = recovery.beforeReload(reason)

    override fun cleanup(reason: String) {
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
}

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
import ru.ruscrafting.farms.paper.worksite.RuntimeComponent

internal class LumbermillModule(
    private val regions: RegionGateway,
    private val port: WorksiteRuntimePort,
    internal val registry: LumberRuntimeRegistry,
    private val components: List<RuntimeComponent>,
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
    }

    override fun canAccess(player: Player): Boolean =
        registry.snapshot().any { port.hasAccess(player, it.settings.permission) }

    override fun activateLoadedState() = components.forEach(RuntimeComponent::activateLoadedState)

    override fun reconcileChunk(chunk: Chunk) = components.forEach { it.reconcileChunk(chunk) }

    override fun beforeReload(reason: String) = components.forEach { it.beforeReload(reason) }

    override fun cleanup(reason: String) = components.forEach { it.cleanup(reason) }

    private fun progress(runtime: LumberRuntime): Pair<Int, Int> = when (runtime.state.phase) {
        LumberPhase.FELLING -> runtime.state.felled to runtime.rules().fellingQuota
        LumberPhase.SKIDDING -> runtime.state.skidded to runtime.rules().skiddingQuota
        LumberPhase.SAWING -> runtime.state.sawCuts to runtime.rules().sawingQuota
        LumberPhase.STACKING, LumberPhase.DISPATCH -> runtime.state.stacked to runtime.rules().stackingQuota
        LumberPhase.INCIDENT -> runtime.state.incident?.let { it.progress to it.required } ?: (0 to 1)
        LumberPhase.PROCESSING -> runtime.state.processed to runtime.rules().processingQuota
        LumberPhase.IDLE, LumberPhase.COOLDOWN -> 0 to 1
    }
}

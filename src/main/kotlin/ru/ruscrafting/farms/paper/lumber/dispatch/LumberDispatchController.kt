package ru.ruscrafting.farms.paper.lumber.dispatch

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.LumberShiftEvent
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteNetworkPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.LumberTransitionCoordinator
import ru.ruscrafting.farms.paper.worksite.WorksiteRewardGrantService
import ru.ruscrafting.farms.api.WorkShiftCompletedEvent

internal class LumberDispatchController(
    private val registry: LumberRuntimeRegistry,
    private val serverId: String,
    private val transitions: LumberTransitionCoordinator,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val stats: WorksiteStatsPort,
    private val network: WorksiteNetworkPort,
    private val clock: () -> Long,
    private val rewards: WorksiteRewardGrantService? = null,
) {
    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = registry.snapshot().firstOrNull { it.station.contains(clicked.location) } ?: return false
        if (runtime.state.phase != LumberPhase.DISPATCH || clicked.type.name != dispatchMaterial(runtime)) return false
        event.isCancelled = true
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!access.allowInteraction(
                "lumber-dispatch:${runtime.settings.id}:${player.uniqueId}", runtime.settings.dispatchInteractionCooldownMillis,
            )
        ) return true
        ringBell(runtime, player, clock())
        return true
    }

    fun ringBell(
        runtime: LumberRuntime,
        player: Player,
        now: Long,
    ): EngineResult<LumberShiftState, LumberShiftEvent> {
        val result = LumberShiftEngine.dispatch(runtime.state, runtime.rules(), player.uniqueId, now)
        if (!result.accepted) return result
        transitions.apply(runtime, result, player)
        val contributors = result.state.contributors
        if (contributors.isNotEmpty()) {
            org.bukkit.Bukkit.getPluginManager().callEvent(
                WorkShiftCompletedEvent(
                    eventId = "$serverId:lumber:${runtime.settings.id}:${result.state.sequence}",
                    kind = "lumber",
                    contributors = contributors.keys,
                    zoneId = runtime.settings.id,
                ),
            )
        }
        stats.recordCompletion(ActivityKind.LUMBER, result.state.contributors)
        rewards?.queueCompletion(
            ActivityKind.LUMBER, runtime.settings.rewards, runtime.settings.id, result.state.sequence,
            result.state.contributors,
            runtime.rules().let { it.fellingQuota + it.skiddingQuota + it.sawingQuota + it.stackingQuota },
            result.state.incidentSchedule.size,
        )
        network.complete(ActivityKind.LUMBER, player.name, emptySet())
        audience.announceWinner(listOf(runtime.region, runtime.station), result.state.contributors)
        audience.celebration(listOf(runtime.region, runtime.station))
        return result
    }

    private fun dispatchMaterial(runtime: LumberRuntime): String = runtime.settings.stationMaterials.drop(3).firstOrNull()
        ?: runtime.settings.stationMaterials.last()
}

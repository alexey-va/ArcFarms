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
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.LumberTransitionCoordinator

internal class LumberDispatchController(
    private val registry: LumberRuntimeRegistry,
    private val transitions: LumberTransitionCoordinator,
    private val port: WorksiteRuntimePort,
    private val clock: () -> Long,
) {
    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = registry.snapshot().firstOrNull { it.station.contains(clicked.location) } ?: return false
        if (runtime.state.phase != LumberPhase.DISPATCH || clicked.type.name != dispatchMaterial(runtime)) return false
        event.isCancelled = true
        if (!port.hasAccess(player, runtime.settings.permission)) {
            port.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!port.allowInteraction("lumber-dispatch:${runtime.settings.id}:${player.uniqueId}", 500L)) return true
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
        port.recordCompletion(ActivityKind.LUMBER, result.state.contributors)
        port.complete(ActivityKind.LUMBER, player.name, emptySet())
        port.announceWinner(listOf(runtime.region, runtime.station), result.state.contributors)
        port.celebration(listOf(runtime.region, runtime.station))
        return result
    }

    private fun dispatchMaterial(runtime: LumberRuntime): String = runtime.settings.stationMaterials.drop(3).firstOrNull()
        ?: runtime.settings.stationMaterials.last()
}

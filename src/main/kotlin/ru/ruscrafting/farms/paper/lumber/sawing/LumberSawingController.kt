package ru.ruscrafting.farms.paper.lumber.sawing

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.LumberShiftEvent
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.lumber.LumberSawSequence
import ru.ruscrafting.farms.domain.lumber.LumberSawSide
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.LumberTransitionCoordinator
import java.util.logging.Level

internal class LumberSawingController(
    private val registry: LumberRuntimeRegistry,
    private val transitions: LumberTransitionCoordinator,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val state: WorksiteStatePort,
    private val clock: () -> Long,
    private val startStacking: (LumberRuntime, LumberShiftState) -> LumberShiftState,
    private val reconcileStacking: (LumberRuntime) -> Unit,
) {
    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = registry.snapshot().firstOrNull { it.station.contains(clicked.location) } ?: return false
        if (runtime.state.phase != LumberPhase.SAWING) return false
        val side = side(runtime, clicked.type.name) ?: return false
        event.isCancelled = true
        if (!access.hasAccess(player, runtime.settings.permission)) {
            audience.sendChat(player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (!access.allowInteraction("lumber-saw:${runtime.settings.id}:${player.uniqueId}", 150L)) return true
        use(runtime, side, player, clock())
        return true
    }

    fun use(
        runtime: LumberRuntime,
        side: LumberSawSide,
        player: Player,
        now: Long,
    ): EngineResult<LumberShiftState, LumberShiftEvent> {
        if (runtime.state.phase != LumberPhase.SAWING) return EngineResult(runtime.state, false)
        val sequence = LumberSawSequence.use(runtime.state.sawSequence, side, now)
        if (!sequence.accepted) {
            remind(runtime, player)
            return EngineResult(runtime.state, false)
        }
        val cut = LumberShiftEngine.saw(
            runtime.state.copy(sawSequence = sequence.state),
            runtime.rules(),
            player.uniqueId,
        )
        val state = if (cut.state.phase == LumberPhase.STACKING) startStacking(runtime, cut.state) else cut.state
        val applied = cut.copy(state = state)
        transitions.apply(runtime, applied, player)
        if (state.phase == LumberPhase.STACKING) {
            runCatching { reconcileStacking(runtime) }.onFailure { failure ->
                this.state.log(Level.WARNING, "Could not reconcile lumber pallets for ${runtime.settings.id}", failure)
            }
        }
        return applied
    }

    private fun side(runtime: LumberRuntime, material: String): LumberSawSide? {
        val controls = runtime.settings.stationMaterials.take(2)
        return when (controls.indexOf(material)) {
            0 -> LumberSawSide.LEFT
            1 -> LumberSawSide.RIGHT
            else -> null
        }
    }

    private fun remind(runtime: LumberRuntime, player: Player) {
        val key = if (runtime.state.sawSequence.expected == LumberSawSide.LEFT) {
            MessageKey.LUMBER_SAW_LEFT
        } else {
            MessageKey.LUMBER_SAW_RIGHT
        }
        audience.sendActionBar(player, key)
        audience.showScreenTitle(player, key, scope = "lumber:saw:${runtime.settings.id}")
    }
}

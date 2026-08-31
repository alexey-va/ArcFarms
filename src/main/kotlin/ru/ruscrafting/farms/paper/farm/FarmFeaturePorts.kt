package ru.ruscrafting.farms.paper.farm

import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.EngineResult
import ru.ruscrafting.farms.domain.FarmPointKind
import ru.ruscrafting.farms.domain.FarmPointPosition
import ru.ruscrafting.farms.domain.FarmPlotPosition
import ru.ruscrafting.farms.domain.FarmOrder
import ru.ruscrafting.farms.domain.FarmShiftState
import ru.ruscrafting.farms.domain.FarmShiftEvent
import ru.ruscrafting.farms.paper.FarmRuntime

/** Named seam for configured/default farm points while point administration is extracted. */
internal fun interface FarmPointProvider {
    fun resolve(runtime: FarmRuntime, kind: FarmPointKind): FarmPointPosition

    fun configured(runtime: FarmRuntime, kind: FarmPointKind): FarmPointPosition? = null
}

/** Sole application boundary through which a feature may apply a farm state transition. */
internal fun interface FarmTransitionSink {
    fun apply(runtime: FarmRuntime, result: EngineResult<FarmShiftState, FarmShiftEvent>, actor: Player?)
}

/** Breaks composition-time cycles while keeping every feature on one typed transition path. */
internal class FarmTransitionRouter : FarmTransitionSink {
    private var delegate: FarmTransitionSink? = null

    fun bind(delegate: FarmTransitionSink) {
        check(this.delegate == null) { "Farm transition router is already bound" }
        this.delegate = delegate
    }

    override fun apply(runtime: FarmRuntime, result: EngineResult<FarmShiftState, FarmShiftEvent>, actor: Player?) {
        requireNotNull(delegate) { "Farm transition router is not bound" }.apply(runtime, result, actor)
    }
}

/** Starts a shift through the owning coordinator without exposing its mutable internals. */
internal fun interface FarmShiftStarter {
    fun start(runtime: FarmRuntime, player: Player, now: Long): Boolean
}

/** Starts either the planned order or an explicitly selected admin contract. */
internal fun interface FarmShiftLauncher {
    fun start(runtime: FarmRuntime, player: Player, now: Long, order: FarmOrder?): Boolean
}

/** Composition-time cycle breaker shared by harvest, admin and the runtime tick. */
internal class FarmShiftLaunchRouter : FarmShiftStarter, FarmShiftLauncher {
    private var delegate: FarmShiftLauncher? = null

    fun bind(delegate: FarmShiftLauncher) {
        check(this.delegate == null) { "Farm shift launch router is already bound" }
        this.delegate = delegate
    }

    override fun start(runtime: FarmRuntime, player: Player, now: Long): Boolean =
        start(runtime, player, now, null)

    override fun start(runtime: FarmRuntime, player: Player, now: Long, order: FarmOrder?): Boolean =
        requireNotNull(delegate) { "Farm shift launch router is not bound" }.start(runtime, player, now, order)
}

/** Player-facing explanation used when an action does not match the active farm phase. */
internal fun interface FarmTaskHintSink {
    fun show(player: Player, runtime: FarmRuntime, reason: String)
}

/** Composition-time cycle breaker between harvest validation and the HUD. */
internal class FarmTaskHintRouter : FarmTaskHintSink {
    private var delegate: FarmTaskHintSink? = null

    fun bind(delegate: FarmTaskHintSink) {
        check(this.delegate == null) { "Farm task hint router is already bound" }
        this.delegate = delegate
    }

    override fun show(player: Player, runtime: FarmRuntime, reason: String) {
        requireNotNull(delegate) { "Farm task hint router is not bound" }.show(player, runtime, reason)
    }
}

/** Temporary typed seam until field discovery moves into FarmFieldController. */
internal fun interface FarmIncidentBedProvider {
    fun discover(runtime: FarmRuntime): Set<FarmPlotPosition>
}

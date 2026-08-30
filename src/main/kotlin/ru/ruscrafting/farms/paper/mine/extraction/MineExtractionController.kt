package ru.ruscrafting.farms.paper.mine.extraction

import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.MineTransitionCoordinator
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex

internal class MineExtractionController(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val scene: MineCartScene,
    private val transitions: MineTransitionCoordinator,
    private val port: WorksiteRuntimePort,
    private val clock: () -> Long,
) {
    private val routes = mutableMapOf<String, MineExtractionRoute>()

    fun canRoute(runtime: MineRuntime): Boolean = route(runtime) != null
    fun deliveryPoint(runtime: MineRuntime): WorksitePosition? = route(runtime)?.sample(0)

    fun begin(runtime: MineRuntime, state: MineShiftState): MineShiftState {
        require(state.phase == MinePhase.EXTRACTION)
        requireNotNull(route(runtime)) { "Mine zone ${runtime.settings.id} lost its extraction route" }
        return state.copy(routeIndex = 0)
    }

    fun onMove(from: Location, to: Location, player: Player): Boolean {
        val runtime = registry.at(to) ?: registry.at(from) ?: return false
        return push(runtime, player, to)
    }

    fun push(runtime: MineRuntime, player: Player, to: Location): Boolean {
        if (runtime.state.phase != MinePhase.EXTRACTION || !port.hasAccess(player, runtime.settings.permission)) return false
        val route = route(runtime) ?: return false
        if (runtime.state.routeIndex >= route.finalIndex) return false
        val next = route.sample(runtime.state.routeIndex + 1)
        if (!near(to, next, 1.6)) return false
        val advanced = MineShiftEngine.advanceRoute(runtime.state, route.finalIndex, player.uniqueId)
        transitions.apply(runtime, advanced, player)
        if (runtime.state.routeIndex >= route.finalIndex) {
            transitions.apply(runtime, MineShiftEngine.extract(runtime.state, runtime.rules(), player.uniqueId, clock()), player)
            scene.cleanup(runtime.settings.id)
        } else {
            scene.reconcile(runtime, route)
        }
        return true
    }

    fun reconcile(runtime: MineRuntime) = scene.reconcile(runtime, route(runtime))
    fun routeFor(runtime: MineRuntime): MineExtractionRoute? = route(runtime)

    fun cleanup() {
        registry.snapshot().forEach { scene.cleanup(it.settings.id) }
        routes.clear()
    }

    private fun route(runtime: MineRuntime): MineExtractionRoute? = routes.getOrPut(runtime.settings.id) {
        MineExtractionRoute.fromAnchors(index.loadedTargets(runtime.settings.id, MineAnchorRole.RAIL))
            ?: return null
    }

    private fun near(location: Location, position: WorksitePosition, radius: Double): Boolean {
        if (location.world.name != position.world) return false
        val dx = location.x - (position.x + 0.5)
        val dy = location.y - (position.y + 1.0)
        val dz = location.z - (position.z + 0.5)
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }
}

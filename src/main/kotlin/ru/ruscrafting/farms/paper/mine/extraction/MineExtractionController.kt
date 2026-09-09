package ru.ruscrafting.farms.paper.mine.extraction

import org.bukkit.Location
import org.bukkit.entity.Player
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.worksite.WorksiteNetworkPort
import ru.ruscrafting.farms.paper.worksite.WorksiteStatsPort
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.MineTransitionCoordinator
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.paper.worksite.WorksiteRewardGrantService
import ru.ruscrafting.farms.api.WorkShiftCompletedEvent

internal class MineExtractionController(
    private val registry: MineRuntimeRegistry,
    private val serverId: String,
    private val index: MineBlockIndex,
    private val scene: MineCartScene,
    private val transitions: MineTransitionCoordinator,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
    private val stats: WorksiteStatsPort,
    private val network: WorksiteNetworkPort,
    private val clock: () -> Long,
    private val rewards: WorksiteRewardGrantService? = null,
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
        if (runtime.settings.miningOnly || runtime.state.phase != MinePhase.EXTRACTION || !access.hasAccess(player, runtime.settings.permission)) return false
        val route = route(runtime) ?: return false
        if (runtime.state.routeIndex >= route.finalIndex) return false
        val next = route.sample(runtime.state.routeIndex + 1)
        if (!near(to, next, runtime.settings.extractionCheckpointRadius)) return false
        val advanced = MineShiftEngine.advanceRoute(runtime.state, route.finalIndex, player.uniqueId)
        transitions.apply(runtime, advanced, player)
        if (runtime.state.routeIndex >= route.finalIndex) {
            complete(runtime, player, route.finalIndex)
            scene.cleanup(runtime.settings.id)
        } else {
            scene.reconcile(runtime, route)
        }
        return true
    }

    fun completeMiningOrder(runtime: MineRuntime, player: Player) {
        if (!runtime.settings.miningOnly || runtime.state.phase != MinePhase.EXTRACTION ||
            runtime.state.incidentCursor < runtime.state.incidentSchedule.size) return
        complete(runtime, player, 0)
    }

    private fun complete(runtime: MineRuntime, player: Player, routeLength: Int) {
        val result = MineShiftEngine.extract(runtime.state, runtime.rules(), player.uniqueId, clock())
        transitions.apply(runtime, result, player)
        if (result.accepted) {
            val contributors = result.state.contributors
            if (contributors.isNotEmpty()) {
                org.bukkit.Bukkit.getPluginManager().callEvent(
                    WorkShiftCompletedEvent(
                        eventId = "$serverId:mine:${runtime.settings.id}:${result.state.sequence}",
                        kind = "mine",
                        contributors = contributors.keys,
                        zoneId = runtime.settings.id,
                    ),
                )
            }
            stats.recordCompletion(ActivityKind.MINE, result.state.contributors)
            rewards?.queueCompletion(
                ActivityKind.MINE, runtime.settings.rewards, runtime.settings.id, result.state.sequence,
                result.state.contributors,
                runtime.rules().let { if (it.miningOnly) it.miningQuota else it.prospectingQuota + it.miningQuota + it.loadingQuota + routeLength },
                result.state.incidentSchedule.size,
            )
            network.complete(ActivityKind.MINE, player.name, emptySet())
            audience.announceWinner(listOf(runtime.region), result.state.contributors)
            audience.celebration(listOf(runtime.region))
        }
    }

    fun reconcile(runtime: MineRuntime) {
        if (!runtime.settings.miningOnly) scene.reconcile(runtime, route(runtime))
    }
    fun routeFor(runtime: MineRuntime): MineExtractionRoute? = route(runtime)
    fun guidanceTarget(runtime: MineRuntime): WorksitePosition? = route(runtime)?.let { route ->
        route.sample((runtime.state.routeIndex + 1).coerceAtMost(route.finalIndex))
    }

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

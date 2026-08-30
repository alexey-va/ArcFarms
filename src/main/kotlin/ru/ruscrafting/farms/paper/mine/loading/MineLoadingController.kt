package ru.ruscrafting.farms.paper.mine.loading

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.ActivityKind
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.MineShiftEngine
import ru.ruscrafting.farms.domain.MineShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.MineTransitionCoordinator
import ru.ruscrafting.farms.paper.mine.extraction.MineExtractionController
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.worksite.ServiceItemIdentity
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteServiceItems
import java.util.UUID
import kotlin.math.absoluteValue

internal class MineLoadingController(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val extraction: MineExtractionController,
    private val transitions: MineTransitionCoordinator,
    private val serviceItems: WorksiteServiceItems?,
    private val locale: ArcFarmsLocale?,
    private val port: WorksiteRuntimePort,
    private val clock: () -> Long,
) {
    private data class Carry(val zoneId: String, val sequence: Long, val targetId: String, val identity: ServiceItemIdentity)
    private val carried = mutableMapOf<UUID, Carry>()

    fun canStage(runtime: MineRuntime): Boolean = candidates(runtime).size >= runtime.rules().loadingQuota * 2 &&
        extraction.canRoute(runtime)

    fun begin(runtime: MineRuntime, phaseState: MineShiftState): MineShiftState {
        require(phaseState.phase == MinePhase.LOADING)
        val candidates = candidates(runtime)
        require(candidates.size >= runtime.rules().loadingQuota * 2) {
            "Mine zone ${runtime.settings.id} lost safe loading targets"
        }
        return phaseState.copy(
            objective = ObjectiveTargetPool.plan(
                WorksiteObjectiveKey(runtime.settings.id, "loading", phaseState.sequence),
                runtime.rules().loadingQuota,
                candidates,
            ),
        )
    }

    fun onInteract(event: PlayerInteractEvent): Boolean {
        val clicked = event.clickedBlock ?: return false
        val runtime = registry.at(clicked.location) ?: return false
        if (runtime.state.phase != MinePhase.LOADING || event.action != Action.RIGHT_CLICK_BLOCK) return false
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == clicked.position() } ?: return false
        event.isCancelled = true
        return pickup(runtime, target.id, event.player).also { accepted ->
            if (!accepted) port.sendActionBar(event.player, MessageKey.MINE_LOADING_REQUIRED)
        }
    }

    fun pickup(runtime: MineRuntime, targetId: String, player: Player): Boolean {
        if (runtime.state.phase != MinePhase.LOADING || player.uniqueId in carried) return false
        if (!port.hasAccess(player, runtime.settings.permission)) return false
        val objective = runtime.state.objective ?: return false
        val leased = ObjectiveTargetPool.lease(objective, targetId, player.uniqueId, clock())
        if (!leased.accepted) return false
        val identity = ServiceItemIdentity(
            ActivityKind.MINE,
            runtime.settings.id,
            runtime.state.sequence,
            runtime.state.sequence,
            ObjectiveTargetRole("ore_crate"),
            targetId.take(47),
        )
        runtime.state = runtime.state.copy(objective = leased.state)
        carried[player.uniqueId] = Carry(runtime.settings.id, runtime.state.sequence, targetId, identity)
        val name = locale?.render(MessageKey.MINE_ORE_CRATE, player) ?: net.kyori.adventure.text.Component.text("Ore crate")
        val issued = serviceItems?.issue(player, identity, Material.RAW_IRON, name)
        if (issued == null) {
            carried.remove(player.uniqueId)
            runtime.state = runtime.state.copy(objective = objective)
            return false
        }
        port.persistAsync()
        return issued.amount > 0
    }

    fun onMove(to: Location, player: Player): Boolean {
        val carry = carried[player.uniqueId] ?: return false
        val runtime = registry.byId(carry.zoneId) ?: return releasePlayer(player.uniqueId)
        val point = extraction.deliveryPoint(runtime) ?: return false
        if (!near(to, point, 2.0)) return false
        return deliver(runtime, player)
    }

    fun deliver(runtime: MineRuntime, player: Player): Boolean {
        val carry = carried[player.uniqueId] ?: return false
        if (carry.zoneId != runtime.settings.id || carry.sequence != runtime.state.sequence) return false
        if (serviceItems?.consume(player, carry.identity) != true) return false
        val objective = runtime.state.objective ?: return false
        val completed = ObjectiveTargetPool.complete(objective, carry.targetId, player.uniqueId)
        if (!completed.accepted) return false
        carried.remove(player.uniqueId)
        val advanced = MineShiftEngine.load(runtime.state.copy(objective = completed.state), runtime.rules(), player.uniqueId)
        val final = if (advanced.state.phase == MinePhase.EXTRACTION) extraction.begin(runtime, advanced.state) else {
            advanced.state.copy(objective = completed.state)
        }
        transitions.apply(runtime, advanced.copy(state = final), player)
        if (final.phase == MinePhase.EXTRACTION) extraction.reconcile(runtime)
        return true
    }

    fun isActive(identity: ServiceItemIdentity): Boolean {
        val runtime = registry.byId(identity.zoneId) ?: return false
        return identity.activity == ActivityKind.MINE && identity.role.value == "ore_crate" &&
            runtime.state.phase == MinePhase.LOADING && runtime.state.sequence == identity.sequence &&
            carried.values.any { it.identity == identity }
    }

    fun release(playerId: UUID, identity: ServiceItemIdentity, reason: WorksitePlayerReleaseReason) {
        if (carried[playerId]?.identity == identity) releasePlayer(playerId)
    }

    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason): Boolean = releasePlayer(player.uniqueId)

    private fun releasePlayer(playerId: UUID): Boolean {
        val carry = carried.remove(playerId) ?: return false
        Bukkit.getPlayer(playerId)?.let { player -> serviceItems?.consume(player, carry.identity) }
        val runtime = registry.byId(carry.zoneId) ?: return true
        if (runtime.state.sequence != carry.sequence) return true
        val objective = runtime.state.objective ?: return true
        val released = ObjectiveTargetPool.release(objective, playerId)
        if (released.accepted) {
            runtime.state = runtime.state.copy(objective = released.state)
            port.persistAsync()
        }
        return true
    }

    fun cleanup() {
        carried.keys.toList().forEach(::releasePlayer)
    }

    private fun candidates(runtime: MineRuntime): List<ObjectiveTargetCandidate> =
        index.loadedTargets(runtime.settings.id, MineAnchorRole.RAIL).mapIndexed { index, position ->
            ObjectiveTargetCandidate(
                "crate_${index + 1}_${token(position.x)}_${token(position.z)}",
                position,
                ObjectiveTargetRole("ore_crate"),
                index.toLong(),
            )
        }

    private fun near(location: Location, position: WorksitePosition, radius: Double): Boolean {
        if (location.world.name != position.world) return false
        val dx = location.x - (position.x + 0.5)
        val dy = location.y - (position.y + 1.0)
        val dz = location.z - (position.z + 0.5)
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }

    private fun token(value: Int): String = if (value < 0) "m${value.toLong().absoluteValue}" else value.toString()
    private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)
}

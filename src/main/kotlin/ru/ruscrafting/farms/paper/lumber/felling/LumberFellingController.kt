package ru.ruscrafting.farms.paper.lumber.felling

import net.kyori.adventure.text.Component
import org.bukkit.Sound
import org.bukkit.event.block.BlockBreakEvent
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.MaterialRules
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.LumberTransitionCoordinator
import ru.ruscrafting.farms.paper.lumber.index.LumberBlockIndex
import ru.ruscrafting.farms.paper.lumber.recovery.LumberBlockRecoveryController
import java.util.logging.Level
import kotlin.math.absoluteValue

internal class LumberFellingController(
    private val registry: LumberRuntimeRegistry,
    private val index: LumberBlockIndex,
    private val recovery: LumberBlockRecoveryController,
    private val transitions: LumberTransitionCoordinator,
    private val port: WorksiteRuntimePort,
    private val clock: () -> Long,
    private val startSkidding: (
        LumberRuntime,
        WorksiteObjectiveState,
        ru.ruscrafting.farms.domain.LumberShiftState,
    ) -> ru.ruscrafting.farms.domain.LumberShiftState,
    private val canStartSkidding: (LumberRuntime, WorksiteObjectiveState) -> Boolean,
    private val reconcileSkidding: (LumberRuntime) -> Unit,
) {
    fun onBreakHigh(event: BlockBreakEvent): Boolean {
        val runtime = registry.at(event.block.location) ?: return false
        event.isCancelled = true
        port.traceBlockBreak(event, ru.ruscrafting.farms.domain.ActivityKind.LUMBER, runtime.settings.id)
        if (!port.hasAccess(event.player, runtime.settings.permission)) {
            port.sendChat(event.player, MessageKey.ZONE_LOCKED)
            return true
        }
        if (port.isAdminEditing(event.player)) return false.also { event.isCancelled = false }

        val brokenSpecies = MaterialRules.speciesOf(event.block.type)
        if (brokenSpecies == null || !index.contains(runtime.settings.id, event.block, brokenSpecies)) {
            remind(event.player, MessageKey.LUMBER_TARGET_REQUIRED)
            return true
        }
        if (!ensureStarted(runtime, brokenSpecies, event)) return true
        val species = runtime.state.species
        if (runtime.state.phase != LumberPhase.FELLING || brokenSpecies != species) {
            remind(
                event.player,
                MessageKey.LUMBER_WRONG_SPECIES,
                mapOf("wood" to MaterialRules.woodComponent(requireNotNull(species))),
            )
            return true
        }

        val position = event.block.position()
        val target = runtime.state.objective?.targets?.firstOrNull { it.position == position }
        if (target == null || target.status != ObjectiveTargetStatus.AVAILABLE) {
            remind(event.player, MessageKey.LUMBER_TARGET_REQUIRED)
            return true
        }
        val sequence = runtime.state.sequence
        recovery.prepare(
            runtime = runtime,
            player = event.player,
            block = event.block,
            tool = event.player.inventory.itemInMainHand,
            stillValid = {
                runtime.state.sequence == sequence && runtime.state.phase == LumberPhase.FELLING &&
                    runtime.state.objective?.target(target.id)?.status == ObjectiveTargetStatus.AVAILABLE
            },
            afterMutation = {
                val objective = requireNotNull(runtime.state.objective)
                val completed = ObjectiveTargetPool.complete(objective, target.id, event.player.uniqueId)
                if (!completed.accepted) return@prepare
                val felled = LumberShiftEngine.fell(
                    runtime.state.copy(objective = completed.state),
                    runtime.rules(),
                    brokenSpecies,
                    event.player.uniqueId,
                    clock(),
                )
                val finalState = if (felled.state.phase == LumberPhase.SKIDDING) {
                    startSkidding(runtime, completed.state, felled.state)
                } else {
                    felled.state.copy(objective = completed.state)
                }
                transitions.apply(runtime, felled.copy(state = finalState), event.player)
                if (finalState.phase == LumberPhase.SKIDDING) {
                    runCatching { reconcileSkidding(runtime) }.onFailure { failure ->
                        port.log(Level.WARNING, "Could not reconcile lumber bundles for ${runtime.settings.id}", failure)
                    }
                }
            },
        ).whenComplete { accepted, failure ->
            if (failure != null) {
                port.log(Level.WARNING, "Could not prepare lumber block ${runtime.settings.id}:${target.id}", failure)
                remind(event.player, MessageKey.LUMBER_JOURNAL_FAILED)
            } else if (accepted == false) {
                remind(event.player, MessageKey.LUMBER_TARGET_REQUIRED)
            }
        }
        return true
    }

    private fun ensureStarted(runtime: LumberRuntime, brokenSpecies: String, event: BlockBreakEvent): Boolean {
        if (runtime.state.phase == LumberPhase.COOLDOWN) {
            remind(event.player, MessageKey.COOLDOWN)
            return false
        }
        if (runtime.state.phase != LumberPhase.IDLE) return true
        val started = LumberShiftEngine.start(runtime.state, runtime.nextOrder().domain(), runtime.rules(runtime.nextOrder()), clock())
        val expectedSpecies = requireNotNull(started.state.species)
        if (brokenSpecies != expectedSpecies) {
            remind(
                event.player,
                MessageKey.LUMBER_WRONG_SPECIES,
                mapOf("wood" to MaterialRules.woodComponent(expectedSpecies)),
            )
            return false
        }
        val rules = runtime.rules(runtime.nextOrder())
        val candidates = candidates(runtime, expectedSpecies)
        if (candidates.size < rules.fellingQuota) {
            remind(event.player, MessageKey.ZONE_UNAVAILABLE)
            return false
        }
        val objective = ObjectiveTargetPool.plan(
            WorksiteObjectiveKey(runtime.settings.id, "felling", started.state.sequence),
            rules.fellingQuota,
            candidates,
        )
        if (!canStartSkidding(runtime, objective)) {
            remind(event.player, MessageKey.ZONE_UNAVAILABLE)
            return false
        }
        transitions.apply(runtime, started.copy(state = started.state.copy(objective = objective)), event.player)
        port.broadcast(
            listOf(runtime.region),
            MessageKey.LUMBER_STARTED,
            mapOf("wood" to MaterialRules.woodComponent(expectedSpecies)),
            Sound.BLOCK_WOOD_PLACE,
            title = true,
        )
        return true
    }

    private fun candidates(runtime: LumberRuntime, species: String): List<ObjectiveTargetCandidate> =
        index.loadedLogs(runtime.settings.id, species).mapNotNull { position ->
            val world = org.bukkit.Bukkit.getWorld(position.world) ?: return@mapNotNull null
            val block = world.getBlockAt(position.x, position.y, position.z)
            if (!block.getRelative(0, -1, 0).type.isOccluding) return@mapNotNull null
            ObjectiveTargetCandidate(
                id = "log_${token(position.x)}_${token(position.y)}_${token(position.z)}",
                position = position,
                role = ObjectiveTargetRole("log"),
                score = score(runtime, position),
            )
        }

    private fun score(runtime: LumberRuntime, position: WorksitePosition): Long {
        val centerX2 = runtime.station.bounds.minX.toLong() + runtime.station.bounds.maxX
        val centerZ2 = runtime.station.bounds.minZ.toLong() + runtime.station.bounds.maxZ
        val dx2 = position.x.toLong() * 2L - centerX2
        val dz2 = position.z.toLong() * 2L - centerZ2
        return dx2 * dx2 + dz2 * dz2
    }

    private fun token(value: Int): String = if (value < 0) "m${value.toLong().absoluteValue}" else value.toString()

    private fun remind(
        player: org.bukkit.entity.Player,
        key: MessageKey,
        values: Map<String, Component> = emptyMap(),
    ) {
        port.sendActionBar(player, key, values)
        port.showScreenTitle(player, key, values, scope = "lumber:${key.path}")
    }

    private fun org.bukkit.block.Block.position() = WorksitePosition(world.name, x, y, z)
}

package ru.ruscrafting.farms.paper.mine.incident.entity

import org.bukkit.Location
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.util.Vector
import ru.ruscrafting.farms.domain.MineScenarioAction
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.paper.mine.MineRuntime
import java.util.UUID

/** Bounded actors for entity-backed mine scenario stages. */
internal class MineScenarioActors(private val effects: MineIncidentEntityEffects) {
    private val tracked = linkedMapOf<String, MutableMap<String, UUID>>()
    private val activeActions = linkedMapOf<String, MineScenarioAction>()
    private val activeKinds = linkedMapOf<String, MineIncidentEntityKind>()

    fun reconcile(runtime: MineRuntime, action: MineScenarioAction, targets: List<ObjectiveTargetState>) {
        val key = key(runtime)
        val kind = actorKind(runtime, action)
        if (kind == null) {
            clear(key)
            return
        }
        if (activeActions[key] != action || activeKinds[key] != kind) {
            clear(key)
            activeActions[key] = action
            activeKinds[key] = kind
        }
        val expected = expectedTargets(kind, targets)
        val current = tracked.getOrPut(key) { linkedMapOf() }
        current.keys.filter { it !in expected }.toList().forEach { current.remove(it)?.let(effects::remove) }
        expected.forEach { (targetId, position) ->
            if (current[targetId]?.let(effects::entity)?.isValid != true) {
                current[targetId] = effects.spawn(runtime, kind, targetId, carriedPosition(targets.first { it.id == targetId }, position))
            }
            if (kind == MineIncidentEntityKind.MINER) current[targetId]?.let { syncMiner(it, targets.first { it.id == targetId }) }
        }
    }

    fun reconcileChunk(
        runtime: MineRuntime,
        chunk: org.bukkit.Chunk,
        action: MineScenarioAction,
        targets: List<ObjectiveTargetState>,
    ) {
        val key = key(runtime)
        val kind = actorKind(runtime, action)
        if (kind == null) {
            clear(key)
            return
        }
        if (activeActions[key] != action || activeKinds[key] != kind) clear(key)
        val expected = expectedTargets(kind, targets)
        val current = tracked.getOrPut(key) { linkedMapOf() }
        current.entries.removeIf { (_, id) -> effects.entity(id)?.isValid != true }
        current.putAll(effects.reconcileChunk(runtime, chunk, kind, expected))
        activeActions[key] = action
        activeKinds[key] = kind
    }

    /** Removes a dead actor from the bounded map so the next reconcile can respawn it. */
    fun death(event: EntityDeathEvent): MineIncidentEntityIdentity? {
        val identity = effects.identity(event.entity) ?: return null
        if (identity.kind !in setOf(MineIncidentEntityKind.CREATURE, MineIncidentEntityKind.HERD, MineIncidentEntityKind.MINER)) return null
        val key = "${identity.zoneId}:${identity.sequence}"
        if (tracked[key]?.remove(identity.targetId) != event.entity.uniqueId) return null
        event.drops.clear()
        event.droppedExp = 0
        return if (identity.kind == MineIncidentEntityKind.CREATURE && event.entity.killer != null) identity else null
    }

    /** Guides bounded no-AI bats through the clear central aisle to the activated lamp. */
    fun guide(runtime: MineRuntime, lamp: Location): Set<String> {
        val key = key(runtime)
        if (activeActions[key] != MineScenarioAction.HERD) return emptySet()
        val reached = linkedSetOf<String>()
        tracked[key].orEmpty().forEach { (targetId, id) ->
            val bat = effects.entity(id)?.takeIf { it.isValid && it.type == EntityType.BAT } ?: return@forEach
            if (bat.world !== lamp.world) return@forEach
            val location = bat.location
            val distance = location.distance(lamp)
            if (distance <= 1.8) {
                bat.velocity = Vector()
                reached += targetId
            } else {
                // No-AI bats ignore velocity. Advance the visible actor through the aisle rather than crediting a timer.
                val waypoint = if (kotlin.math.abs(location.x - lamp.x) > .5) Location(lamp.world, lamp.x, lamp.y, location.z) else lamp
                val delta = waypoint.toVector().subtract(location.toVector())
                val next = location.clone().add(delta.normalize().multiply(minOf(1.25, delta.length())))
                if (next.block.isPassable) bat.teleport(next)
            }
        }
        return reached
    }

    fun cleanup(runtime: MineRuntime) {
        val key = key(runtime)
        tracked.remove(key)?.values?.forEach(effects::remove)
        effects.cleanup(runtime, MineIncidentEntityKind.CREATURE)
        effects.cleanup(runtime, MineIncidentEntityKind.HERD)
        effects.cleanup(runtime, MineIncidentEntityKind.MINER)
        activeActions.remove(key)
        activeKinds.remove(key)
    }

    private fun actorKind(runtime: MineRuntime, action: MineScenarioAction): MineIncidentEntityKind? = when {
        runtime.state.incident?.type == MineIncidentType.INJURED_MINER &&
            action in setOf(MineScenarioAction.INTERACT, MineScenarioAction.CARRY) -> MineIncidentEntityKind.MINER
        action == MineScenarioAction.HERD -> MineIncidentEntityKind.HERD
        action == MineScenarioAction.DEFEND -> MineIncidentEntityKind.CREATURE
        else -> null
    }

    private fun carriedPosition(target: ObjectiveTargetState, fallback: ru.ruscrafting.farms.domain.worksite.WorksitePosition) =
        target.leasedBy?.let(Bukkit::getPlayer)?.let { behind(it) } ?: fallback

    private fun expectedTargets(kind: MineIncidentEntityKind, targets: List<ObjectiveTargetState>): Map<String, ru.ruscrafting.farms.domain.worksite.WorksitePosition> {
        val incomplete = targets.filter { it.status != ObjectiveTargetStatus.COMPLETED }
        return (if (kind == MineIncidentEntityKind.MINER) incomplete.take(1) else incomplete)
            .associate { it.id to carriedPosition(it, it.position) }
    }

    private fun behind(player: Player): ru.ruscrafting.farms.domain.worksite.WorksitePosition {
        val location = player.location.clone()
        val direction = location.direction.apply { y = 0.0 }.takeIf { it.lengthSquared() > 0.0 }?.normalize()
        val behind = if (direction == null) location else location.subtract(direction.multiply(1.25))
        return ru.ruscrafting.farms.domain.worksite.WorksitePosition(
            requireNotNull(behind.world).name, behind.blockX, behind.blockY, behind.blockZ,
        )
    }

    private fun syncMiner(id: UUID, target: ObjectiveTargetState) {
        val entity = effects.entity(id) ?: return
        val owner = target.leasedBy?.let(Bukkit::getPlayer)
        entity.setGravity(owner == null)
        entity.isInvulnerable = owner != null
        if (owner == null) {
            val p = target.position
            val world = entity.world.takeIf { it.name == p.world } ?: return
            val home = Location(world, p.x + .5, p.y + 1.0, p.z + .5)
            if (entity.world !== world || entity.location.distanceSquared(home) > .25) entity.teleport(home)
            return
        }
        val location = owner.location.clone()
        val direction = location.direction.apply { y = 0.0 }.takeIf { it.lengthSquared() > 0.0 }?.normalize() ?: return
        entity.teleport(location.subtract(direction.multiply(1.25)).apply { y = owner.location.y })
    }

    private fun clear(key: String) {
        tracked.remove(key)?.values?.forEach(effects::remove)
        activeActions.remove(key)
        activeKinds.remove(key)
    }

    private fun key(runtime: MineRuntime) = "${runtime.settings.id}:${runtime.state.sequence}"
}

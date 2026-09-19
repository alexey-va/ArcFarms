package ru.ruscrafting.farms.paper.mine.incident.rescue

import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import java.util.UUID

/** A few guards along the reachable cave; no loot is exported from a rescue. */
internal class MineRescueCreatures(
    private val registry: MineRuntimeRegistry,
    private val effects: MineIncidentEntityEffects,
    private val access: WorksiteAccessPort,
) {
    private data class Guards(val sequence: Long, val expected: Map<String, WorksitePosition>,
        val living: MutableMap<String, UUID> = mutableMapOf(), val defeated: MutableSet<String> = mutableSetOf())
    private val guards = mutableMapOf<String, Guards>()

    fun reconcile(runtime: MineRuntime, scene: MineLostMinerMazeScene) {
        val current = guards.getOrPut(runtime.settings.id) {
            val candidates = scene.records.asSequence().filter { it.y == scene.start.blockY && it.mazeData == "minecraft:air" }
                .map { org.bukkit.Location(scene.world, it.x + .5, it.y.toDouble(), it.z + .5) }
                .filter { it.distanceSquared(scene.start) > 36 && it.distanceSquared(scene.target) > 9 }
                .mapNotNull { location -> scene.pathDistanceToTarget(location)?.let { it to location } }
                .sortedBy { it.first }.toList()
            val points = (1..3).mapNotNull { ordinal -> candidates.getOrNull(candidates.size * ordinal / 4)?.second }
                .distinctBy { it.blockX to it.blockZ }
            Guards(runtime.state.sequence, points.mapIndexed { index, point ->
                "rescue_guard_$index" to WorksitePosition(scene.world.name, point.blockX, point.blockY, point.blockZ)
            }.toMap())
        }
        current.expected.filterKeys { it !in current.defeated }.forEach { (id, point) ->
            if (current.living[id]?.let(effects::entity)?.isValid == true) return@forEach
            if (scene.world.isChunkLoaded(point.x shr 4, point.z shr 4)) {
                current.living[id] = effects.spawn(runtime, MineIncidentEntityKind.RESCUE_CREATURE, id, point)
            }
        }
    }

    fun onDeath(event: EntityDeathEvent): Boolean {
        val identity = effects.identity(event.entity)?.takeIf { it.kind == MineIncidentEntityKind.RESCUE_CREATURE } ?: return false
        event.drops.clear()
        event.droppedExp = 0
        guards[identity.zoneId]?.takeIf { it.sequence == identity.sequence }?.let {
            it.defeated += identity.targetId
            it.living.remove(identity.targetId)
        }
        return true
    }

    fun onDamage(event: EntityDamageEvent): Boolean {
        val identity = effects.identity(event.entity)?.takeIf { it.kind == MineIncidentEntityKind.RESCUE_CREATURE } ?: return false
        event.isCancelled = true
        val runtime = registry.byId(identity.zoneId) ?: return true
        if (runtime.state.sequence != identity.sequence || runtime.state.incident?.type != MineIncidentType.LOST_MINER) return true
        val damager = (event as? EntityDamageByEntityEvent)?.damager
        val player = damager as? Player ?: (damager as? Projectile)?.shooter as? Player ?: return true
        if (access.hasAccess(player, runtime.settings.permission) && player.world === event.entity.world &&
            player.location.distanceSquared(event.entity.location) < 40 * 40) event.isCancelled = false
        return true
    }

    fun cleanup(runtime: MineRuntime) {
        guards.remove(runtime.settings.id)?.living?.values?.forEach(effects::remove)
        effects.cleanup(runtime, MineIncidentEntityKind.RESCUE_CREATURE)
    }
}

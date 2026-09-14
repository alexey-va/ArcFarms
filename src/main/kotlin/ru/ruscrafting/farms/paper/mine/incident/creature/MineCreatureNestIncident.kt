package ru.ruscrafting.farms.paper.mine.incident.creature

import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import ru.ruscrafting.farms.config.ArcFarmsLocale
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.MinePhase
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.MineRuntimeRegistry
import ru.ruscrafting.farms.paper.mine.incident.MineIncidentCoordinator
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityEffects
import ru.ruscrafting.farms.paper.mine.incident.entity.MineIncidentEntityKind
import ru.ruscrafting.farms.paper.mine.incident.orderMineIncidentPositions
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort

/** Farm-style cave infestation: every site has one glowing, breakable nest and one glowing creature. */
internal class MineCreatureNestIncident(
    private val registry: MineRuntimeRegistry,
    private val index: MineBlockIndex,
    private val incidents: MineIncidentCoordinator,
    private val effects: MineIncidentEntityEffects,
    private val access: WorksiteAccessPort,
    private val locale: ArcFarmsLocale? = null,
) {
    private val entities = mutableMapOf<String, MutableMap<MineIncidentEntityKind, MutableMap<String, java.util.UUID>>>()

    fun start(runtime: MineRuntime, required: Int, now: Long): Boolean {
        val candidates = candidates(runtime, required)
        if (candidates.size < required * COMPONENTS_PER_SITE) return false
        if (!incidents.start(runtime, MineIncidentType.CREATURE_NEST, candidates.size, now, candidates)) return false
        runtime.region.world.loadedChunks.forEach { reconcileChunk(runtime, it) }
        return true
    }

    fun defeat(runtime: MineRuntime, targetId: String, player: Player): Boolean {
        if (!active(runtime) || runtime.state.objective?.target(targetId)?.role?.value != CREATURE_ROLE) return false
        removeTarget(runtime, MineIncidentEntityKind.CREATURE, targetId)
        val completed = incidents.completeTarget(runtime, targetId, player).accepted
        if (completed && !active(runtime)) cleanup(runtime)
        return completed
    }

    fun destroyNest(runtime: MineRuntime, targetId: String, player: Player): Boolean {
        val target = runtime.state.objective?.target(targetId) ?: return false
        if (!active(runtime) || target.role.value != NEST_ROLE) return false
        removeTarget(runtime, MineIncidentEntityKind.CREATURE_NEST_DISPLAY, targetId)
        removeTarget(runtime, MineIncidentEntityKind.CREATURE_NEST_HITBOX, targetId)
        val completed = incidents.completeTarget(runtime, targetId, player).accepted
        if (completed) {
            val location = target.position.location(runtime).add(0.5, 1.0, 0.5)
            runtime.region.world.playSound(location, Sound.BLOCK_WOOD_BREAK, 0.9f, 0.75f)
            runtime.region.world.spawnParticle(
                Particle.BLOCK,
                location,
                8,
                0.35,
                0.3,
                0.35,
                0.04,
                Material.MANGROVE_ROOTS.createBlockData(),
            )
            locale?.renderPath("mine.creature-nest.destroyed", player)?.let(player::sendActionBar)
        }
        if (completed && !active(runtime)) cleanup(runtime)
        return completed
    }

    fun onDamage(event: EntityDamageEvent): Boolean {
        val identity = effects.identity(event.entity) ?: return false
        if (identity.kind !in OWNED_KINDS) return false
        event.isCancelled = true
        val runtime = registry.byId(identity.zoneId) ?: return true
        if (!active(runtime) || runtime.state.sequence != identity.sequence || !runtime.region.contains(event.entity.location)) return true
        val attacker = (event as? EntityDamageByEntityEvent)?.playerDamager() ?: return true
        if (!access.hasAccess(attacker, runtime.settings.permission)) return true
        when (identity.kind) {
            MineIncidentEntityKind.CREATURE -> event.isCancelled = false
            MineIncidentEntityKind.CREATURE_NEST_HITBOX -> destroyNest(runtime, identity.targetId, attacker)
            MineIncidentEntityKind.CREATURE_NEST_DISPLAY -> Unit
            else -> Unit
        }
        return true
    }

    fun onDeath(event: EntityDeathEvent): Boolean {
        val identity = effects.identity(event.entity) ?: return false
        if (identity.kind != MineIncidentEntityKind.CREATURE) return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        if (!active(runtime) || runtime.state.sequence != identity.sequence) return false
        event.drops.clear()
        event.droppedExp = 0
        val killer = event.entity.killer
        if (killer != null && access.hasAccess(killer, runtime.settings.permission) && runtime.region.contains(event.entity.location)) {
            defeat(runtime, identity.targetId, killer)
        }
        return true
    }

    fun reconcileChunk(runtime: MineRuntime, chunk: Chunk): Int {
        if (!active(runtime)) return 0
        val pending = runtime.state.objective?.targets.orEmpty().filter { it.status != ObjectiveTargetStatus.COMPLETED }
        reconcileKind(runtime, chunk, MineIncidentEntityKind.CREATURE, pending.filter { it.role.value == CREATURE_ROLE }
            .associate { it.id to it.position })
        val nests = pending.filter { it.role.value == NEST_ROLE }.associate { it.id to it.position }
        reconcileKind(runtime, chunk, MineIncidentEntityKind.CREATURE_NEST_DISPLAY, nests)
        reconcileKind(runtime, chunk, MineIncidentEntityKind.CREATURE_NEST_HITBOX, nests)
        return OWNED_KINDS.sumOf { tracked(runtime, it).size }
    }

    fun spawnedCount(runtime: MineRuntime): Int = tracked(runtime, MineIncidentEntityKind.CREATURE).size
    fun nestCount(runtime: MineRuntime): Int = tracked(runtime, MineIncidentEntityKind.CREATURE_NEST_DISPLAY).size

    /** Hot-tick safety net: only missing tracked UUIDs cause an exact loaded objective chunk reconcile. */
    fun reconcileMissing(runtime: MineRuntime): Int {
        if (!active(runtime)) return 0
        val missing = runtime.state.objective?.targets.orEmpty().filter { target ->
            if (target.status == ObjectiveTargetStatus.COMPLETED) return@filter false
            kinds(target.role.value).any { kind -> tracked(runtime, kind)[target.id]?.let(effects::entity) == null }
        }
        missing.map { it.position }.distinctBy { (it.x shr 4) to (it.z shr 4) }.forEach { position ->
            val world = runtime.region.world
            val chunkX = position.x shr 4
            val chunkZ = position.z shr 4
            if (world.isChunkLoaded(chunkX, chunkZ)) reconcileChunk(runtime, world.getChunkAt(chunkX, chunkZ))
        }
        return OWNED_KINDS.sumOf { tracked(runtime, it).size }
    }

    fun cleanup(runtime: MineRuntime) {
        entities.remove(key(runtime))?.values?.flatMap { it.values }?.forEach(effects::remove)
        OWNED_KINDS.forEach { effects.cleanup(runtime, it) }
    }

    private fun reconcileKind(
        runtime: MineRuntime,
        chunk: Chunk,
        kind: MineIncidentEntityKind,
        expected: Map<String, WorksitePosition>,
    ) {
        val current = tracked(runtime, kind)
        current.entries.removeIf { (_, id) -> effects.entity(id) == null }
        current.putAll(effects.reconcileChunk(runtime, chunk, kind, expected))
        current.values.mapNotNull(effects::entity).forEach { applyPresentation(it, kind) }
    }

    private fun applyPresentation(entity: Entity, kind: MineIncidentEntityKind) {
        val path = when (kind) {
            MineIncidentEntityKind.CREATURE -> "mine.creature-nest.creature-name"
            MineIncidentEntityKind.CREATURE_NEST_HITBOX -> "mine.creature-nest.nest-name"
            else -> return
        }
        entity.customName(locale?.renderPath(path, null) ?: Component.empty())
        entity.isCustomNameVisible = true
    }

    private fun active(runtime: MineRuntime): Boolean =
        runtime.state.phase == MinePhase.INCIDENT && runtime.state.incident?.type == MineIncidentType.CREATURE_NEST

    private fun candidates(runtime: MineRuntime, required: Int): List<ObjectiveTargetCandidate> {
        val positions = orderMineIncidentPositions(
            runtime,
            index.loadedTargets(runtime.settings.id, MineAnchorRole.NEST)
                .filter { index.isLiveTarget(runtime.settings.id, it, MineAnchorRole.NEST, runtime.railMaterials) },
            required,
            0xCEEA7L,
        ).take(required)
        return positions.flatMapIndexed { order, position ->
            listOf(
                ObjectiveTargetCandidate("nest_${order + 1}", position, ObjectiveTargetRole(NEST_ROLE), order * 2L),
                ObjectiveTargetCandidate(
                    "creature_${order + 1}", position.copy(y = position.y + 1),
                    ObjectiveTargetRole(CREATURE_ROLE), order * 2L + 1L,
                ),
            )
        }
    }

    private fun tracked(runtime: MineRuntime, kind: MineIncidentEntityKind): MutableMap<String, java.util.UUID> =
        entities.getOrPut(key(runtime), ::linkedMapOf).getOrPut(kind, ::linkedMapOf)

    private fun removeTarget(runtime: MineRuntime, kind: MineIncidentEntityKind, targetId: String) {
        tracked(runtime, kind).remove(targetId)?.let(effects::remove)
    }

    private fun kinds(role: String): Set<MineIncidentEntityKind> = when (role) {
        CREATURE_ROLE -> setOf(MineIncidentEntityKind.CREATURE)
        NEST_ROLE -> setOf(MineIncidentEntityKind.CREATURE_NEST_DISPLAY, MineIncidentEntityKind.CREATURE_NEST_HITBOX)
        else -> emptySet()
    }

    private fun EntityDamageByEntityEvent.playerDamager(): Player? = when (val source = damager) {
        is Player -> source
        is Projectile -> source.shooter as? Player
        else -> null
    }

    private fun key(runtime: MineRuntime) = "${runtime.settings.id}:${runtime.state.sequence}"

    private fun WorksitePosition.location(runtime: MineRuntime) =
        org.bukkit.Location(runtime.region.world, x.toDouble(), y.toDouble(), z.toDouble())

    private companion object {
        const val COMPONENTS_PER_SITE = 2
        const val NEST_ROLE = "creature_nest"
        const val CREATURE_ROLE = "creature"
        val OWNED_KINDS = setOf(
            MineIncidentEntityKind.CREATURE,
            MineIncidentEntityKind.CREATURE_NEST_DISPLAY,
            MineIncidentEntityKind.CREATURE_NEST_HITBOX,
        )
    }
}

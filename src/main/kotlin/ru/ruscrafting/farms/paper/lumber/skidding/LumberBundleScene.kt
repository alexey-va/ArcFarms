package ru.ruscrafting.farms.paper.lumber.skidding

import org.bukkit.Axis
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.Orientable
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveState
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.WorksiteRuntimePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.LumberTransitionCoordinator
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import java.util.UUID

internal data class LumberBundleIdentity(
    val zoneId: String,
    val sequence: Long,
    val targetId: String,
)

internal interface LumberBundleEffects {
    fun showGround(runtime: LumberRuntime, targetId: String, position: WorksitePosition)
    fun hideGround(runtime: LumberRuntime, targetId: String)
    fun showCarried(runtime: LumberRuntime, targetId: String, player: Player)
    fun moveCarried(player: Player)
    fun hideCarried(playerId: UUID)
    fun cleanupZone(zoneId: String)
    fun identity(entity: Entity): LumberBundleIdentity? = null
}

/**
 * Vanilla block displays are deliberate here: the available IA firewood source has no resolvable
 * texture PNG in the checked-in client pack, so it cannot pass the mandatory grounding proof.
 */
internal class PaperLumberBundleEffects(
    private val plugin: Plugin,
) : LumberBundleEffects {
    private data class Key(val zoneId: String, val sequence: Long, val targetId: String)

    private val groundDisplays = mutableMapOf<Key, UUID>()
    private val groundInteractions = mutableMapOf<Key, UUID>()
    private val carriedDisplays = mutableMapOf<UUID, UUID>()
    private val carriedKeys = mutableMapOf<UUID, Key>()
    private val zoneKey = NamespacedKey(plugin, "lumber_bundle_zone")
    private val sequenceKey = NamespacedKey(plugin, "lumber_bundle_sequence")
    private val targetKey = NamespacedKey(plugin, "lumber_bundle_target")

    override fun showGround(runtime: LumberRuntime, targetId: String, position: WorksitePosition) {
        val key = Key(runtime.settings.id, runtime.state.sequence, targetId)
        val currentDisplay = groundDisplays[key]?.let(Bukkit::getEntity)
        val currentInteraction = groundInteractions[key]?.let(Bukkit::getEntity)
        if (currentDisplay?.isValid == true && currentInteraction?.isValid == true) return
        remove(groundDisplays.remove(key))
        remove(groundInteractions.remove(key))
        val world = Bukkit.getWorld(position.world) ?: return
        val display = world.spawn(
            Location(world, position.x + 0.05, position.y.toDouble(), position.z + 0.25),
            BlockDisplay::class.java,
        ) { entity ->
            entity.block = bundleBlock(runtime)
            entity.transformation = Transformation(
                Vector3f(),
                AxisAngle4f(),
                Vector3f(0.90f, 0.50f, 0.50f),
                AxisAngle4f(),
            )
            entity.isGlowing = true
            entity.viewRange = 48.0f
            tag(entity, key)
        }
        val interaction = world.spawn(
            Location(world, position.x + 0.5, position.y.toDouble(), position.z + 0.5),
            Interaction::class.java,
        ) { entity ->
            entity.interactionWidth = 1.35f
            entity.interactionHeight = 1.10f
            entity.isResponsive = true
            tag(entity, key)
        }
        groundDisplays[key] = display.uniqueId
        groundInteractions[key] = interaction.uniqueId
    }

    override fun hideGround(runtime: LumberRuntime, targetId: String) {
        val key = Key(runtime.settings.id, runtime.state.sequence, targetId)
        remove(groundDisplays.remove(key))
        remove(groundInteractions.remove(key))
    }

    override fun showCarried(runtime: LumberRuntime, targetId: String, player: Player) {
        hideCarried(player.uniqueId)
        val key = Key(runtime.settings.id, runtime.state.sequence, targetId)
        val display = player.world.spawn(carriedLocation(player), BlockDisplay::class.java) { entity ->
            entity.block = bundleBlock(runtime)
            entity.transformation = Transformation(
                Vector3f(),
                AxisAngle4f(),
                Vector3f(0.80f, 0.45f, 0.45f),
                AxisAngle4f(),
            )
            entity.viewRange = 48.0f
            tag(entity, key)
        }
        carriedDisplays[player.uniqueId] = display.uniqueId
        carriedKeys[player.uniqueId] = key
    }

    override fun moveCarried(player: Player) {
        val display = carriedDisplays[player.uniqueId]?.let(Bukkit::getEntity) as? BlockDisplay ?: return
        val target = carriedLocation(player)
        if (display.world !== target.world) display.teleport(target) else display.teleport(target)
    }

    override fun hideCarried(playerId: UUID) {
        remove(carriedDisplays.remove(playerId))
        carriedKeys.remove(playerId)
    }

    override fun cleanupZone(zoneId: String) {
        groundDisplays.keys.filter { it.zoneId == zoneId }.toList().forEach { key -> remove(groundDisplays.remove(key)) }
        groundInteractions.keys.filter { it.zoneId == zoneId }.toList().forEach { key -> remove(groundInteractions.remove(key)) }
        carriedKeys.filterValues { it.zoneId == zoneId }.keys.toList().forEach(::hideCarried)
    }

    override fun identity(entity: Entity): LumberBundleIdentity? {
        val pdc = entity.persistentDataContainer
        val zone = pdc.get(zoneKey, PersistentDataType.STRING) ?: return null
        val sequence = pdc.get(sequenceKey, PersistentDataType.LONG) ?: return null
        val target = pdc.get(targetKey, PersistentDataType.STRING) ?: return null
        return LumberBundleIdentity(zone, sequence, target)
    }

    private fun bundleBlock(runtime: LumberRuntime): org.bukkit.block.data.BlockData {
        val species = requireNotNull(runtime.state.species)
        val material = sequenceOf("${species}_LOG", "${species}_STEM", "${species}_WOOD", "${species}_HYPHAE")
            .mapNotNull(Material::matchMaterial)
            .firstOrNull() ?: Material.OAK_LOG
        return material.createBlockData().also { data ->
            if (data is Orientable && Axis.X in data.axes) data.axis = Axis.X
        }
    }

    private fun tag(entity: Entity, key: Key) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, key.zoneId)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, key.sequence)
        entity.persistentDataContainer.set(targetKey, PersistentDataType.STRING, key.targetId)
        entity.isPersistent = false
    }

    private fun carriedLocation(player: Player): Location {
        val direction = player.location.direction.setY(0).normalize().multiply(0.85)
        return player.location.clone().add(direction).add(-0.4, 1.0, -0.225)
    }

    private fun remove(entityId: UUID?) {
        entityId?.let(Bukkit::getEntity)?.remove()
    }
}

internal class LumberBundleScene(
    private val registry: LumberRuntimeRegistry,
    private val effects: LumberBundleEffects,
    private val transitions: LumberTransitionCoordinator,
    private val port: WorksiteRuntimePort,
    private val clock: () -> Long,
) {
    private data class Carry(val zoneId: String, val sequence: Long, val targetId: String)

    private val carried = mutableMapOf<UUID, Carry>()

    fun begin(
        runtime: LumberRuntime,
        fellingObjective: WorksiteObjectiveState,
        phaseState: LumberShiftState,
    ): LumberShiftState {
        val candidates = stageCandidates(runtime, fellingObjective)
        require(candidates.size >= runtime.rules().skiddingQuota * 2) {
            "Lumber zone ${runtime.settings.id} lost safe bundle staging positions"
        }
        val objective = ObjectiveTargetPool.plan(
            WorksiteObjectiveKey(runtime.settings.id, "skidding", phaseState.sequence),
            runtime.rules().skiddingQuota,
            candidates,
        )
        return phaseState.copy(objective = objective)
    }

    fun canStage(runtime: LumberRuntime, fellingObjective: WorksiteObjectiveState): Boolean =
        stageCandidates(runtime, fellingObjective).size >= runtime.rules().skiddingQuota * 2

    fun reconcile(runtime: LumberRuntime) {
        if (runtime.state.phase != LumberPhase.SKIDDING) {
            effects.cleanupZone(runtime.settings.id)
            carried.entries.removeIf { it.value.zoneId == runtime.settings.id }
            return
        }
        releaseOrphanedLeases(runtime)
        runtime.state.objective?.targets.orEmpty().forEach { target ->
            when (target.status) {
                ObjectiveTargetStatus.AVAILABLE -> effects.showGround(runtime, target.id, target.position)
                ObjectiveTargetStatus.LEASED, ObjectiveTargetStatus.COMPLETED -> effects.hideGround(runtime, target.id)
            }
        }
    }

    fun pickup(runtime: LumberRuntime, targetId: String, player: Player): Boolean {
        if (runtime.state.phase != LumberPhase.SKIDDING || player.uniqueId in carried) return false
        if (!port.hasAccess(player, runtime.settings.permission)) return false
        val objective = runtime.state.objective ?: return false
        val leased = ObjectiveTargetPool.lease(objective, targetId, player.uniqueId, clock())
        if (!leased.accepted) return false
        runtime.state = runtime.state.copy(objective = leased.state)
        carried[player.uniqueId] = Carry(runtime.settings.id, runtime.state.sequence, targetId)
        effects.hideGround(runtime, targetId)
        effects.showCarried(runtime, targetId, player)
        port.persistAsync()
        return true
    }

    fun deliver(runtime: LumberRuntime, player: Player): Boolean {
        val carry = carried[player.uniqueId] ?: return false
        if (carry.zoneId != runtime.settings.id || carry.sequence != runtime.state.sequence) return false
        val objective = runtime.state.objective ?: return false
        val completed = ObjectiveTargetPool.complete(objective, carry.targetId, player.uniqueId)
        if (!completed.accepted) return false
        carried.remove(player.uniqueId)
        effects.hideCarried(player.uniqueId)
        val result = LumberShiftEngine.skid(runtime.state.copy(objective = completed.state), runtime.rules(), player.uniqueId)
        transitions.apply(runtime, result, player)
        if (runtime.state.phase == LumberPhase.SKIDDING) reconcile(runtime) else effects.cleanupZone(runtime.settings.id)
        return result.accepted
    }

    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason): Boolean {
        return releasePlayer(player.uniqueId)
    }

    private fun releasePlayer(playerId: UUID): Boolean {
        val carry = carried.remove(playerId) ?: return false
        val runtime = registry.byId(carry.zoneId)
        effects.hideCarried(playerId)
        if (runtime == null || runtime.state.sequence != carry.sequence) return true
        val objective = runtime.state.objective ?: return true
        val released = ObjectiveTargetPool.release(objective, playerId)
        if (released.accepted) {
            runtime.state = runtime.state.copy(objective = released.state)
            released.state.target(carry.targetId)?.let { effects.showGround(runtime, it.id, it.position) }
            port.persistAsync()
        }
        return true
    }

    fun carrying(playerId: UUID): LumberBundleIdentity? = carried[playerId]?.let {
        LumberBundleIdentity(it.zoneId, it.sequence, it.targetId)
    }

    fun identity(entity: Entity): LumberBundleIdentity? = effects.identity(entity)

    fun updateCarried() = carried.keys.toList().forEach { playerId ->
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !player.isOnline) {
            releasePlayer(playerId)
        } else {
            renewExpiredLease(playerId)
            effects.moveCarried(player)
        }
    }

    fun cleanup() {
        registry.snapshot().forEach { effects.cleanupZone(it.settings.id) }
        carried.clear()
    }

    private fun stageCandidates(
        runtime: LumberRuntime,
        fellingObjective: WorksiteObjectiveState,
    ): List<ObjectiveTargetCandidate> {
        val occupiedLogs = fellingObjective.targets.mapTo(hashSetOf()) { it.position }
        val staged = linkedSetOf<WorksitePosition>()
        val completed = fellingObjective.targets.filter { it.status == ObjectiveTargetStatus.COMPLETED }
        val sources = completed.ifEmpty { fellingObjective.targets }
        sources.sortedBy { it.score }.forEach { target ->
            STAGING_OFFSETS.forEach { (dx, dz) ->
                val position = target.position.copy(x = target.position.x + dx, z = target.position.z + dz)
                if (position !in occupiedLogs && position !in staged && isSafeStage(runtime, position)) staged += position
            }
        }
        return staged.mapIndexed { index, position ->
            ObjectiveTargetCandidate(
                id = "bundle_${index + 1}",
                position = position,
                role = ObjectiveTargetRole("bundle"),
                score = index.toLong(),
            )
        }
    }

    private fun isSafeStage(runtime: LumberRuntime, position: WorksitePosition): Boolean {
        val world = Bukkit.getWorld(position.world) ?: return false
        val location = Location(world, position.x.toDouble(), position.y.toDouble(), position.z.toDouble())
        if (!runtime.region.contains(location)) return false
        val feet = world.getBlockAt(position.x, position.y, position.z)
        return feet.isPassable && feet.getRelative(0, 1, 0).isPassable && feet.getRelative(0, -1, 0).type.isOccluding
    }

    private fun releaseOrphanedLeases(runtime: LumberRuntime) {
        var objective = runtime.state.objective ?: return
        val activeOwners = carried.filterValues { it.zoneId == runtime.settings.id && it.sequence == runtime.state.sequence }.keys
        objective.targets.asSequence()
            .filter { it.status == ObjectiveTargetStatus.LEASED && it.leasedBy !in activeOwners }
            .mapNotNull { it.leasedBy }
            .distinct()
            .forEach { playerId -> objective = ObjectiveTargetPool.release(objective, playerId).state }
        if (objective != runtime.state.objective) {
            runtime.state = runtime.state.copy(objective = objective)
            port.persistAsync()
        }
    }

    private fun renewExpiredLease(playerId: UUID) {
        val carry = carried[playerId] ?: return
        val runtime = registry.byId(carry.zoneId) ?: return
        val objective = runtime.state.objective ?: return
        val target = objective.target(carry.targetId) ?: return
        if (target.status != ObjectiveTargetStatus.LEASED || target.leasedBy != playerId || clock() < target.leaseExpiresAt) return
        val renewed = ObjectiveTargetPool.lease(objective, carry.targetId, playerId, clock())
        if (renewed.accepted) {
            runtime.state = runtime.state.copy(objective = renewed.state)
            port.persistAsync()
        }
    }

    private companion object {
        val STAGING_OFFSETS = buildList {
            for (radius in 1..3) {
                for (dx in -radius..radius) {
                    for (dz in -radius..radius) {
                        if (kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dz)) == radius) add(dx to dz)
                    }
                }
            }
        }
    }
}

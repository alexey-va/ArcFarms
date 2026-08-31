package ru.ruscrafting.farms.paper.lumber.stacking

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.ruscrafting.farms.config.MessageKey
import ru.ruscrafting.farms.domain.LumberPhase
import ru.ruscrafting.farms.domain.LumberShiftEngine
import ru.ruscrafting.farms.domain.LumberShiftState
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetCandidate
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetPool
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetRole
import ru.ruscrafting.farms.domain.worksite.ObjectiveTargetStatus
import ru.ruscrafting.farms.domain.worksite.WorksiteObjectiveKey
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.worksite.WorksiteAccessPort
import ru.ruscrafting.farms.paper.worksite.WorksiteAudiencePort
import ru.ruscrafting.farms.paper.lumber.LumberRuntime
import ru.ruscrafting.farms.paper.lumber.LumberRuntimeRegistry
import ru.ruscrafting.farms.paper.lumber.LumberTransitionCoordinator
import ru.ruscrafting.farms.paper.worksite.WorksitePlayerReleaseReason
import ru.ruscrafting.farms.paper.worksite.WorksiteCarryable
import java.util.UUID

internal data class LumberPalletIdentity(val zoneId: String, val sequence: Long, val targetId: String)

internal interface LumberStackingEffects {
    fun showPallet(runtime: LumberRuntime, targetId: String, position: WorksitePosition)
    fun hidePallet(runtime: LumberRuntime, targetId: String)
    fun showCarried(runtime: LumberRuntime, player: Player)
    fun moveCarried(player: Player)
    fun hideCarried(playerId: UUID)
    fun cleanupZone(zoneId: String)
    fun identity(entity: Entity): LumberPalletIdentity? = null
}

internal class PaperLumberStackingEffects(private val plugin: Plugin) : LumberStackingEffects {
    private data class Key(val zoneId: String, val sequence: Long, val targetId: String)

    private val displays = mutableMapOf<Key, UUID>()
    private val interactions = mutableMapOf<Key, UUID>()
    private val carried = mutableMapOf<UUID, UUID>()
    private val carriedZones = mutableMapOf<UUID, String>()
    private val zoneKey = NamespacedKey(plugin, "lumber_pallet_zone")
    private val sequenceKey = NamespacedKey(plugin, "lumber_pallet_sequence")
    private val targetKey = NamespacedKey(plugin, "lumber_pallet_target")

    override fun showPallet(runtime: LumberRuntime, targetId: String, position: WorksitePosition) {
        val key = Key(runtime.settings.id, runtime.state.sequence, targetId)
        if (displays[key]?.let(Bukkit::getEntity)?.isValid == true && interactions[key]?.let(Bukkit::getEntity)?.isValid == true) return
        remove(displays.remove(key))
        remove(interactions.remove(key))
        val world = Bukkit.getWorld(position.world) ?: return
        val display = world.spawn(
            Location(world, position.x + 0.05, position.y.toDouble(), position.z + 0.05),
            BlockDisplay::class.java,
        ) { entity ->
            entity.block = Material.OAK_PLANKS.createBlockData()
            entity.transformation = Transformation(
                Vector3f(), AxisAngle4f(), Vector3f(0.90f, 0.08f, 0.90f), AxisAngle4f(),
            )
            entity.isGlowing = true
            entity.isPersistent = false
            tag(entity, key)
        }
        val interaction = world.spawn(
            Location(world, position.x + 0.5, position.y.toDouble(), position.z + 0.5),
            Interaction::class.java,
        ) { entity ->
            entity.interactionWidth = 1.25f
            entity.interactionHeight = 0.80f
            entity.isResponsive = true
            entity.isPersistent = false
            tag(entity, key)
        }
        displays[key] = display.uniqueId
        interactions[key] = interaction.uniqueId
    }

    override fun hidePallet(runtime: LumberRuntime, targetId: String) {
        val key = Key(runtime.settings.id, runtime.state.sequence, targetId)
        remove(displays.remove(key))
        remove(interactions.remove(key))
    }

    override fun showCarried(runtime: LumberRuntime, player: Player) {
        hideCarried(player.uniqueId)
        val display = player.world.spawn(carriedLocation(player), BlockDisplay::class.java) { entity ->
            entity.block = Material.OAK_PLANKS.createBlockData()
            entity.transformation = Transformation(
                Vector3f(), AxisAngle4f(), Vector3f(0.72f, 0.18f, 0.72f), AxisAngle4f(),
            )
            entity.isPersistent = false
        }
        carried[player.uniqueId] = display.uniqueId
        carriedZones[player.uniqueId] = runtime.settings.id
    }

    override fun moveCarried(player: Player) {
        (carried[player.uniqueId]?.let(Bukkit::getEntity) as? BlockDisplay)?.teleport(carriedLocation(player))
    }

    override fun hideCarried(playerId: UUID) {
        remove(carried.remove(playerId))
        carriedZones.remove(playerId)
    }

    override fun cleanupZone(zoneId: String) {
        displays.keys.filter { it.zoneId == zoneId }.toList().forEach { key -> remove(displays.remove(key)) }
        interactions.keys.filter { it.zoneId == zoneId }.toList().forEach { key -> remove(interactions.remove(key)) }
        carriedZones.filterValues { it == zoneId }.keys.toList().forEach(::hideCarried)
    }

    override fun identity(entity: Entity): LumberPalletIdentity? {
        val pdc = entity.persistentDataContainer
        return LumberPalletIdentity(
            pdc.get(zoneKey, PersistentDataType.STRING) ?: return null,
            pdc.get(sequenceKey, PersistentDataType.LONG) ?: return null,
            pdc.get(targetKey, PersistentDataType.STRING) ?: return null,
        )
    }

    private fun tag(entity: Entity, key: Key) {
        entity.persistentDataContainer.set(zoneKey, PersistentDataType.STRING, key.zoneId)
        entity.persistentDataContainer.set(sequenceKey, PersistentDataType.LONG, key.sequence)
        entity.persistentDataContainer.set(targetKey, PersistentDataType.STRING, key.targetId)
    }

    private fun carriedLocation(player: Player) = WorksiteCarryable.carriedLocation(player, 0.75, 1.0, -0.36, -0.36)

    private fun remove(entityId: UUID?) {
        entityId?.let(Bukkit::getEntity)?.remove()
    }
}

internal class LumberStackingScene(
    private val registry: LumberRuntimeRegistry,
    private val effects: LumberStackingEffects,
    private val transitions: LumberTransitionCoordinator,
    private val access: WorksiteAccessPort,
) {
    private data class Carry(val zoneId: String, val sequence: Long)
    private val carried = mutableMapOf<UUID, Carry>()

    fun begin(runtime: LumberRuntime, phaseState: LumberShiftState): LumberShiftState {
        val required = runtime.rules().stackingQuota
        val candidates = slotCandidates(runtime)
        require(candidates.size >= required * 2) { "Lumber station ${runtime.settings.id} has too few safe pallet slots" }
        return phaseState.copy(
            objective = ObjectiveTargetPool.plan(
                WorksiteObjectiveKey(runtime.settings.id, "stacking", phaseState.sequence),
                required,
                candidates,
            ),
        )
    }

    fun reconcile(runtime: LumberRuntime) {
        if (runtime.state.phase != LumberPhase.STACKING) {
            cleanupZone(runtime.settings.id)
            return
        }
        runtime.state.objective?.targets.orEmpty().forEach { target ->
            if (target.status == ObjectiveTargetStatus.COMPLETED) effects.hidePallet(runtime, target.id)
            else effects.showPallet(runtime, target.id, target.position)
        }
    }

    fun pickupPlank(runtime: LumberRuntime, player: Player): Boolean {
        if (runtime.state.phase != LumberPhase.STACKING || player.uniqueId in carried) return false
        if (!access.hasAccess(player, runtime.settings.permission)) return false
        if (carried.values.count { it.zoneId == runtime.settings.id && it.sequence == runtime.state.sequence } >= remaining(runtime)) return false
        carried[player.uniqueId] = Carry(runtime.settings.id, runtime.state.sequence)
        effects.showCarried(runtime, player)
        return true
    }

    fun place(runtime: LumberRuntime, targetId: String, player: Player): Boolean {
        val carry = carried[player.uniqueId] ?: return false
        if (carry.zoneId != runtime.settings.id || carry.sequence != runtime.state.sequence) return false
        val objective = runtime.state.objective ?: return false
        val completed = ObjectiveTargetPool.complete(objective, targetId, player.uniqueId)
        if (!completed.accepted) return false
        carried.remove(player.uniqueId)
        effects.hideCarried(player.uniqueId)
        val result = LumberShiftEngine.stack(runtime.state.copy(objective = completed.state), runtime.rules(), player.uniqueId)
        transitions.apply(runtime, result, player)
        if (runtime.state.phase == LumberPhase.STACKING) reconcile(runtime) else cleanupZone(runtime.settings.id)
        return result.accepted
    }

    fun releasePlayer(playerId: UUID): Boolean {
        val removed = carried.remove(playerId) ?: return false
        effects.hideCarried(playerId)
        return removed.zoneId.isNotEmpty()
    }

    fun updateCarried() = carried.keys.toList().forEach { playerId ->
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !player.isOnline) releasePlayer(playerId) else effects.moveCarried(player)
    }

    fun identity(entity: Entity): LumberPalletIdentity? = effects.identity(entity)

    fun cleanup() = registry.snapshot().forEach { cleanupZone(it.settings.id) }

    private fun cleanupZone(zoneId: String) {
        effects.cleanupZone(zoneId)
        carried.entries.removeIf { it.value.zoneId == zoneId }
    }

    private fun remaining(runtime: LumberRuntime): Int =
        (runtime.rules().stackingQuota - runtime.state.stacked).coerceAtLeast(0)

    private fun slotCandidates(runtime: LumberRuntime): List<ObjectiveTargetCandidate> {
        val bounds = runtime.station.bounds
        val limit = runtime.rules().stackingQuota * 4
        val positions = mutableListOf<WorksitePosition>()
        var inspected = 0
        scan@ for (y in bounds.minY..bounds.maxY) {
            for (x in bounds.minX..bounds.maxX) for (z in bounds.minZ..bounds.maxZ) {
                if (++inspected > MAX_SLOT_SCAN_BLOCKS) break@scan
                val feet = runtime.station.world.getBlockAt(x, y, z)
                if (feet.isPassable && feet.getRelative(0, 1, 0).isPassable && feet.getRelative(0, -1, 0).type.isOccluding) {
                    positions += WorksitePosition(runtime.station.world.name, x, y, z)
                    if (positions.size >= limit) break@scan
                }
            }
        }
        return positions.mapIndexed { index, position ->
            ObjectiveTargetCandidate("pallet_${index + 1}", position, ObjectiveTargetRole("pallet"), index.toLong())
        }
    }

    private companion object {
        const val MAX_SLOT_SCAN_BLOCKS = 250_000
    }
}

internal class LumberStackingController(
    private val registry: LumberRuntimeRegistry,
    private val scene: LumberStackingScene,
    private val access: WorksiteAccessPort,
    private val audience: WorksiteAudiencePort,
) {
    fun onInteract(event: PlayerInteractEvent, clicked: Block, player: Player): Boolean {
        val runtime = registry.snapshot().firstOrNull { it.station.contains(clicked.location) } ?: return false
        if (runtime.state.phase != LumberPhase.STACKING || clicked.type.name != rackMaterial(runtime)) return false
        event.isCancelled = true
        if (!access.allowInteraction(
                "lumber-plank:${runtime.settings.id}:${player.uniqueId}", runtime.settings.plankInteractionCooldownMillis,
            )
        ) return true
        if (!scene.pickupPlank(runtime, player)) audience.sendActionBar(player, MessageKey.LUMBER_PLANK_UNAVAILABLE)
        return true
    }

    fun onInteractEntity(event: PlayerInteractEntityEvent): Boolean {
        val identity = scene.identity(event.rightClicked) ?: return false
        val runtime = registry.byId(identity.zoneId) ?: return false
        if (runtime.state.sequence != identity.sequence) return false
        event.isCancelled = true
        scene.place(runtime, identity.targetId, event.player)
        return true
    }

    fun onMove(to: Location, player: Player): Boolean {
        val carry = registry.snapshot().firstOrNull { runtime ->
            runtime.state.phase == LumberPhase.STACKING && !runtime.region.contains(to) && !runtime.station.contains(to)
        } ?: return false
        return scene.releasePlayer(player.uniqueId).also { if (it) audience.sendActionBar(player, MessageKey.LUMBER_PLANK_RETURNED) }
    }

    fun releasePlayer(player: Player, reason: WorksitePlayerReleaseReason) = scene.releasePlayer(player.uniqueId)
    fun updateVisuals() = scene.updateCarried()
    fun cleanup() = scene.cleanup()

    private fun rackMaterial(runtime: LumberRuntime): String = runtime.settings.stationMaterials.drop(2).firstOrNull()
        ?: runtime.settings.stationMaterials.first()
}

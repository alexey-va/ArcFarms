package ru.ruscrafting.farms.paper.mine.incident.entity

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import java.util.UUID

internal enum class MineIncidentEntityKind {
    CREATURE,
    CREATURE_NEST_DISPLAY,
    CREATURE_NEST_HITBOX,
    MINER,
    CAVE_IN_MARKER,
}

internal data class MineIncidentEntityIdentity(
    val kind: MineIncidentEntityKind,
    val zoneId: String,
    val sequence: Long,
    val targetId: String,
)

internal interface MineIncidentEntityEffects {
    fun spawn(runtime: MineRuntime, kind: MineIncidentEntityKind, targetId: String, position: WorksitePosition): UUID
    fun identity(entity: Entity): MineIncidentEntityIdentity?
    fun entity(id: UUID): Entity?
    fun remove(id: UUID)
    fun reconcileChunk(
        runtime: MineRuntime,
        chunk: Chunk,
        kind: MineIncidentEntityKind,
        expected: Map<String, WorksitePosition>,
    ): Map<String, UUID>
    fun cleanup(runtime: MineRuntime, kind: MineIncidentEntityKind)
}

/** PDC-owned entities; only chunk reconcile and cleanup scan loaded entities. */
internal class PaperMineIncidentEntityEffects(plugin: Plugin) : MineIncidentEntityEffects {
    private val markerKey = NamespacedKey(plugin, "mine_incident_entity")
    private val kindKey = NamespacedKey(plugin, "mine_incident_kind")
    private val zoneKey = NamespacedKey(plugin, "mine_incident_zone")
    private val sequenceKey = NamespacedKey(plugin, "mine_incident_sequence")
    private val targetKey = NamespacedKey(plugin, "mine_incident_target")

    override fun spawn(
        runtime: MineRuntime,
        kind: MineIncidentEntityKind,
        targetId: String,
        position: WorksitePosition,
    ): UUID {
        val world = requireNotNull(Bukkit.getWorld(position.world))
        val at = Location(
            world,
            position.x + 0.5,
            position.y + when (kind) {
                MineIncidentEntityKind.CREATURE -> 0.0
                MineIncidentEntityKind.CREATURE_NEST_DISPLAY -> 1.25
                MineIncidentEntityKind.CREATURE_NEST_HITBOX, MineIncidentEntityKind.MINER -> 1.0
                else -> 0.5
            },
            position.z + 0.5,
        )
        val entity = world.spawnEntity(
            at,
            when (kind) {
                MineIncidentEntityKind.CREATURE -> EntityType.HUSK
                MineIncidentEntityKind.CREATURE_NEST_DISPLAY -> EntityType.ITEM_DISPLAY
                MineIncidentEntityKind.CREATURE_NEST_HITBOX -> EntityType.ARMOR_STAND
                MineIncidentEntityKind.MINER -> EntityType.VILLAGER
                MineIncidentEntityKind.CAVE_IN_MARKER -> EntityType.ITEM_DISPLAY
            },
        )
        entity.persistentDataContainer.apply {
            set(markerKey, PersistentDataType.INTEGER, 1)
            set(kindKey, PersistentDataType.STRING, kind.name)
            set(zoneKey, PersistentDataType.STRING, runtime.settings.id)
            set(sequenceKey, PersistentDataType.LONG, runtime.state.sequence)
            set(targetKey, PersistentDataType.STRING, targetId)
        }
        entity.isPersistent = false
        (entity as? LivingEntity)?.removeWhenFarAway = false
        if (kind == MineIncidentEntityKind.CREATURE) entity.isGlowing = true
        (entity as? Villager)?.apply { setAI(false); isSilent = true }
        if (kind in setOf(MineIncidentEntityKind.CAVE_IN_MARKER, MineIncidentEntityKind.CREATURE_NEST_DISPLAY)) {
            (entity as ItemDisplay).apply {
            itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            setItemStack(ItemStack(if (kind == MineIncidentEntityKind.CAVE_IN_MARKER) Material.COBBLESTONE else Material.MANGROVE_ROOTS))
            viewRange = 2.5f
            isGlowing = true
            glowColorOverride = if (kind == MineIncidentEntityKind.CAVE_IN_MARKER) {
                org.bukkit.Color.fromRGB(0x8b, 0xd3, 0xff)
            } else {
                org.bukkit.Color.fromRGB(0xff, 0x7a, 0x45)
            }
            val scale = if (kind == MineIncidentEntityKind.CAVE_IN_MARKER) 1.35f else 1.5f
            transformation = transformation.also { it.scale.set(scale, scale, scale) }
            }
        }
        if (kind == MineIncidentEntityKind.CREATURE_NEST_HITBOX) (entity as ArmorStand).apply {
            isInvisible = true
            setGravity(false)
            isSmall = false
            isInvulnerable = false
        }
        return entity.uniqueId
    }

    override fun identity(entity: Entity): MineIncidentEntityIdentity? {
        val pdc = entity.persistentDataContainer
        if (pdc.get(markerKey, PersistentDataType.INTEGER) != 1) return null
        return runCatching {
            MineIncidentEntityIdentity(
                MineIncidentEntityKind.valueOf(requireNotNull(pdc.get(kindKey, PersistentDataType.STRING))),
                requireNotNull(pdc.get(zoneKey, PersistentDataType.STRING)),
                requireNotNull(pdc.get(sequenceKey, PersistentDataType.LONG)),
                requireNotNull(pdc.get(targetKey, PersistentDataType.STRING)),
            )
        }.getOrNull()
    }

    override fun entity(id: UUID): Entity? = Bukkit.getEntity(id)
    override fun remove(id: UUID) { Bukkit.getEntity(id)?.remove() }

    override fun reconcileChunk(
        runtime: MineRuntime,
        chunk: Chunk,
        kind: MineIncidentEntityKind,
        expected: Map<String, WorksitePosition>,
    ): Map<String, UUID> {
        if (chunk.world !== runtime.region.world) return emptyMap()
        val canonical = linkedMapOf<String, UUID>()
        chunk.entities.forEach { entity ->
            val identity = identity(entity) ?: return@forEach
            if (identity.zoneId != runtime.settings.id || identity.sequence != runtime.state.sequence || identity.kind != kind) return@forEach
            if (identity.targetId !in expected || canonical.putIfAbsent(identity.targetId, entity.uniqueId) != null) entity.remove()
        }
        expected.filterValues { position ->
            position.world == chunk.world.name && (position.x shr 4) == chunk.x && (position.z shr 4) == chunk.z
        }.forEach { (targetId, position) ->
            if (targetId !in canonical) canonical[targetId] = spawn(runtime, kind, targetId, position)
        }
        return canonical
    }

    override fun cleanup(runtime: MineRuntime, kind: MineIncidentEntityKind) {
        runtime.region.world.loadedChunks.forEach { chunk ->
            chunk.entities.filter { entity ->
                identity(entity)?.let { it.zoneId == runtime.settings.id && it.kind == kind } == true
            }.forEach(Entity::remove)
        }
    }
}

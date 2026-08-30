package ru.ruscrafting.farms.paper.mine.incident.entity

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Villager
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import java.util.UUID

internal enum class MineIncidentEntityKind { CREATURE, MINER }

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
        val entity = world.spawnEntity(
            Location(world, position.x + 0.5, position.y + 1.0, position.z + 0.5),
            if (kind == MineIncidentEntityKind.CREATURE) EntityType.SILVERFISH else EntityType.VILLAGER,
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
        (entity as? Villager)?.apply { setAI(false); isSilent = true }
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

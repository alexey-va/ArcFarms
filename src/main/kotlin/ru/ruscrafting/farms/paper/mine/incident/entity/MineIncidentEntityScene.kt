package ru.ruscrafting.farms.paper.mine.incident.entity

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
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
    MINER_CAMP_LANTERN,
    MINER_CAMP_SUPPLIES,
    CAVE_IN_MARKER,
    GAS_MARKER,
    CRYSTAL_MARKER,
    FLOOD_MARKER,
    POWER_MARKER,
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
        val offset = if (kind in OBJECTIVE_MARKER_KINDS) {
            objectiveMarkerOffset(world.getBlockAt(position.x, position.y, position.z))
        } else entityOffset(kind)
        val at = Location(
            world,
            position.x + offset.first,
            position.y + offset.second,
            position.z + offset.third,
        )
        val entity = world.spawnEntity(
            at,
            when (kind) {
                MineIncidentEntityKind.CREATURE -> EntityType.HUSK
                MineIncidentEntityKind.CREATURE_NEST_DISPLAY -> EntityType.ITEM_DISPLAY
                MineIncidentEntityKind.CREATURE_NEST_HITBOX -> EntityType.ARMOR_STAND
                MineIncidentEntityKind.MINER -> EntityType.VILLAGER
                MineIncidentEntityKind.CAVE_IN_MARKER,
                MineIncidentEntityKind.MINER_CAMP_LANTERN,
                MineIncidentEntityKind.MINER_CAMP_SUPPLIES,
                MineIncidentEntityKind.GAS_MARKER,
                MineIncidentEntityKind.CRYSTAL_MARKER,
                MineIncidentEntityKind.FLOOD_MARKER,
                MineIncidentEntityKind.POWER_MARKER -> EntityType.ITEM_DISPLAY
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
        if (kind == MineIncidentEntityKind.CREATURE || kind == MineIncidentEntityKind.MINER) entity.isGlowing = true
        (entity as? Villager)?.apply { setAI(false); isSilent = true }
        if (kind in DISPLAY_KINDS) {
            (entity as ItemDisplay).apply {
            itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            setItemStack(ItemStack(markerMaterial(kind)))
            viewRange = if (kind in OBJECTIVE_MARKER_KINDS) 12.0f else 4.0f
            isGlowing = true
            glowColorOverride = markerColor(kind)
            val scale = when (kind) {
                MineIncidentEntityKind.CAVE_IN_MARKER -> 1.35f
                MineIncidentEntityKind.CREATURE_NEST_DISPLAY -> 1.75f
                else -> 1.9f
            }
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

    private fun markerMaterial(kind: MineIncidentEntityKind): Material = when (kind) {
        MineIncidentEntityKind.CAVE_IN_MARKER -> Material.COBBLESTONE
        MineIncidentEntityKind.CREATURE_NEST_DISPLAY -> Material.MANGROVE_ROOTS
        MineIncidentEntityKind.MINER_CAMP_LANTERN -> Material.SOUL_LANTERN
        MineIncidentEntityKind.MINER_CAMP_SUPPLIES -> Material.BARREL
        MineIncidentEntityKind.GAS_MARKER -> Material.SLIME_BALL
        MineIncidentEntityKind.CRYSTAL_MARKER -> Material.AMETHYST_SHARD
        MineIncidentEntityKind.FLOOD_MARKER -> Material.HEART_OF_THE_SEA
        MineIncidentEntityKind.POWER_MARKER -> Material.REDSTONE_TORCH
        else -> error("Entity kind $kind is not a display marker")
    }

    private fun markerColor(kind: MineIncidentEntityKind): org.bukkit.Color = when (kind) {
        MineIncidentEntityKind.CAVE_IN_MARKER -> org.bukkit.Color.fromRGB(0x8b, 0xd3, 0xff)
        MineIncidentEntityKind.CREATURE_NEST_DISPLAY -> org.bukkit.Color.fromRGB(0xff, 0x7a, 0x45)
        MineIncidentEntityKind.MINER_CAMP_LANTERN -> org.bukkit.Color.fromRGB(0x75, 0xd8, 0xff)
        MineIncidentEntityKind.MINER_CAMP_SUPPLIES -> org.bukkit.Color.fromRGB(0xff, 0xb3, 0x55)
        MineIncidentEntityKind.GAS_MARKER -> org.bukkit.Color.fromRGB(0x75, 0xff, 0x70)
        MineIncidentEntityKind.CRYSTAL_MARKER -> org.bukkit.Color.fromRGB(0xd5, 0x68, 0xff)
        MineIncidentEntityKind.FLOOD_MARKER -> org.bukkit.Color.fromRGB(0x45, 0xc8, 0xf5)
        MineIncidentEntityKind.POWER_MARKER -> org.bukkit.Color.fromRGB(0xff, 0xd6, 0x48)
        else -> error("Entity kind $kind is not a display marker")
    }

    private companion object {
        val OBJECTIVE_MARKER_KINDS = setOf(
            MineIncidentEntityKind.GAS_MARKER,
            MineIncidentEntityKind.CRYSTAL_MARKER,
            MineIncidentEntityKind.FLOOD_MARKER,
            MineIncidentEntityKind.POWER_MARKER,
        )
        val DISPLAY_KINDS = OBJECTIVE_MARKER_KINDS + setOf(
            MineIncidentEntityKind.CAVE_IN_MARKER,
            MineIncidentEntityKind.CREATURE_NEST_DISPLAY,
            MineIncidentEntityKind.MINER_CAMP_LANTERN,
            MineIncidentEntityKind.MINER_CAMP_SUPPLIES,
        )
    }

    private fun entityOffset(kind: MineIncidentEntityKind): Triple<Double, Double, Double> = when (kind) {
        MineIncidentEntityKind.CREATURE -> Triple(0.5, 0.0, 0.5)
        MineIncidentEntityKind.CREATURE_NEST_DISPLAY -> Triple(0.5, 1.25, 0.5)
        MineIncidentEntityKind.CREATURE_NEST_HITBOX, MineIncidentEntityKind.MINER -> Triple(0.5, 1.0, 0.5)
        MineIncidentEntityKind.CAVE_IN_MARKER -> Triple(0.5, 0.5, 0.5)
        MineIncidentEntityKind.MINER_CAMP_LANTERN -> Triple(1.15, 0.75, 0.35)
        MineIncidentEntityKind.MINER_CAMP_SUPPLIES -> Triple(-0.15, 0.65, 0.85)
        else -> Triple(0.5, 1.55, 0.5)
    }

    private fun objectiveMarkerOffset(block: org.bukkit.block.Block): Triple<Double, Double, Double> = when {
        block.getRelative(BlockFace.UP).type.isAir -> Triple(0.5, 1.35, 0.5)
        block.getRelative(BlockFace.NORTH).type.isAir -> Triple(0.5, 0.5, -0.15)
        block.getRelative(BlockFace.SOUTH).type.isAir -> Triple(0.5, 0.5, 1.15)
        block.getRelative(BlockFace.WEST).type.isAir -> Triple(-0.15, 0.5, 0.5)
        block.getRelative(BlockFace.EAST).type.isAir -> Triple(1.15, 0.5, 0.5)
        else -> Triple(0.5, 1.35, 0.5)
    }
}

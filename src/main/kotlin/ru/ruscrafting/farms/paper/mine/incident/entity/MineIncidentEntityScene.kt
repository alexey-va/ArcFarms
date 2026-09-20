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
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Interaction
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.farms.paper.worksite.WorksiteBlockGlow
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import java.util.UUID

internal enum class MineIncidentEntityKind {
    CREATURE,
    RESCUE_CREATURE,
    CREATURE_NEST_DISPLAY,
    CREATURE_NEST_HITBOX,
    MINER,
    MINER_CAMP_LANTERN,
    MINER_CAMP_SUPPLIES,
    MINER_MAZE_ENTRANCE,
    MINER_MAZE_ENTRANCE_HITBOX,
    CAVE_IN_MARKER,
    GAS_MARKER,
    GAS_MARKER_HITBOX,
    CRYSTAL_MARKER,
    CRYSTAL_MARKER_HITBOX,
    FLOOD_MARKER,
    FLOOD_MARKER_HITBOX,
    POWER_MARKER,
    POWER_MARKER_HITBOX,
}

internal val MineIncidentEntityKind.objectiveMarkerKind: MineIncidentEntityKind?
    get() = when (this) {
        MineIncidentEntityKind.GAS_MARKER_HITBOX -> MineIncidentEntityKind.GAS_MARKER
        MineIncidentEntityKind.CRYSTAL_MARKER_HITBOX -> MineIncidentEntityKind.CRYSTAL_MARKER
        MineIncidentEntityKind.FLOOD_MARKER_HITBOX -> MineIncidentEntityKind.FLOOD_MARKER
        MineIncidentEntityKind.POWER_MARKER_HITBOX -> MineIncidentEntityKind.POWER_MARKER
        else -> null
    }

internal val MineIncidentEntityKind.objectiveMarkerHitboxKind: MineIncidentEntityKind?
    get() = when (this) {
        MineIncidentEntityKind.GAS_MARKER -> MineIncidentEntityKind.GAS_MARKER_HITBOX
        MineIncidentEntityKind.CRYSTAL_MARKER -> MineIncidentEntityKind.CRYSTAL_MARKER_HITBOX
        MineIncidentEntityKind.FLOOD_MARKER -> MineIncidentEntityKind.FLOOD_MARKER_HITBOX
        MineIncidentEntityKind.POWER_MARKER -> MineIncidentEntityKind.POWER_MARKER_HITBOX
        else -> null
    }

internal val MineIncidentEntityKind.isObjectiveMarkerHitbox: Boolean
    get() = objectiveMarkerKind != null

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
    private val roaming = hashMapOf<String, UUID>()
    private fun roamingKey(runtime: MineRuntime, target: String) = "${runtime.settings.id}:${runtime.state.sequence}:$target"
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
        val offset = if (kind in ADJACENT_ENTITY_KINDS) {
            val placement = requireNotNull(objectiveMarkerPlacement(world.getBlockAt(position.x, position.y, position.z))) {
                "No open adjacent cell for mine objective marker target=$targetId position=$position"
            }
            if (kind in INTERACTION_HITBOX_KINDS) placement.hitbox else placement.display
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
                MineIncidentEntityKind.CREATURE, MineIncidentEntityKind.RESCUE_CREATURE -> EntityType.HUSK
                MineIncidentEntityKind.CREATURE_NEST_DISPLAY -> EntityType.ITEM_DISPLAY
                MineIncidentEntityKind.CREATURE_NEST_HITBOX -> EntityType.ARMOR_STAND
                MineIncidentEntityKind.MINER -> EntityType.VILLAGER
                MineIncidentEntityKind.MINER_CAMP_LANTERN,
                MineIncidentEntityKind.MINER_CAMP_SUPPLIES -> EntityType.ITEM_DISPLAY
                MineIncidentEntityKind.MINER_MAZE_ENTRANCE -> EntityType.BLOCK_DISPLAY
                MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX -> EntityType.INTERACTION
                MineIncidentEntityKind.CAVE_IN_MARKER -> EntityType.BLOCK_DISPLAY
                MineIncidentEntityKind.GAS_MARKER,
                MineIncidentEntityKind.CRYSTAL_MARKER,
                MineIncidentEntityKind.FLOOD_MARKER,
                MineIncidentEntityKind.POWER_MARKER -> EntityType.BLOCK_DISPLAY
                MineIncidentEntityKind.GAS_MARKER_HITBOX,
                MineIncidentEntityKind.CRYSTAL_MARKER_HITBOX,
                MineIncidentEntityKind.FLOOD_MARKER_HITBOX,
                MineIncidentEntityKind.POWER_MARKER_HITBOX -> EntityType.INTERACTION
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
        if (kind == MineIncidentEntityKind.CREATURE || kind == MineIncidentEntityKind.RESCUE_CREATURE || kind == MineIncidentEntityKind.MINER) entity.isGlowing = true
        (entity as? Villager)?.apply { setAI(false); isSilent = true; isInvulnerable = true }
        if (kind in BLOCK_DISPLAY_KINDS) {
            (entity as BlockDisplay).apply {
                block = markerBlockMaterial(kind).createBlockData()
                viewRange = if (kind in OBJECTIVE_MARKER_KINDS || kind == MineIncidentEntityKind.MINER_MAZE_ENTRANCE) 12.0f else 6.0f
                brightness = Display.Brightness(15, 15)
                isGlowing = true
                glowColorOverride = markerColor(kind)
                if (kind == MineIncidentEntityKind.FLOOD_MARKER) {
                    block = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData()
                    transformation = transformation.also {
                        it.translation.set(0f, 0.9f, 0f)
                        it.scale.set(1f, 0.015f, 1f)
                    }
                } else if (kind in ACTUAL_BLOCK_GLOW_KINDS) {
                    WorksiteBlockGlow.apply(this, world.getBlockAt(position.x, position.y, position.z).blockData, markerColor(kind))
                } else {
                    transformation = transformation.also {
                        it.translation.set(MARKER_INSET, MARKER_INSET, MARKER_INSET)
                        it.scale.set(MARKER_SCALE)
                    }
                }
            }
        } else if (kind in DISPLAY_KINDS) {
            (entity as ItemDisplay).apply {
                itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                setItemStack(ItemStack(markerMaterial(kind)))
                viewRange = 4.0f
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
        if (kind in INTERACTION_HITBOX_KINDS) (entity as Interaction).apply {
            interactionWidth = OBJECTIVE_HITBOX_WIDTH
            interactionHeight = OBJECTIVE_HITBOX_HEIGHT
            isResponsive = true
        }
        if (kind == MineIncidentEntityKind.CREATURE_NEST_HITBOX) (entity as ArmorStand).apply {
            isInvisible = true
            setGravity(false)
            isSmall = false
            isInvulnerable = false
        }
        if (kind == MineIncidentEntityKind.CREATURE) roaming[roamingKey(runtime, targetId)] = entity.uniqueId
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
    override fun remove(id: UUID) { roaming.values.removeIf { it == id }; Bukkit.getEntity(id)?.remove() }

    override fun reconcileChunk(
        runtime: MineRuntime,
        chunk: Chunk,
        kind: MineIncidentEntityKind,
        expected: Map<String, WorksitePosition>,
    ): Map<String, UUID> {
        if (chunk.world !== runtime.region.world) return emptyMap()
        val canonical = linkedMapOf<String, UUID>()
        if (kind == MineIncidentEntityKind.CREATURE) expected.keys.forEach { target ->
            roaming[roamingKey(runtime, target)]?.let { id ->
                Bukkit.getEntity(id)?.takeIf { it.isValid && !it.isDead }?.let { canonical[target] = id }
            }
        }
        chunk.entities.forEach { entity ->
            val identity = identity(entity) ?: return@forEach
            if (identity.zoneId != runtime.settings.id || identity.sequence != runtime.state.sequence || identity.kind != kind) return@forEach
            val expectedLocation = expected[identity.targetId]?.let { position ->
                when {
                    kind in ADJACENT_ENTITY_KINDS -> objectiveMarkerLocation(kind, position)
                    else -> entityLocation(position, kind)
                }
            }
            val belongsToChunk = expectedLocation?.world?.name == chunk.world.name &&
                (expectedLocation.blockX shr 4) == chunk.x && (expectedLocation.blockZ shr 4) == chunk.z
            val remainsOpen = kind !in ADJACENT_ENTITY_KINDS || entity.location.block.isPassable
            val sameCreature = kind == MineIncidentEntityKind.CREATURE && expectedLocation != null &&
                runtime.region.contains(entity.location) && kotlin.math.abs(entity.location.y - expectedLocation.y) <= 2.0
            val existing = canonical[identity.targetId]
            if ((!belongsToChunk && !sameCreature) || !remainsOpen || identity.targetId !in expected ||
                existing != null && existing != entity.uniqueId
            ) remove(entity.uniqueId)
            else {
                canonical[identity.targetId] = entity.uniqueId
                if (kind == MineIncidentEntityKind.CREATURE) roaming[roamingKey(runtime, identity.targetId)] = entity.uniqueId
            }
        }
        val expectedInChunk = expected.filter { (targetId, position) ->
            val spawnLocation = when {
                kind in ADJACENT_ENTITY_KINDS -> objectiveMarkerLocation(kind, position)
                else -> entityLocation(position, kind)
            }
            spawnLocation?.world?.name == chunk.world.name &&
                (spawnLocation.blockX shr 4) == chunk.x && (spawnLocation.blockZ shr 4) == chunk.z
        }
        expectedInChunk.forEach { (targetId, position) ->
            if (targetId !in canonical) canonical[targetId] = spawn(runtime, kind, targetId, position)
        }
        return canonical
    }

    override fun cleanup(runtime: MineRuntime, kind: MineIncidentEntityKind) {
        runtime.region.world.loadedChunks.forEach { chunk ->
            chunk.entities.filter { entity ->
                identity(entity)?.let { it.zoneId == runtime.settings.id && it.kind == kind } == true
            }.forEach { remove(it.uniqueId) }
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

    private fun markerBlockMaterial(kind: MineIncidentEntityKind): Material = when (kind) {
        MineIncidentEntityKind.CAVE_IN_MARKER -> Material.BLUE_STAINED_GLASS
        MineIncidentEntityKind.GAS_MARKER -> Material.SLIME_BLOCK
        MineIncidentEntityKind.CRYSTAL_MARKER -> Material.AMETHYST_BLOCK
        MineIncidentEntityKind.FLOOD_MARKER -> Material.SEA_LANTERN
        MineIncidentEntityKind.POWER_MARKER -> Material.REDSTONE_BLOCK
        MineIncidentEntityKind.MINER_MAZE_ENTRANCE -> Material.OCHRE_FROGLIGHT
        else -> error("Entity kind $kind is not a block display marker")
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
        MineIncidentEntityKind.MINER_MAZE_ENTRANCE -> org.bukkit.Color.fromRGB(0xff, 0xb8, 0x45)
        else -> error("Entity kind $kind is not a display marker")
    }

    private companion object {
        val ACTUAL_BLOCK_GLOW_KINDS = setOf(
            MineIncidentEntityKind.CAVE_IN_MARKER, MineIncidentEntityKind.CRYSTAL_MARKER, MineIncidentEntityKind.GAS_MARKER,
        )
        val OBJECTIVE_MARKER_KINDS = setOf(
            MineIncidentEntityKind.GAS_MARKER,
            MineIncidentEntityKind.CRYSTAL_MARKER,
            MineIncidentEntityKind.FLOOD_MARKER,
            MineIncidentEntityKind.POWER_MARKER,
        )
        val OBJECTIVE_MARKER_HITBOX_KINDS = setOf(
            MineIncidentEntityKind.GAS_MARKER_HITBOX,
            MineIncidentEntityKind.CRYSTAL_MARKER_HITBOX,
            MineIncidentEntityKind.FLOOD_MARKER_HITBOX,
            MineIncidentEntityKind.POWER_MARKER_HITBOX,
        )
        val INTERACTION_HITBOX_KINDS = OBJECTIVE_MARKER_HITBOX_KINDS + MineIncidentEntityKind.MINER_MAZE_ENTRANCE_HITBOX
        val ADJACENT_ENTITY_KINDS = (OBJECTIVE_MARKER_KINDS - ACTUAL_BLOCK_GLOW_KINDS - MineIncidentEntityKind.FLOOD_MARKER) + INTERACTION_HITBOX_KINDS + MineIncidentEntityKind.MINER_MAZE_ENTRANCE
        val BLOCK_DISPLAY_KINDS = OBJECTIVE_MARKER_KINDS + setOf(
            MineIncidentEntityKind.CAVE_IN_MARKER,
            MineIncidentEntityKind.MINER_MAZE_ENTRANCE,
        )
        val DISPLAY_KINDS = setOf(
            MineIncidentEntityKind.CREATURE_NEST_DISPLAY,
            MineIncidentEntityKind.MINER_CAMP_LANTERN,
            MineIncidentEntityKind.MINER_CAMP_SUPPLIES,
        )
        const val OBJECTIVE_HITBOX_WIDTH = 0.8f
        const val OBJECTIVE_HITBOX_HEIGHT = 1.0f
        const val MARKER_INSET = 0.12f
        const val MARKER_SCALE = 0.76f
    }

    private fun entityOffset(kind: MineIncidentEntityKind): Triple<Double, Double, Double> = when (kind) {
        MineIncidentEntityKind.CREATURE, MineIncidentEntityKind.RESCUE_CREATURE -> Triple(0.5, 0.0, 0.5)
        MineIncidentEntityKind.CREATURE_NEST_DISPLAY -> Triple(0.5, 1.25, 0.5)
        MineIncidentEntityKind.CREATURE_NEST_HITBOX, MineIncidentEntityKind.MINER -> Triple(0.5, 1.0, 0.5)
        MineIncidentEntityKind.CAVE_IN_MARKER, MineIncidentEntityKind.CRYSTAL_MARKER,
        MineIncidentEntityKind.GAS_MARKER, MineIncidentEntityKind.FLOOD_MARKER -> Triple(0.0, 0.0, 0.0)
        MineIncidentEntityKind.MINER_CAMP_LANTERN -> Triple(1.15, 0.75, 0.35)
        MineIncidentEntityKind.MINER_CAMP_SUPPLIES -> Triple(-0.15, 0.65, 0.85)
        else -> Triple(0.5, 1.55, 0.5)
    }

    private fun entityLocation(position: WorksitePosition, kind: MineIncidentEntityKind): Location? {
        val world = Bukkit.getWorld(position.world) ?: return null
        val offset = entityOffset(kind)
        return Location(world, position.x + offset.first, position.y + offset.second, position.z + offset.third)
    }

    private data class ObjectiveMarkerPlacement(
        val display: Triple<Double, Double, Double>,
        val hitbox: Triple<Double, Double, Double>,
    )

    private fun objectiveMarkerPlacement(block: org.bukkit.block.Block): ObjectiveMarkerPlacement? = when {
        block.isOpenRelative(BlockFace.UP) -> ObjectiveMarkerPlacement(
            display = Triple(0.0, 1.0, 0.0), hitbox = Triple(0.5, 1.0, 0.5),
        )
        block.isOpenRelative(BlockFace.NORTH) -> ObjectiveMarkerPlacement(
            display = Triple(0.0, 0.0, -1.0), hitbox = Triple(0.5, 0.0, -0.5),
        )
        block.isOpenRelative(BlockFace.SOUTH) -> ObjectiveMarkerPlacement(
            display = Triple(0.0, 0.0, 1.0), hitbox = Triple(0.5, 0.0, 1.5),
        )
        block.isOpenRelative(BlockFace.WEST) -> ObjectiveMarkerPlacement(
            display = Triple(-1.0, 0.0, 0.0), hitbox = Triple(-0.5, 0.0, 0.5),
        )
        block.isOpenRelative(BlockFace.EAST) -> ObjectiveMarkerPlacement(
            display = Triple(1.0, 0.0, 0.0), hitbox = Triple(1.5, 0.0, 0.5),
        )
        else -> null
    }

    private fun org.bukkit.block.Block.isOpenRelative(face: BlockFace): Boolean {
        val blockX = this.x + face.modX
        val blockY = this.y + face.modY
        val blockZ = this.z + face.modZ
        if (blockY !in world.minHeight until world.maxHeight || !world.isChunkLoaded(blockX shr 4, blockZ shr 4)) return false
        return getRelative(face).isPassable
    }

    private fun objectiveMarkerLocation(kind: MineIncidentEntityKind, position: WorksitePosition): Location? {
        val world = Bukkit.getWorld(position.world) ?: return null
        val placement = objectiveMarkerPlacement(world.getBlockAt(position.x, position.y, position.z)) ?: return null
        val offset = if (kind in INTERACTION_HITBOX_KINDS) placement.hitbox else placement.display
        return Location(world, position.x + offset.first, position.y + offset.second, position.z + offset.third)
    }
}

/** Candidate discovery guard shared by crystal incidents and marker reconciliation. */
internal fun hasMineObjectiveMarkerSpace(position: WorksitePosition): Boolean {
    val world = Bukkit.getWorld(position.world) ?: return false
    if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return false
    val block = world.getBlockAt(position.x, position.y, position.z)
    return listOf(BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST).any { face ->
        val x = position.x + face.modX
        val y = position.y + face.modY
        val z = position.z + face.modZ
        y in world.minHeight until world.maxHeight && world.isChunkLoaded(x shr 4, z shr 4) &&
            block.getRelative(face).isPassable
    }
}

/** Unloaded neighbour chunks are unknown, not proof that a persisted objective became impossible. */
internal fun isMineObjectiveMarkerBlocked(position: WorksitePosition): Boolean {
    val world = Bukkit.getWorld(position.world) ?: return false
    if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return false
    val block = world.getBlockAt(position.x, position.y, position.z)
    var unknown = false
    listOf(BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST).forEach { face ->
        val x = position.x + face.modX
        val y = position.y + face.modY
        val z = position.z + face.modZ
        if (y !in world.minHeight until world.maxHeight) return@forEach
        if (!world.isChunkLoaded(x shr 4, z shr 4)) {
            unknown = true
        } else if (block.getRelative(face).isPassable) {
            return false
        }
    }
    return !unknown
}

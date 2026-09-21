package ru.ruscrafting.farms.paper.mine.index

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.worksite.WorksiteChunkPayload
import java.util.logging.Level

internal data class MineIndexDefinition(
    val zoneId: String,
    val region: ActivityRegion,
    val mineable: Set<Material>,
    val railMaterials: Set<Material> = emptySet(),
    val maxBlocks: Int = 20_000_000,
) {
    init {
        require(zoneId.matches(Regex("[a-z0-9_-]{1,48}")))
        require(mineable.isNotEmpty())
        require(maxBlocks in 1..20_000_000 && region.bounds.volume <= maxBlocks)
    }
}

internal data class MineIndexedTarget(
    val position: WorksitePosition,
    val roles: Set<MineAnchorRole>,
) {
    init { require(roles.isNotEmpty()) }
}

/** Chunk-local packed cache. Gameplay lookups never load chunks or scan the region. */
internal class MineBlockIndex(private val plugin: Plugin, private val lift: ru.ruscrafting.farms.paper.mine.lift.MineLiftAccess? = null) {
    private data class ChunkKey(val world: String, val x: Int, val z: Int)
    private data class DirtyChunk(val zoneId: String, val chunk: ChunkKey)
    private val targetsByZone = mutableMapOf<String, MutableMap<ChunkKey, MutableMap<Int, Int>>>()
    private val dirtyChunks = linkedSetOf<DirtyChunk>()

    fun allowsEvent(position: WorksitePosition): Boolean {
        val world = Bukkit.getWorld(position.world) ?: return false
        return lift?.excludesEvent(org.bukkit.Location(world, position.x + .5, position.y + 1.0, position.z + .5)) != true
    }

    fun targets(zoneId: String, role: MineAnchorRole): Set<WorksitePosition> = collectTargets(zoneId, role, false)

    fun loadedTargets(zoneId: String, role: MineAnchorRole): Set<WorksitePosition> = collectTargets(zoneId, role, true)

    /** Restricts hot guidance queries to indexed chunks around the player. Never loads terrain. */
    fun nearbyTargets(zoneId: String, role: MineAnchorRole, at: org.bukkit.Location, radius: Int): List<WorksitePosition> {
        require(radius in 1..32)
        val chunks = targetsByZone[zoneId] ?: return emptyList()
        val mask = 1 shl role.ordinal
        return buildList {
            for (x in ((at.blockX - radius) shr 4)..((at.blockX + radius) shr 4)) {
                for (z in ((at.blockZ - radius) shr 4)..((at.blockZ + radius) shr 4)) {
                    if (!at.world.isChunkLoaded(x, z)) continue
                    val key = ChunkKey(at.world.name, x, z)
                    chunks[key]?.forEach { (packed, roles) ->
                        if (roles and mask != 0) {
                            val point = position(key, packed)
                            if (kotlin.math.abs(point.y - at.blockY) <= 4 &&
                                (point.x + 0.5 - at.x) * (point.x + 0.5 - at.x) +
                                (point.z + 0.5 - at.z) * (point.z + 0.5 - at.z) <= radius * radius) add(point)
                        }
                    }
                }
            }
        }
    }

    private fun collectTargets(zoneId: String, role: MineAnchorRole, loadedOnly: Boolean): Set<WorksitePosition> = buildSet {
        val mask = 1 shl role.ordinal
        targetsByZone[zoneId]?.forEach { (chunk, entries) ->
            if (loadedOnly && Bukkit.getWorld(chunk.world)?.isChunkLoaded(chunk.x, chunk.z) != true) return@forEach
            entries.forEach { (packed, roles) -> if (roles and mask != 0) {
                val p = position(chunk, packed)
                if (role in setOf(MineAnchorRole.PROSPECT, MineAnchorRole.MINEABLE) || allowsEvent(p)) add(p)
            } }
        }
    }

    fun contains(zoneId: String, block: org.bukkit.block.Block, role: MineAnchorRole): Boolean {
        val roles = targetsByZone[zoneId]?.get(ChunkKey(block.world.name, block.x shr 4, block.z shr 4))
            ?.get(packPosition(block.x, block.y, block.z)) ?: return false
        return roles and (1 shl role.ordinal) != 0
    }

    fun isLiveTarget(
        zoneId: String,
        position: WorksitePosition,
        role: MineAnchorRole,
        railMaterials: Set<Material> = emptySet(),
    ): Boolean {
        if (role !in setOf(MineAnchorRole.PROSPECT, MineAnchorRole.MINEABLE) && !allowsEvent(position)) return false
        val world = Bukkit.getWorld(position.world) ?: return false
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return false
        val block = world.getBlockAt(position.x, position.y, position.z)
        return contains(zoneId, block, role) && role in MineAnchorClassifier.classify(block, emptySet(), railMaterials)
    }

    fun reconcileChunk(definition: MineIndexDefinition, chunk: Chunk) {
        if (chunk.world !== definition.region.world) return
        val bounds = definition.region.bounds
        if (chunk.x !in (bounds.minX shr 4)..(bounds.maxX shr 4) ||
            chunk.z !in (bounds.minZ shr 4)..(bounds.maxZ shr 4)) return
        val chunkKey = ChunkKey(chunk.world.name, chunk.x, chunk.z)
        // Pending local changes take precedence over an older PDC snapshot.
        if (DirtyChunk(definition.zoneId, chunkKey) in dirtyChunks) return
        val storageKey = key(definition.zoneId)
        val container = chunk.persistentDataContainer
        val legacy = container.has(storageKey, PersistentDataType.STRING)
        val bytes = WorksiteChunkPayload.read(container, storageKey)
        val decoded = runCatching {
            bytes?.let { MineIndexCodec.decode(chunk.world.name, chunk.x, chunk.z, it) }.orEmpty()
        }.getOrElse { failure ->
            targetsByZone[definition.zoneId]?.remove(chunkKey)
            plugin.logger.log(Level.WARNING, "Invalid mine index zone=${definition.zoneId} chunk=${chunk.x},${chunk.z}; retained for repair", failure)
            return
        }
        val entries = linkedMapOf<Int, Int>()
        decoded.forEach { target ->
            val p = target.position
            if ((p.x shr 4) != chunk.x || (p.z shr 4) != chunk.z ||
                p.y !in chunk.world.minHeight until chunk.world.maxHeight) return@forEach
            val block = chunk.getBlock(p.x and 15, p.y, p.z and 15)
            if (!definition.region.contains(block.location)) return@forEach
            val roles = reconciledRoles(definition, block, target.roles)
            if (roles.isNotEmpty()) entries[packPosition(p.x, p.y, p.z)] = roleMask(roles)
        }
        targetsByZone.getOrPut(definition.zoneId, ::linkedMapOf)[chunkKey] = entries
        if (legacy || bytes != null && !MineIndexCodec.isCompact(bytes)) {
            dirtyChunks += DirtyChunk(definition.zoneId, chunkKey)
        }
    }

    internal fun replaceZone(
        definition: MineIndexDefinition,
        chunks: Collection<Chunk>,
        targets: Collection<MineIndexedTarget>,
    ) {
        require(targets.size <= MAX_TARGETS_PER_ZONE)
        val replacement = linkedMapOf<ChunkKey, MutableMap<Int, Int>>()
        chunks.forEach { replacement[ChunkKey(it.world.name, it.x, it.z)] = linkedMapOf() }
        targets.forEach { target ->
            val p = target.position
            val chunk = ChunkKey(p.world, p.x shr 4, p.z shr 4)
            replacement.getOrPut(chunk, ::linkedMapOf)[packPosition(p.x, p.y, p.z)] = roleMask(target.roles)
        }
        targetsByZone[definition.zoneId] = replacement
        dirtyChunks.removeIf { it.zoneId == definition.zoneId }
        replacement.keys.forEach { dirtyChunks += DirtyChunk(definition.zoneId, it) }
    }

    fun refreshBlock(definition: MineIndexDefinition, block: org.bukkit.block.Block) {
        if (!definition.region.contains(block.location)) return
        val chunkKey = ChunkKey(block.world.name, block.x shr 4, block.z shr 4)
        val entries = targetsByZone.getOrPut(definition.zoneId, ::linkedMapOf).getOrPut(chunkKey, ::linkedMapOf)
        val packed = packPosition(block.x, block.y, block.z)
        val mask = roleMask(reconciledRoles(definition, block, rolesByMask[entries.getOrDefault(packed, 0)]))
        if (entries.getOrDefault(packed, 0) == mask) return
        if (mask == 0) entries.remove(packed) else entries[packed] = mask
        dirtyChunks += DirtyChunk(definition.zoneId, chunkKey)
    }

    /** Coalesces all changes to a chunk into one PDC update; the normal tick writes at most one. */
    fun flushDirty(chunkBudget: Int = 1): Int {
        require(chunkBudget > 0)
        var written = 0
        val iterator = dirtyChunks.iterator()
        while (iterator.hasNext() && written < chunkBudget) {
            val dirty = iterator.next()
            val world = Bukkit.getWorld(dirty.chunk.world) ?: continue
            if (!world.isChunkLoaded(dirty.chunk.x, dirty.chunk.z)) continue
            writeChunk(dirty, world.getChunkAt(dirty.chunk.x, dirty.chunk.z))
            iterator.remove()
            written++
        }
        return written
    }

    /** Called before Paper saves/unloads the chunk. Never loads a different chunk. */
    fun flushChunk(chunk: Chunk) {
        val chunkKey = ChunkKey(chunk.world.name, chunk.x, chunk.z)
        val iterator = dirtyChunks.iterator()
        while (iterator.hasNext()) {
            val dirty = iterator.next()
            if (dirty.chunk != chunkKey) continue
            writeChunk(dirty, chunk)
            iterator.remove()
        }
    }

    fun clear() {
        flushDirty(Int.MAX_VALUE)
        targetsByZone.clear()
        dirtyChunks.clear()
    }

    private fun writeChunk(dirty: DirtyChunk, chunk: Chunk) {
        val entries = targetsByZone[dirty.zoneId]?.get(dirty.chunk).orEmpty()
        val targets = entries.map { (packed, mask) -> MineIndexedTarget(position(dirty.chunk, packed), rolesByMask[mask]) }
        val storageKey = key(dirty.zoneId)
        if (targets.isEmpty()) chunk.persistentDataContainer.remove(storageKey)
        else WorksiteChunkPayload.write(chunk.persistentDataContainer, storageKey,
            MineIndexCodec.encode(chunk.x, chunk.z, targets))
    }

    private fun reconciledRoles(
        definition: MineIndexDefinition,
        block: org.bukkit.block.Block,
        previous: Set<MineAnchorRole>,
    ): Set<MineAnchorRole> {
        val current = MineAnchorClassifier.classify(block, definition.mineable, definition.railMaterials)
        if (!block.type.isSolid || !MineAnchorClassifier.hasUnloadedHorizontalNeighbor(block)) return current
        // Keep only previously verified boundary anchors while a neighbour is unavailable.
        // Gameplay's isLiveTarget still validates actual exposure before any action.
        return current + previous.filter { role ->
            role in boundaryRoles || role == MineAnchorRole.PROSPECT && block.type in definition.mineable
        }
    }

    private fun key(zoneId: String) = NamespacedKey(plugin, "mine_index_$zoneId")

    private fun position(chunk: ChunkKey, packed: Int) = WorksitePosition(
        chunk.world, (chunk.x shl 4) + (packed and 15), packed shr 8, (chunk.z shl 4) + ((packed ushr 4) and 15),
    )

    internal companion object {
        const val MAX_TARGETS_PER_ZONE = 250_000
        private val boundaryRoles = setOf(MineAnchorRole.SUPPORT, MineAnchorRole.LAMP, MineAnchorRole.POWER)
        private fun packPosition(x: Int, y: Int, z: Int) = (y shl 8) or ((z and 15) shl 4) or (x and 15)
        private fun roleMask(roles: Set<MineAnchorRole>) = roles.fold(0) { mask, role -> mask or (1 shl role.ordinal) }
        private val rolesByMask = Array(1 shl MineAnchorRole.entries.size) { mask ->
            MineAnchorRole.entries.filterTo(linkedSetOf()) { mask and (1 shl it.ordinal) != 0 }
        }
    }
}

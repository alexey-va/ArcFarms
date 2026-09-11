package ru.ruscrafting.farms.paper.mine.index

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.ActivityRegion

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

/** Durable chunk-PDC-backed topology. Hot readers never load a chunk or scan a region. */
internal class MineBlockIndex(private val plugin: Plugin) {
    private val targetsByZone = mutableMapOf<String, MutableSet<MineIndexedTarget>>()

    fun targets(zoneId: String, role: MineAnchorRole): Set<WorksitePosition> = targetsByZone[zoneId].orEmpty()
        .asSequence().filter { role in it.roles }.map(MineIndexedTarget::position).toCollection(linkedSetOf())

    fun loadedTargets(zoneId: String, role: MineAnchorRole): Set<WorksitePosition> =
        targets(zoneId, role).filterTo(linkedSetOf()) { position ->
            Bukkit.getWorld(position.world)?.isChunkLoaded(position.x shr 4, position.z shr 4) == true
        }

    fun contains(zoneId: String, block: org.bukkit.block.Block, role: MineAnchorRole): Boolean {
        val position = WorksitePosition(block.world.name, block.x, block.y, block.z)
        return targetsByZone[zoneId].orEmpty().any { it.position == position && role in it.roles }
    }

    fun isLiveTarget(
        zoneId: String,
        position: WorksitePosition,
        role: MineAnchorRole,
        railMaterials: Set<Material> = emptySet(),
    ): Boolean {
        val world = Bukkit.getWorld(position.world) ?: return false
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return false
        val block = world.getBlockAt(position.x, position.y, position.z)
        return contains(zoneId, block, role) && role in MineAnchorClassifier.classify(block, emptySet(), railMaterials)
    }

    fun reconcileChunk(definition: MineIndexDefinition, chunk: Chunk) {
        if (chunk.world !== definition.region.world) return
        val decoded = decode(chunk.world.name, chunk.persistentDataContainer.get(key(definition.zoneId), PersistentDataType.STRING))
            .mapNotNull { target ->
                val position = target.position
                if ((position.x shr 4) != chunk.x || (position.z shr 4) != chunk.z) return@mapNotNull null
                val block = chunk.world.getBlockAt(position.x, position.y, position.z)
                if (!definition.region.contains(block.location)) return@mapNotNull null
                val valid = MineAnchorClassifier.classify(block, definition.mineable, definition.railMaterials)

                target.copy(roles = valid).takeIf { valid.isNotEmpty() }
            }
            .toSet()
        replaceChunk(definition.zoneId, chunk, decoded)
    }

    internal fun replaceZone(
        definition: MineIndexDefinition,
        chunks: Collection<Chunk>,
        targets: Collection<MineIndexedTarget>,
    ) {
        val bounded = targets.distinct().also { require(it.size <= MAX_TARGETS_PER_ZONE) }
        targetsByZone[definition.zoneId] = bounded.toCollection(linkedSetOf())
        val byChunk = bounded.groupBy { (it.position.x shr 4) to (it.position.z shr 4) }
        chunks.forEach { chunk ->
            val encoded = encode(byChunk[chunk.x to chunk.z].orEmpty())
            if (encoded.isEmpty()) chunk.persistentDataContainer.remove(key(definition.zoneId))
            else chunk.persistentDataContainer.set(key(definition.zoneId), PersistentDataType.STRING, encoded)
        }
    }

    fun refreshBlock(definition: MineIndexDefinition, block: org.bukkit.block.Block) {
        val chunk = block.chunk
        val position = WorksitePosition(block.world.name, block.x, block.y, block.z)
        val current = targetsByZone.getOrPut(definition.zoneId, ::linkedSetOf)
        current.removeIf { it.position == position }
        val roles = MineAnchorClassifier.classify(block, definition.mineable, definition.railMaterials)
        if (roles.isNotEmpty()) current += MineIndexedTarget(position, roles)
        chunk.persistentDataContainer.set(key(definition.zoneId), PersistentDataType.STRING,
            encode(current.filter { (it.position.x shr 4) == chunk.x && (it.position.z shr 4) == chunk.z }))
    }

    fun clear() = targetsByZone.clear()

    private fun replaceChunk(zoneId: String, chunk: Chunk, replacement: Set<MineIndexedTarget>) {
        val current = targetsByZone.getOrPut(zoneId, ::linkedSetOf)
        current.removeIf { it.position.world == chunk.world.name && (it.position.x shr 4) == chunk.x && (it.position.z shr 4) == chunk.z }
        current += replacement
        if (current.isEmpty()) targetsByZone.remove(zoneId)
    }

    private fun key(zoneId: String) = NamespacedKey(plugin, "mine_index_$zoneId")

    private fun encode(targets: Collection<MineIndexedTarget>): String = targets.joinToString(";") { target ->
        val p = target.position
        "${p.x},${p.y},${p.z},${target.roles.joinToString("+") { it.name }}"
    }

    private fun decode(world: String, encoded: String?): List<MineIndexedTarget> = encoded.orEmpty().split(';').mapNotNull { entry ->
        if (entry.isBlank()) return@mapNotNull null
        val parts = entry.split(',')
        if (parts.size != 4) return@mapNotNull null
        runCatching {
            MineIndexedTarget(
                WorksitePosition(world, parts[0].toInt(), parts[1].toInt(), parts[2].toInt()),
                parts[3].split('+').map(MineAnchorRole::valueOf).toSet(),
            )
        }.getOrNull()
    }

    internal companion object { const val MAX_TARGETS_PER_ZONE = 250_000 }
}

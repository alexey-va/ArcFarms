package ru.ruscrafting.farms.paper.lumber.index

import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.plugin.Plugin
import org.bukkit.persistence.PersistentDataType
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.ActivityRegion
import ru.ruscrafting.farms.paper.MaterialRules

internal data class LumberIndexDefinition(
    val zoneId: String,
    val region: ActivityRegion,
    val species: Set<String>,
    val maxBlocks: Int = 20_000_000,
) {
    init {
        require(zoneId.matches(Regex("[a-z0-9_-]{1,48}")))
        require(species.isNotEmpty())
        require(maxBlocks in 1..20_000_000)
        require(region.bounds.volume <= maxBlocks)
    }
}

internal data class LumberLogTarget(
    val position: WorksitePosition,
    val species: String,
)

/** Durable chunk-PDC-backed index. Region scans happen only through an explicit bounded reindex job. */
internal class LumberBlockIndex(private val plugin: Plugin) {
    private val targetsByZone = mutableMapOf<String, MutableSet<LumberLogTarget>>()

    fun logs(zoneId: String, species: String): Set<WorksitePosition> = targetsByZone[zoneId].orEmpty()
        .asSequence()
        .filter { it.species == species }
        .map(LumberLogTarget::position)
        .toCollection(linkedSetOf())

    fun loadedLogs(zoneId: String, species: String): Set<WorksitePosition> = logs(zoneId, species).filterTo(linkedSetOf()) {
        val world = org.bukkit.Bukkit.getWorld(it.world) ?: return@filterTo false
        world.isChunkLoaded(it.x shr 4, it.z shr 4)
    }

    fun contains(zoneId: String, block: org.bukkit.block.Block, species: String): Boolean =
        LumberLogTarget(WorksitePosition(block.world.name, block.x, block.y, block.z), species) in targetsByZone[zoneId].orEmpty()

    fun reconcileChunk(definition: LumberIndexDefinition, chunk: Chunk) {
        if (chunk.world !== definition.region.world) return
        val decoded = decode(chunk.world.name, chunk.persistentDataContainer.get(key(definition.zoneId), PersistentDataType.STRING))
            .filter { target ->
                val position = target.position
                val block = chunk.world.getBlockAt(position.x, position.y, position.z)
                position.world == chunk.world.name && (position.x shr 4) == chunk.x && (position.z shr 4) == chunk.z &&
                    definition.region.contains(block.location) && target.species in definition.species &&
                    MaterialRules.speciesOf(block.type) == target.species
            }
            .toSet()
        replaceChunk(definition.zoneId, chunk, decoded)
    }

    internal fun replaceZone(
        definition: LumberIndexDefinition,
        chunks: Collection<Chunk>,
        targets: Collection<LumberLogTarget>,
    ) {
        val bounded = targets.distinct().also { require(it.size <= MAX_TARGETS_PER_ZONE) }
        targetsByZone[definition.zoneId] = bounded.toCollection(linkedSetOf())
        val byChunk = bounded.groupBy { (it.position.x shr 4) to (it.position.z shr 4) }
        chunks.forEach { chunk ->
            val encoded = encode(byChunk[chunk.x to chunk.z].orEmpty())
            val pdc = chunk.persistentDataContainer
            if (encoded.isEmpty()) pdc.remove(key(definition.zoneId))
            else pdc.set(key(definition.zoneId), PersistentDataType.STRING, encoded)
        }
    }

    fun clear() = targetsByZone.clear()

    private fun replaceChunk(zoneId: String, chunk: Chunk, replacement: Set<LumberLogTarget>) {
        val current = targetsByZone.getOrPut(zoneId, ::linkedSetOf)
        current.removeIf { target ->
            target.position.world == chunk.world.name && (target.position.x shr 4) == chunk.x && (target.position.z shr 4) == chunk.z
        }
        current += replacement
        if (current.isEmpty()) targetsByZone.remove(zoneId)
    }

    private fun key(zoneId: String) = NamespacedKey(plugin, "lumber_logs_$zoneId")

    private fun encode(targets: Collection<LumberLogTarget>): String = targets.joinToString(";") { target ->
        val p = target.position
        "${p.x},${p.y},${p.z},${target.species}"
    }

    private fun decode(world: String, encoded: String?): List<LumberLogTarget> = encoded.orEmpty().split(';').mapNotNull { entry ->
        if (entry.isBlank()) return@mapNotNull null
        val parts = entry.split(',')
        if (parts.size != 4) return@mapNotNull null
        runCatching {
            LumberLogTarget(
                WorksitePosition(world, parts[0].toInt(), parts[1].toInt(), parts[2].toInt()),
                parts[3],
            )
        }.getOrNull()
    }

    private companion object {
        const val MAX_TARGETS_PER_ZONE = 100_000
    }
}

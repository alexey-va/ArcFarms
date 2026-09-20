package ru.ruscrafting.farms.paper.mine.incident

import org.bukkit.Material
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex
import ru.ruscrafting.farms.paper.mine.incident.entity.hasMineObjectiveMarkerSpace

/** Indexed candidates are validated ahead of requests, in small server-thread slices. */
internal class MineIncidentCandidateStock(private val index: MineBlockIndex) {
    private data class Pool(var source: List<WorksitePosition> = emptyList(), var cursor: Int = 0,
        var refreshAt: Long = 0, val ready: LinkedHashSet<WorksitePosition> = linkedSetOf(),
        var considered: Int = 0, var rejected: Int = 0)
    private val pools = mutableMapOf<Pair<String, MineIncidentType>, Pool>()
    private var lane = 0

    fun prewarm(runtime: MineRuntime, now: Long) {
        val type = TYPES[lane++ % TYPES.size]
        val pool = pools.getOrPut(runtime.settings.id to type) { Pool() }
        if (pool.cursor >= pool.source.size && now >= pool.refreshAt) {
            pool.source = index.loadedTargets(runtime.settings.id, role(type)).toList()
            pool.cursor = 0
            pool.refreshAt = now + 15_000
            pool.considered = 0; pool.rejected = 0
        }
        val deadline = System.nanoTime() + 1_000_000L
        repeat(32) {
            if (System.nanoTime() >= deadline) return
            val position = pool.source.getOrNull(pool.cursor++) ?: return
            pool.considered++
            if (valid(runtime, type, position)) {
                pool.ready.add(position)
                if (pool.ready.size > 256) pool.ready.remove(pool.ready.first())
            } else { pool.ready.remove(position); pool.rejected++ }
        }
    }

    fun candidates(runtime: MineRuntime, type: MineIncidentType): List<WorksitePosition> =
        pools[runtime.settings.id to type]?.ready?.toList().orEmpty()

    fun report(runtime: MineRuntime, type: MineIncidentType, required: Int): MineIncidentPlacementReport {
        val p = pools[runtime.settings.id to type]
        return MineIncidentPlacementReport(type, required, p?.ready?.size ?: 0, p?.considered ?: 0,
            if (p == null) mapOf("preparation_pending" to 1) else mapOf("unsuitable" to p.rejected))
    }

    fun clear() = pools.clear()

    private fun valid(runtime: MineRuntime, type: MineIncidentType, p: WorksitePosition): Boolean {
        if (!index.isLiveTarget(runtime.settings.id, p, role(type), runtime.railMaterials)) return false
        if (type == MineIncidentType.CRYSTAL_RESONANCE) return p.blockType() == Material.AMETHYST_CLUSTER && hasMineObjectiveMarkerSpace(p)
        if (!runtime.isIncidentSurface(p)) return false
        if (type == MineIncidentType.FLOODING) return runtime.floodFootprint(p).let { it.size >= 20 && it.all { q -> q.blockType() == Material.AIR } }
        if (type == MineIncidentType.CREATURE_NEST) return (-2..2).all { dx -> (-2..2).all { dz ->
            dx * dx + dz * dz > 4 || index.isLiveTarget(runtime.settings.id, p.copy(x = p.x + dx, z = p.z + dz), MineAnchorRole.NEST, runtime.railMaterials)
        } }
        return hasMineObjectiveMarkerSpace(p)
    }

    private fun role(type: MineIncidentType) = when (type) {
        MineIncidentType.CRYSTAL_RESONANCE -> MineAnchorRole.CRYSTAL
        MineIncidentType.GAS_LEAK -> MineAnchorRole.SUPPORT
        MineIncidentType.LOST_MINER -> MineAnchorRole.MINER
        else -> MineAnchorRole.NEST
    }

    private companion object {
        val TYPES = listOf(MineIncidentType.FLOODING, MineIncidentType.GAS_LEAK,
            MineIncidentType.CREATURE_NEST, MineIncidentType.CRYSTAL_RESONANCE, MineIncidentType.LOST_MINER)
    }
}

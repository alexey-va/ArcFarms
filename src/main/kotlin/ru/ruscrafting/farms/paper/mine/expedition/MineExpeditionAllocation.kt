package ru.ruscrafting.farms.paper.mine.expedition

import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import ru.ruscrafting.farms.persistence.MineExpeditionSceneReceipt

/** Disjoint fixed-size cells cover every supported plan, including its moving machine. */
internal object MineExpeditionAllocation {
    fun allocate(kind: MineExpeditionKind, id: Long, occupied: Collection<MineExpeditionSceneReceipt>): MineExpeditionPlacement {
        val seed = WorksiteDeterministicSeed.derive(id, kind.ordinal.toLong())
        val cells = occupied.map { (it.placement.originX / CELL_SIZE) to (it.placement.originZ / CELL_SIZE) }.toSet()
        repeat(4_096) { probe ->
            val score = WorksiteDeterministicSeed.gridScore(seed, probe, kind.ordinal)
            val x = Math.floorMod(score, GRID_SIZE.toLong()).toInt() - GRID_SIZE / 2
            val z = Math.floorMod(score ushr 32, GRID_SIZE.toLong()).toInt() - GRID_SIZE / 2
            if ((x to z) !in cells) return MineExpeditionPlacement(MineExpeditionWorldGenerator.WORLD_NAME, x * CELL_SIZE, 0, z * CELL_SIZE, seed)
        }
        error("No bounded expedition allocation cell is available")
    }
    private const val CELL_SIZE = 128
    private const val GRID_SIZE = 4_096
}

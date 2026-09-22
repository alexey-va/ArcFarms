package ru.ruscrafting.farms.paper.mine.expedition

import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionKind
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionGenerator
import ru.ruscrafting.farms.domain.worksite.WorksiteDeterministicSeed
import ru.ruscrafting.farms.persistence.MineExpeditionSceneReceipt

internal data class MineExpeditionSite(val world: String, val centerX: Int, val northZ: Int, val floorY: Int,
    val surfaceX: Double, val surfaceY: Double, val surfaceZ: Double)

/** Nearby disjoint 112-block cells north of the authored mine and its side workings. */
internal object MineExpeditionAllocation {
    fun allocate(kind: MineExpeditionKind, id: Long, occupied: Collection<MineExpeditionSceneReceipt>,
        site: MineExpeditionSite): MineExpeditionPlacement {
        val seed = WorksiteDeterministicSeed.derive(id, kind.ordinal.toLong())
        repeat(12) { probe ->
            val slot = (probe + kind.ordinal) % 12
            val x = site.centerX + (slot % 3 - 1) * 112
            val z = site.northZ - 112 - slot / 3 * 112
            if (occupied.none { it.placement.world == site.world &&
                    kotlin.math.abs(it.placement.originX - x) < 100 && kotlin.math.abs(it.placement.originZ - z) < 100 }) {
                return MineExpeditionPlacement(site.world, x, site.floorY - 5, z, seed,
                    MineExpeditionGenerator.currentGeometryVersion(kind))
            }
        }
        error("All nearby expedition sites are occupied")
    }
}

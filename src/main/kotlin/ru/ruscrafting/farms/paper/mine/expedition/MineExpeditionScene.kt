package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Location
import org.bukkit.World
import ru.ruscrafting.farms.domain.mine.expedition.ExpeditionPoint
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlan
import ru.ruscrafting.farms.domain.mine.expedition.MineExpeditionPlacement
import ru.ruscrafting.farms.paper.worksite.scene.WorksitePreparedScene
import kotlin.math.floor

/** Runtime view joining the pure plan, durable placement and prepared-scene journal. */
internal class MineExpeditionScene(
    val plan: MineExpeditionPlan,
    val placement: MineExpeditionPlacement,
    val world: World,
    val zoneId: String,
    val sequence: Long,
    val objectiveNonce: Long,
    val journalSequence: Long,
    val surface: Location,
    internal val prepared: WorksitePreparedScene,
    var completedAt: Long = 0L,
) {
    /** Stable incident identity; journalSequence is the cross-shift journal identity. */
    val nonce: Long get() = objectiveNonce
    val sceneId: Int get() = objectiveNonce.toInt()
    val kind get() = plan.kind
    private var readyCached = false
    val ready: Boolean get() = readyCached

    /** The owner calls this after a bounded process pass; never scan a large scene per tick. */
    fun refreshReady(building: Boolean, complete: Boolean = false) {
        readyCached = !building && complete
    }

    internal fun invalidateReady() {
        readyCached = false
    }

    fun at(local: ExpeditionPoint): Location = Location(
        world,
        placement.originX + local.x + 0.5,
        placement.originY + local.y.toDouble(),
        placement.originZ + local.z + 0.5,
    )

    fun station(id: String): Location = at(plan.stations.getValue(id))

    fun contains(location: Location, margin: Double = 0.0): Boolean {
        if (location.world !== world) return false
        val x = location.x - placement.originX
        val y = location.y - placement.originY
        val z = location.z - placement.originZ
        return x >= plan.bounds.min.x - margin && x <= plan.bounds.max.x + 1.0 + margin &&
            y >= plan.bounds.min.y - margin && y <= plan.bounds.max.y + 1.0 + margin &&
            z >= plan.bounds.min.z - margin && z <= plan.bounds.max.z + 1.0 + margin
    }

    fun local(location: Location): ExpeditionPoint? {
        if (location.world !== world) return null
        return ExpeditionPoint(
            floor(location.x - placement.originX).toInt(),
            floor(location.y - placement.originY).toInt(),
            floor(location.z - placement.originZ).toInt(),
        ).takeIf(plan.bounds::contains)
    }
}

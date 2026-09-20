package ru.ruscrafting.farms.domain.mine.expedition

/** Local block coordinates; a route point denotes the player's feet. */
data class ExpeditionPoint(val x: Int, val y: Int, val z: Int) {
    fun offset(dx: Int = 0, dy: Int = 0, dz: Int = 0) = ExpeditionPoint(x + dx, y + dy, z + dz)
}

enum class MineExpeditionKind { LAST_DESCENT, DRILLING_ARK, DEAD_FACTORY }

data class ExpeditionBounds(val min: ExpeditionPoint, val max: ExpeditionPoint) {
    fun contains(point: ExpeditionPoint): Boolean =
        point.x in min.x..max.x && point.y in min.y..max.y && point.z in min.z..max.z
}

/** Pure, replayable scene; unspecified cells retain solid natural terrain. */
data class MineExpeditionPlan(
    val kind: MineExpeditionKind,
    val seed: Long,
    val bounds: ExpeditionBounds,
    val blocks: Map<ExpeditionPoint, String>,
    val stations: Map<String, ExpeditionPoint>,
    val routes: Map<String, List<ExpeditionPoint>>,
    val walkingRoutes: List<List<ExpeditionPoint>>,
) {
    val spawn: ExpeditionPoint get() = stations.getValue("entry")
    val exit: ExpeditionPoint get() = stations.getValue("exit")
}

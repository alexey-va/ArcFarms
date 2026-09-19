package ru.ruscrafting.farms.paper.mine.point

import com.google.gson.Gson
import ru.ruscrafting.farms.domain.MineLocations
import ru.ruscrafting.farms.domain.MineZoneLocations

/**
 * Optional map-owned geometry. The source is deliberately a bundled resource,
 * not runtime data/config, and is selected only for the named compact-mine world.
 */
internal class MinePointDefaults(
    private val locations: MineLocations = loadCompactMap(),
) {
    fun zone(zoneId: String, worldName: String): MineZoneLocations? =
        if (worldName == COMPACT_MINE_WORLD) locations.zones[zoneId] else null

    private companion object {
        const val COMPACT_MINE_WORLD = "rc_atelier_compact_mine"
        const val RESOURCE = "mine/compact-map-points.json"

        fun loadCompactMap(): MineLocations {
            val stream = MinePointDefaults::class.java.classLoader.getResourceAsStream(RESOURCE) ?: return MineLocations()
            return stream.use { input ->
                Gson().fromJson(input.reader(Charsets.UTF_8), MineLocations::class.java) ?: MineLocations()
            }
        }
    }
}

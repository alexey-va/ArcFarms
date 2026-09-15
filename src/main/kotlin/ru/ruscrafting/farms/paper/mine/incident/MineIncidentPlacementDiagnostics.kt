package ru.ruscrafting.farms.paper.mine.incident

import org.bukkit.Bukkit
import org.bukkit.Material
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import ru.ruscrafting.farms.paper.mine.MineRuntime
import ru.ruscrafting.farms.paper.mine.index.MineAnchorRole
import ru.ruscrafting.farms.paper.mine.index.MineBlockIndex

internal data class MineIncidentPlacementReport(
    val type: MineIncidentType,
    val required: Int,
    val usable: Int,
    val considered: Int,
    val rejected: Map<String, Int>,
) {
    fun technical(): String = "required=$required usable=$usable considered=$considered " +
        "rejected=${rejected.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }}"
}

internal class MineIncidentPlacementDiagnostics(private val index: MineBlockIndex) {
    fun report(runtime: MineRuntime, type: MineIncidentType, required: Int): MineIncidentPlacementReport {
        val needed = required * runtime.rules().targetMultiplier
        val role = role(type) ?: return MineIncidentPlacementReport(
            type, needed, 0, 0, mapOf("in_place_implementation_missing" to 1),
        )
        val rejected = linkedMapOf<String, Int>()
        var usable = 0
        val candidates = index.targets(runtime.settings.id, role)
        candidates.forEach { position ->
            val reason = issue(runtime, type, role, position)
            if (reason == null) usable++ else rejected[reason] = rejected.getOrDefault(reason, 0) + 1
        }
        return MineIncidentPlacementReport(type, needed, usable, candidates.size, rejected)
    }

    fun describe(runtime: MineRuntime, type: MineIncidentType, required: Int): String = report(runtime, type, required).technical()

    private fun issue(runtime: MineRuntime, type: MineIncidentType, role: MineAnchorRole, position: WorksitePosition): String? {
        val world = Bukkit.getWorld(position.world) ?: return "world_unavailable"
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return "chunk_unloaded"
        if (!index.isLiveTarget(runtime.settings.id, position, role, runtime.railMaterials)) return "anchor_changed"
        if (type != MineIncidentType.CRYSTAL_RESONANCE && type != MineIncidentType.TRACK_DAMAGE &&
            !runtime.isIncidentSurface(position)) return "decorative_surface"
        if (type == MineIncidentType.FLOODING) {
            if (position.floodFootprint().any { water ->
                    water.blockType() != Material.AIR || !runtime.isIncidentSurface(water.copy(y = water.y - 1))
                }) return "flood_footprint_blocked"
        }
        if (type == MineIncidentType.POWER_FAILURE) {
            val above = world.getBlockAt(position.x, position.y + 1, position.z)
            if (above.type != Material.AIR) return "space_above_occupied"
        }
        return null
    }

    private fun role(type: MineIncidentType): MineAnchorRole? = when (type) {
        MineIncidentType.GAS_LEAK -> MineAnchorRole.SUPPORT
        MineIncidentType.FLOODING -> MineAnchorRole.NEST
        MineIncidentType.POWER_FAILURE -> MineAnchorRole.POWER
        MineIncidentType.CREATURE_NEST -> MineAnchorRole.NEST
        MineIncidentType.TRACK_DAMAGE -> MineAnchorRole.RAIL
        MineIncidentType.CRYSTAL_RESONANCE -> MineAnchorRole.CRYSTAL
        MineIncidentType.LOST_MINER -> MineAnchorRole.MINER
        else -> null
    }
}

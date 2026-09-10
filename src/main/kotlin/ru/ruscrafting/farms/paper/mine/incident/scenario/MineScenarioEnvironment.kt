package ru.ruscrafting.farms.paper.mine.incident.scenario

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import ru.ruscrafting.farms.domain.MineIncidentType
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowJournalRecord
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowScene
import ru.ruscrafting.farms.paper.platform.FarmBlockDataDecoder
import ru.ruscrafting.farms.paper.mine.MineRuntime

/** Bounded room-local mutations. Every mutation is an owned room journal record. */
internal class MineScenarioEnvironment(private val decoder: FarmBlockDataDecoder) {
    private data class Applied(val stage: String, val records: List<FarmMoleBurrowJournalRecord>)
    private val applied = mutableMapOf<String, Applied>()

    /** [stageProgress] is total completed targets across the scenario, not within-stage progress. */
    fun apply(runtime: MineRuntime, room: FarmMoleBurrowScene, stageIndex: Int, stageProgress: Int) {
        val incident = runtime.state.incident?.type ?: return
        val mode = mapping[incident] ?: return
        val key = "${runtime.settings.id}:${runtime.state.sequence}"
        val stage = "$mode:$stageIndex:$stageProgress"
        if (applied[key]?.stage == stage) return
        applied.remove(key)?.records?.forEach { restore(room, it) }
        val records = roomRecords(room)
        val changed = when (mode) {
            "cave_in" -> caveIn(records, stageProgress)
            "flood" -> flood(records)
            "gas", "fungal", "roots", "warehouse", "drill" -> alcove(records)
            "power" -> if (stageProgress >= POWER_RESTORE_PROGRESS) emptyList() else powerLights(records)
            "lava" -> lava(records)
            "ancient" -> if (stageProgress >= ANCIENT_UNLOCK_PROGRESS) emptyList() else ancientGate(records)
            else -> emptyList()
        }
        val material = when (mode) {
            "cave_in", "warehouse" -> "minecraft:cobblestone"
            "flood" -> "minecraft:water[level=0]"
            "gas" -> "minecraft:green_stained_glass"
            "power" -> "minecraft:black_concrete"
            "fungal" -> "minecraft:red_mushroom_block"
            "roots" -> "minecraft:mangrove_roots"
            "lava" -> "minecraft:lava[level=0]"
            "ancient" -> "minecraft:deepslate_bricks"
            "drill" -> "minecraft:redstone_lamp"
            else -> null
        }
        if (material != null) changed.forEach { set(room, it, material) }
        val enclosure = if (mode == "flood" || mode == "lava") containment(records) else emptyList()
        if (mode == "flood" || mode == "lava") enclosure.forEach { set(room, it, "minecraft:glass") }
        applied[key] = Applied(stage, changed + enclosure)
    }

    /** Gas effects stay inside the room and only affect nearby players. */
    fun tickPlayers(runtime: MineRuntime, room: FarmMoleBurrowScene, players: Iterable<Player>) {
        if (runtime.state.incident?.type != MineIncidentType.GAS_LEAK) return
        val hazards = applied["${runtime.settings.id}:${runtime.state.sequence}"]?.records.orEmpty()
        players.filter { it.isOnline && room.contains(it.location) }.forEach { player ->
            hazards.firstOrNull { near(room, it, player.location) }?.let { hazard ->
                val location = Location(room.world, hazard.x + .5, hazard.y + .5, hazard.z + .5)
                player.spawnParticle(Particle.CLOUD, location, 2, .12, .12, .12, 0.0)
                player.addPotionEffect(PotionEffect(PotionEffectType.NAUSEA, 40, 0, false, false, false))
            }
        }
    }

    fun forget(zoneId: String) { applied.keys.removeIf { it.startsWith("$zoneId:") } }

    fun cleanup(runtime: MineRuntime, room: FarmMoleBurrowScene) {
        applied.remove("${runtime.settings.id}:${runtime.state.sequence}")?.records?.forEach { restore(room, it) }
    }

    companion object {
        private const val POWER_RESTORE_PROGRESS = 3
        private const val ANCIENT_UNLOCK_PROGRESS = 2
        val mapping: Map<MineIncidentType, String> = mapOf(
            MineIncidentType.CAVE_IN to "cave_in", MineIncidentType.FLOODING to "flood",
            MineIncidentType.GAS_LEAK to "gas", MineIncidentType.POWER_FAILURE to "power",
            MineIncidentType.FUNGAL_BLOOM to "fungal", MineIncidentType.ROOT_INVASION to "roots",
            MineIncidentType.LAVA_BREACH to "lava", MineIncidentType.ANCIENT_DOOR to "ancient",
            MineIncidentType.OLD_WAREHOUSE to "warehouse", MineIncidentType.DRILL_TRIAL to "drill",
        )

        internal fun localShape(mode: String, totalProgress: Int = 0): Set<Triple<Int, Int, Int>> = when (mode) {
            "alcove" -> buildSet { for (z in 8..19) for (x in listOf(2, 14)) add(Triple(x, 2, z)) }
            "cave_in" -> buildSet {
                val all = buildList { for (y in 2..3) for (x in listOf(4, 5, 6, 10, 11, 12)) add(Triple(x, y, 16)) }
                addAll(all.drop((totalProgress * 2).coerceAtMost(all.size)))
            }
            "ancient" -> buildSet { for (y in 2..4) for (z in 3..5) add(Triple(14, y, z)) }
            "flood" -> buildSet { for (z in 20..22) for (x in 4..5) add(Triple(x, 1, z)) }
            "lava" -> buildSet { for (z in 20..21) add(Triple(5, 1, z)) }
            "containment" -> buildSet {
                for (x in 3..6) for (z in 19..23) add(Triple(x, 0, z)); for (x in 3..6) for (z in 19..23) add(Triple(x, 2, z))
                for (z in 19..23) { add(Triple(3, 1, z)); add(Triple(6, 1, z)) }
                for (x in 3..6) { add(Triple(x, 1, 19)); add(Triple(x, 1, 23)) }
            }
            else -> emptySet()
        }
    }

    private fun roomRecords(room: FarmMoleBurrowScene): Map<Triple<Int, Int, Int>, FarmMoleBurrowJournalRecord> {
        val ox = room.records.minOfOrNull { it.x } ?: return emptyMap()
        val oy = room.records.minOfOrNull { it.y } ?: return emptyMap()
        val oz = room.records.minOfOrNull { it.z } ?: return emptyMap()
        return room.records.associateBy { Triple(it.x - ox, it.y - oy, it.z - oz) }
    }

    private fun alcove(records: Map<Triple<Int, Int, Int>, FarmMoleBurrowJournalRecord>) =
        records.filterKeys { it in localShape("alcove") }.values.toList()

    private fun caveIn(records: Map<Triple<Int, Int, Int>, FarmMoleBurrowJournalRecord>, progress: Int) =
        records.filterKeys { it in localShape("cave_in", progress) }.values.toList()

    private fun ancientGate(records: Map<Triple<Int, Int, Int>, FarmMoleBurrowJournalRecord>) =
        records.filterKeys { it in localShape("ancient") }.values.toList()

    private fun powerLights(records: Map<Triple<Int, Int, Int>, FarmMoleBurrowJournalRecord>) =
        records.values.filter { it.burrowData.substringAfterLast(':').substringBefore('[').endsWith("froglight") }

    private fun flood(records: Map<Triple<Int, Int, Int>, FarmMoleBurrowJournalRecord>) =
        records.filterKeys { it in localShape("flood") }.values.toList()

    private fun lava(records: Map<Triple<Int, Int, Int>, FarmMoleBurrowJournalRecord>) =
        records.filterKeys { it in localShape("lava") }.values.toList()

    private fun containment(records: Map<Triple<Int, Int, Int>, FarmMoleBurrowJournalRecord>) =
        records.filterKeys { it in localShape("containment") }.values.toList()

    private fun near(room: FarmMoleBurrowScene, record: FarmMoleBurrowJournalRecord, location: Location) =
        Location(room.world, record.x + .5, record.y + .5, record.z + .5).distanceSquared(location) <= 6.25

    private fun set(room: FarmMoleBurrowScene, record: FarmMoleBurrowJournalRecord, serialized: String) {
        if (record.world != room.world.name || !room.world.isChunkLoaded(record.x shr 4, record.z shr 4)) return
        val data = runCatching { decoder.decode(serialized) }.getOrNull() ?: return
        room.world.getBlockAt(record.x, record.y, record.z).setBlockData(data, false)
    }

    private fun restore(room: FarmMoleBurrowScene, record: FarmMoleBurrowJournalRecord) = set(room, record, record.burrowData)
}

package ru.ruscrafting.farms.paper.mine.incident.scenario

import com.google.gson.JsonParser
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowJournalRecord
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowMarker
import ru.ruscrafting.farms.paper.farm.care.mole.FarmMoleBurrowScene
import ru.ruscrafting.farms.paper.platform.FarmBlockDataDecoder

/** The reviewed atelier export is also the production geometry, including the air envelope. */
internal class MineEventRoomTemplate(private val decoder: FarmBlockDataDecoder) {
    private val root = requireNotNull(javaClass.getResourceAsStream("/mine/events/room.blocks.json"))
        .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    val size: List<Int> = root.getAsJsonArray("size").map { it.asInt }
    private val materials = root.getAsJsonArray("materials").map { it.asJsonObject.get("block").asString }
    private val canonicalData by lazy { (materials + "minecraft:air").associateWith { decoder.decode(it).asString } }
    private val blocks = root.getAsJsonArray("blocks").associate { entry ->
        val values = entry.asJsonArray.map { it.asInt }
        require(values.size == 4 && values[3] in materials.indices)
        Triple(values[0], values[1], values[2]) to materials[values[3]]
    }

    private val metadata = requireNotNull(javaClass.getResourceAsStream("/mine/events/room.metadata.json"))
        .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    private fun coordinate(values: com.google.gson.JsonArray): Triple<Int, Int, Int> {
        require(values.size() == 3)
        return Triple(values[0].asInt, values[1].asInt, values[2].asInt)
    }
    val entryFeet = coordinate(metadata.getAsJsonArray("entryFeet"))
    val exitFeet = coordinate(metadata.getAsJsonArray("exitFeet"))
    val pads = metadata.getAsJsonArray("objectivePads").map { coordinate(it.asJsonObject.getAsJsonArray("center")) }

    init {
        require(size.size == 3 && size.all { it in 1..32 } && size.reduce(Int::times) <= 8192)
        require(blocks.keys.all { (x, y, z) -> x in 0 until size[0] && y in 0 until size[1] && z in 0 until size[2] })
    }

    /** No terrain or block-entity content is silently overwritten to make an event fit. */
    fun prepare(world: World, zone: String, sequence: Long, origin: Location, entrance: Location): FarmMoleBurrowScene? {
        require(origin.world === world && entrance.world === world)
        val ox = origin.blockX
        val oy = origin.blockY
        val oz = origin.blockZ
        if (oy < world.minHeight || oy + size[1] > world.maxHeight) return null
        val chunks = ((ox shr 4)..((ox + size[0] - 1) shr 4)).flatMap { x ->
            ((oz shr 4)..((oz + size[2] - 1) shr 4)).map { z -> x to z }
        }
        if (chunks.any { (x, z) -> !world.isChunkLoaded(x, z) }) return null
        val count = size.reduce(Int::times)
        val records = ArrayList<FarmMoleBurrowJournalRecord>(count)
        for (x in 0 until size[0]) for (z in 0 until size[2]) for (y in 0 until size[1]) {
            val block = world.getBlockAt(ox + x, oy + y, oz + z)
            if (block.type !in REPLACEABLE) return null
            val data = canonicalData.getValue(blocks[Triple(x, y, z)] ?: "minecraft:air")
            records += FarmMoleBurrowJournalRecord(world.name, zone, sequence, ROOM_ID,
                block.x, block.y, block.z, block.blockData.asString, data,
                when (Triple(x, y, z)) {
                    entryFeet -> FarmMoleBurrowMarker.START
                    exitFeet -> FarmMoleBurrowMarker.LAIR
                    else -> FarmMoleBurrowMarker.NONE
                }, count)
        }
        fun location(position: Triple<Int, Int, Int>) = Location(world,
            ox + position.first + 0.5, oy + position.second.toDouble(), oz + position.third + 0.5, 180f, 0f)
        return FarmMoleBurrowScene(world, zone, sequence, ROOM_ID, entrance.clone(), location(entryFeet), location(exitFeet), records)
    }

    companion object {
        const val ROOM_ID = 1
        private val REPLACEABLE = setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.STONE, Material.DEEPSLATE, Material.TUFF)
    }
}

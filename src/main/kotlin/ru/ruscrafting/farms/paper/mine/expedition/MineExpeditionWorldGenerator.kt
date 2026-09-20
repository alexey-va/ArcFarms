package ru.ruscrafting.farms.paper.mine.expedition

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.WorldInfo
import java.util.Random

/** Solid geological reserve. Event caves are excavated into it through the shared durable block journal. */
internal class MineExpeditionWorldGenerator : ChunkGenerator() {
    override fun generateNoise(worldInfo: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, chunkData: ChunkData) {
        val bottom = chunkData.minHeight
        val roof = minOf(192, chunkData.maxHeight - 8)
        chunkData.setRegion(0, bottom, 0, 16, roof, 16, Material.STONE)
        chunkData.setRegion(0, bottom, 0, 16, minOf(bottom + 3, roof), 16, Material.BEDROCK)
        if (bottom + 3 < 0) chunkData.setRegion(0, bottom + 3, 0, 16, 0, 16, Material.DEEPSLATE)
    }

    override fun shouldGenerateNoise() = false
    override fun shouldGenerateSurface() = false
    override fun shouldGenerateCaves() = false
    override fun shouldGenerateDecorations() = false
    override fun shouldGenerateMobs() = false
    override fun shouldGenerateStructures() = false
    override fun getFixedSpawnLocation(world: World, random: Random): Location = Location(world, 0.5, 192.0, 0.5)

    companion object { const val WORLD_NAME = "rc_arcfarms_expeditions" }
}

package ru.ruscrafting.farms.paper.fixtures

import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.TextDisplay
import ru.ruscrafting.farms.paper.platform.FarmBlockPlatform
import ru.ruscrafting.farms.paper.platform.FarmChunkLeaseManager
import ru.ruscrafting.farms.paper.platform.FarmEntityPlatform
import ru.ruscrafting.farms.paper.platform.FarmTextDisplayStyle

/** Explicit compatibility adapter for APIs intentionally absent from MockBukkit 4.110.0. */
internal object MockBukkitFarmBlockPlatform : FarmBlockPlatform {
    override fun isPassable(block: Block): Boolean = block.type.isAir

    override fun createBlockData(serialized: String): BlockData =
        runCatching { org.bukkit.Bukkit.createBlockData(serialized) }.getOrElse {
            requireNotNull(Material.matchMaterial(serialized.substringBefore('['))).createBlockData()
        }
}

internal object MockBukkitFarmEntityPlatform : FarmEntityPlatform {
    override fun configureTextDisplay(entity: TextDisplay, text: Component, style: FarmTextDisplayStyle) {
        entity.text(text)
        entity.viewRange = style.viewRange
        entity.isPersistent = style.persistent
    }

    override fun setRemoveWhenFarAway(entity: LivingEntity, value: Boolean) = Unit

    override fun ejectPassengers(entity: Entity): Boolean {
        val passengers = entity.passengers.toList()
        passengers.forEach(Entity::leaveVehicle)
        return passengers.isNotEmpty()
    }
}

internal class MockBukkitFarmChunkLeaseManager : FarmChunkLeaseManager {
    private data class Key(val world: String, val x: Int, val z: Int)

    private val retained = linkedSetOf<Key>()

    override fun retain(chunk: Chunk): Boolean = retained.add(chunk.key())

    override fun release(chunk: Chunk): Boolean = retained.remove(chunk.key())

    fun retainedCount(): Int = retained.size

    private fun Chunk.key() = Key(world.name, x, z)
}
